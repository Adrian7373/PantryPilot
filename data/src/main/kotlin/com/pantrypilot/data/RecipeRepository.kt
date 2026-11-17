package com.pantrypilot.data

import android.content.Context
import androidx.room.withTransaction
import com.pantrypilot.data.db.Disposition
import com.pantrypilot.data.db.PantryDao
import com.pantrypilot.data.db.PantryDatabase
import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.data.db.ShoppingReason
import com.pantrypilot.domain.insights.UsageKind
import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.recipe.Lot
import com.pantrypilot.domain.recipe.Recipe
import com.pantrypilot.domain.recipe.RecipeIngredient
import com.pantrypilot.domain.recipe.RecipeMatch
import com.pantrypilot.domain.recipe.RecipeScorer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One ingredient line. [id] is a shelf-life rule id; [text] is how the recipe words it. */
@Serializable
data class RecipeLine(val id: String, val amount: Double, val unit: String, val text: String)

/** A bundled recipe. [extras] are cupboard basics such as salt that are assumed to be on hand. */
@Serializable
data class RecipeEntry(
    val id: String,
    val title: String,
    val servings: Int,
    val minutes: Int,
    val ingredients: List<RecipeLine>,
    val extras: List<String> = emptyList(),
    val steps: List<String>,
) {
    fun toRecipe(): Recipe = Recipe(
        id,
        ingredients.map { line ->
            val unit = MeasureUnit.fromSymbol(line.unit) ?: error("Unknown unit '${line.unit}' in recipe $id")
            RecipeIngredient(line.id, Quantity(line.amount, unit))
        },
    )
}

data class RankedRecipe(val entry: RecipeEntry, val match: RecipeMatch)

/** [usedUp] counts pantry items finished off; [addedToList] names staples that are now low. */
data class CookResult(val usedUp: Int, val addedToList: List<String>)

@Singleton
class RecipeRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PantryDatabase,
    private val pantryDao: PantryDao,
    private val shelfLifeDao: ShelfLifeDao,
    private val restock: RestockRepository,
    private val usage: UsageLog,
    private val clock: Clock,
) {
    private val book: List<RecipeEntry> by lazy {
        val text = context.assets.open(RECIPES_FILE).bufferedReader().use { it.readText() }
        Json.decodeFromString<List<RecipeEntry>>(text)
    }

    /** Every recipe, best rescue first. Re-ranked whenever the pantry changes. */
    fun observeRanked(): Flow<List<RankedRecipe>> =
        combine(pantryDao.observeActive(), shelfLifeDao.observeAll()) { items, rules -> rank(items, rules) }
            .flowOn(Dispatchers.IO)

    /**
     * Takes the recipe's ingredients out of the pantry, soonest-expiring first. Items finished
     * off are marked used, and staples that drop below par go on the shopping list.
     */
    suspend fun cook(recipeId: String): CookResult = withContext(Dispatchers.IO) {
        val entry = book.firstOrNull { it.id == recipeId } ?: return@withContext CookResult(0, emptyList())
        val touched = mutableSetOf<String>()
        var usedUp = 0
        val rules = db.withTransaction {
            val rules = shelfLifeDao.all()
            val items = pantryDao.activeItems()
            val match = score(entry, items, rules)
            val itemsById = items.associateBy { it.id }
            for (use in match.coverage.flatMap { it.uses }) {
                val item = itemsById[use.lotId] ?: continue
                usage.record(item, UsageKind.USED, use.amount)
                val left = item.amountLeft - use.amount
                val finished = left <= item.packageAmount * item.quantity * USED_UP_FRACTION
                pantryDao.updateItem(
                    item.copy(
                        amountLeft = if (finished) 0.0 else left,
                        disposition = if (finished) Disposition.CONSUMED else item.disposition,
                    ),
                )
                if (finished) usedUp++
                touched += item.ruleId
            }
            rules
        }
        val names = rules.associate { it.id to it.name }
        val added = touched.filter { restock.checkLow(it) }.mapNotNull { names[it] }
        CookResult(usedUp, added)
    }

    /** Puts what the recipe needs and the pantry lacks on the shopping list. Returns the names added. */
    suspend fun addMissingToList(recipeId: String): List<String> = withContext(Dispatchers.IO) {
        val entry = book.firstOrNull { it.id == recipeId } ?: return@withContext emptyList()
        val rules = shelfLifeDao.all()
        val match = score(entry, pantryDao.activeItems(), rules)
        val names = rules.associate { it.id to it.name }
        match.coverage
            .filter { it.shortfall > 0.0 }
            .mapNotNull { coverage -> names[coverage.ingredientId]?.let { coverage.ingredientId to it } }
            .filter { (ruleId, name) -> restock.addToList(name, ruleId, ShoppingReason.RECIPE, "For ${entry.title}") }
            .map { (_, name) -> name }
    }

    private fun rank(items: List<PantryItemEntity>, rules: List<ShelfLifeRuleEntity>): List<RankedRecipe> =
        book.map { RankedRecipe(it, score(it, items, rules)) }
            .sortedWith(compareByDescending<RankedRecipe> { it.match.score }.thenBy { it.entry.title })

    private fun score(entry: RecipeEntry, items: List<PantryItemEntity>, rules: List<ShelfLifeRuleEntity>): RecipeMatch {
        val rulesById = rules.associateBy { it.id }
        val lots = items.mapNotNull { item -> rulesById[item.ruleId]?.let { item.toLot(it) } }
        return RecipeScorer.score(entry.toRecipe(), rules.associate { it.id to it.toIngredient() }, lots, clock.instant())
    }

    private fun PantryItemEntity.toLot(rule: ShelfLifeRuleEntity): Lot {
        val total = packageAmount * quantity
        val cents = (priceCents ?: (rule.typicalPriceCents * quantity)).toDouble()
        return Lot(id, ruleId, amountLeft, if (total > 0.0) cents / total else 0.0, Instant.ofEpochMilli(expiresAt))
    }

    private companion object {
        const val RECIPES_FILE = "recipes.json"

        /** A package with less than this share left counts as used up: the last scrape of the jar. */
        const val USED_UP_FRACTION = 0.02
    }
}
