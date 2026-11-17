package com.pantrypilot.app.receipt

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.ReceiptDraft
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.domain.receipt.OcrLine
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ReceiptStage { CAPTURE, READING, REVIEW }

data class ReviewItem(val draft: ReceiptDraft, val included: Boolean)

data class ReceiptState(
    val stage: ReceiptStage = ReceiptStage.CAPTURE,
    val items: List<ReviewItem> = emptyList(),
    val message: String? = null,
) {
    val includedCount: Int get() = items.count { it.included }
}

@HiltViewModel
class ReceiptViewModel @Inject constructor(
    private val repository: PantryRepository,
) : ViewModel() {
    val rules: StateFlow<List<ShelfLifeRuleEntity>> = repository.observeRules()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(ReceiptState())
    val state: StateFlow<ReceiptState> = _state.asStateFlow()

    fun reset() {
        _state.value = ReceiptState()
    }

    fun onReading() {
        _state.value = ReceiptState(stage = ReceiptStage.READING)
    }

    fun onRecognized(lines: List<OcrLine>) {
        viewModelScope.launch {
            val drafts = repository.draftReceipt(lines)
            _state.value = if (drafts.isEmpty()) {
                ReceiptState(message = "No items could be read. Try again with the receipt flat, filling the frame.")
            } else {
                // Lines with no matching rule (often non-food) start unticked.
                ReceiptState(ReceiptStage.REVIEW, drafts.map { ReviewItem(it, included = it.ruleId != null) })
            }
        }
    }

    fun onReadFailed() {
        _state.value = ReceiptState(message = "That photo could not be read. Try again.")
    }

    fun toggle(index: Int) = change(index) { it.copy(included = !it.included && it.draft.ruleId != null) }

    fun rename(index: Int, name: String) = change(index) { it.copy(draft = it.draft.copy(name = name)) }

    fun pickRule(index: Int, rule: ShelfLifeRuleEntity) =
        change(index) { it.copy(draft = it.draft.copy(ruleId = rule.id), included = true) }

    fun save(onSaved: () -> Unit) {
        val confirmed = _state.value.items
            .filter { it.included }
            .map { it.draft.copy(name = it.draft.name.trim().ifEmpty { it.draft.rawName }) }
        viewModelScope.launch {
            repository.addReceiptItems(confirmed)
            reset()
            onSaved()
        }
    }

    private fun change(index: Int, transform: (ReviewItem) -> ReviewItem) {
        _state.update { state ->
            state.copy(items = state.items.mapIndexed { i, item -> if (i == index) transform(item) else item })
        }
    }
}
