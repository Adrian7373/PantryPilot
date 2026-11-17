package com.pantrypilot.app.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.data.BarcodeLookup
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.data.suggestFor
import com.pantrypilot.domain.decay.StorageZone
import com.pantrypilot.domain.quantity.SizeParser
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class LookupStatus { IDLE, LOADING, FOUND, NOT_FOUND, FAILED }

data class AddItemState(
    val barcode: String = "",
    val name: String = "",
    val categories: String = "",
    val zone: StorageZone = StorageZone.FRIDGE,
    val price: String = "",
    val size: String = "",
    val sizeError: Boolean = false,
    val ruleId: String? = null,
    val lookup: LookupStatus = LookupStatus.IDLE,
) {
    val canLookUp: Boolean get() = PantryRepository.isValidBarcode(barcode) && lookup != LookupStatus.LOADING
}

@HiltViewModel
class AddItemViewModel @Inject constructor(
    private val repository: PantryRepository,
) : ViewModel() {
    val rules: StateFlow<List<ShelfLifeRuleEntity>> = repository.observeRules()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(AddItemState())
    val state: StateFlow<AddItemState> = _state.asStateFlow()

    // Once the user chooses a rule or zone by hand, suggestions stop overriding it.
    private var rulePicked = false
    private var zonePicked = false

    fun reset() {
        rulePicked = false
        zonePicked = false
        _state.value = AddItemState()
    }

    fun onBarcodeChange(text: String) {
        _state.update { it.copy(barcode = text.filter(Char::isDigit).take(14), lookup = LookupStatus.IDLE) }
    }

    fun onScanned(barcode: String) {
        onBarcodeChange(barcode)
        lookUp()
    }

    fun lookUp() {
        val barcode = _state.value.barcode
        if (!_state.value.canLookUp) return
        _state.update { it.copy(lookup = LookupStatus.LOADING) }
        viewModelScope.launch {
            val result = repository.lookupBarcode(barcode)
            if (_state.value.barcode != barcode) return@launch // edited while loading
            when (result) {
                is BarcodeLookup.Found -> {
                    _state.update {
                        it.copy(
                            name = result.name,
                            categories = result.categories,
                            size = it.size.ifBlank { result.size },
                            sizeError = false,
                            lookup = LookupStatus.FOUND,
                        )
                    }
                    val learned = rules.value.firstOrNull { it.id == result.learnedRuleId }
                    if (learned != null) select(learned) else suggest()
                }
                BarcodeLookup.NotFound -> _state.update { it.copy(lookup = LookupStatus.NOT_FOUND) }
                BarcodeLookup.Failed -> _state.update { it.copy(lookup = LookupStatus.FAILED) }
            }
        }
    }

    fun onNameChange(text: String) {
        _state.update { it.copy(name = text) }
        suggest()
    }

    fun onPriceChange(text: String) {
        if (text.isEmpty() || PRICE.matches(text)) _state.update { it.copy(price = text) }
    }

    fun onSizeChange(text: String) {
        _state.update { it.copy(size = text, sizeError = false) }
    }

    fun pickZone(zone: StorageZone) {
        zonePicked = true
        _state.update { it.copy(zone = zone) }
    }

    fun pickRule(rule: ShelfLifeRuleEntity) {
        rulePicked = true
        select(rule)
    }

    fun save(onSaved: () -> Unit) {
        val current = _state.value
        val rule = rules.value.firstOrNull { it.id == current.ruleId } ?: return
        val size = if (current.size.isBlank()) {
            null
        } else {
            SizeParser.parse(current.size) ?: run {
                _state.update { it.copy(sizeError = true) }
                return
            }
        }
        viewModelScope.launch {
            repository.addItem(
                name = current.name.trim().ifEmpty { rule.name },
                rule = rule,
                zone = current.zone,
                priceCents = current.price.toBigDecimalOrNull()
                    ?.multiply(BigDecimal(100))?.setScale(0, RoundingMode.HALF_UP)?.toLong(),
                barcode = current.barcode.takeIf(PantryRepository::isValidBarcode),
                size = size,
            )
            reset()
            onSaved()
        }
    }

    private fun suggest() {
        if (rulePicked) return
        val current = _state.value
        select(rules.value.suggestFor(current.name, current.categories))
    }

    private fun select(rule: ShelfLifeRuleEntity?) {
        _state.update {
            it.copy(
                ruleId = rule?.id,
                zone = if (rule != null && !zonePicked) rule.usualZone() else it.zone,
            )
        }
    }

    private companion object {
        val PRICE = Regex("\\d{0,7}(\\.\\d{0,2})?")
    }
}
