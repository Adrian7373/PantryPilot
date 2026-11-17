package com.pantrypilot.app.restock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.data.CheckAnswer
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.RestockRepository
import com.pantrypilot.data.StapleCheck
import com.pantrypilot.data.StapleStatus
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.data.db.ShoppingItemEntity
import com.pantrypilot.data.db.ShoppingReason
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RestockViewModel @Inject constructor(
    private val restock: RestockRepository,
    pantry: PantryRepository,
) : ViewModel() {
    val shopping: StateFlow<List<ShoppingItemEntity>> = restock.observeShoppingList()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val staples: StateFlow<List<StapleStatus>> = restock.observeStaples()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checks: StateFlow<List<StapleCheck>> = restock.observeChecks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val rules: StateFlow<List<ShelfLifeRuleEntity>> = pantry.observeRules()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun bought(id: String) = launch { restock.removeFromList(id) }

    fun answer(ruleId: String, answer: CheckAnswer) = launch { restock.answerCheck(ruleId, answer) }

    fun add(name: String) = launch { restock.addToList(name, ruleId = null, reason = ShoppingReason.MANUAL) }

    fun keepStocked(rule: ShelfLifeRuleEntity) = launch { restock.addStaple(rule) }

    fun stopKeeping(ruleId: String) = launch { restock.removeStaple(ruleId) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
