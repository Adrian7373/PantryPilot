package com.pantrypilot.app.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pantrypilot.app.shortFoodName
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.RankedRecipe
import com.pantrypilot.data.RecipeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class RecipesViewModel @Inject constructor(
    private val recipes: RecipeRepository,
    pantry: PantryRepository,
) : ViewModel() {
    /** Null until the first ranking is ready. */
    val ranked: StateFlow<List<RankedRecipe>?> = recipes.observeRanked()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Short food names by rule id, for "Uses chicken, spinach". */
    val foodNames: StateFlow<Map<String, String>> = pantry.observeRules()
        .map { rules -> rules.associate { it.id to it.name.shortFoodName() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    fun cook(recipeId: String) {
        viewModelScope.launch {
            val result = recipes.cook(recipeId)
            val parts = buildList {
                add("Pantry updated.")
                if (result.usedUp == 1) add("1 item used up.")
                if (result.usedUp > 1) add("${result.usedUp} items used up.")
                if (result.addedToList.isNotEmpty()) add("Added ${result.addedToList.joinToString()} to your shopping list.")
            }
            _messages.send(parts.joinToString(" "))
        }
    }

    fun addMissing(recipeId: String) {
        viewModelScope.launch {
            val added = recipes.addMissingToList(recipeId)
            _messages.send(
                if (added.isEmpty()) "Already on your shopping list." else "Added ${added.joinToString()} to your shopping list.",
            )
        }
    }
}
