package com.pantrypilot.domain.recipe

import com.pantrypilot.domain.quantity.Density
import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.quantity.UnitConverter
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.pow

/** How a food is counted in the pantry: in grams, millilitres or pieces. */
data class Ingredient(val id: String, val stockUnit: MeasureUnit, val density: Density = Density())

data class RecipeIngredient(val ingredientId: String, val amount: Quantity)

data class Recipe(val id: String, val ingredients: List<RecipeIngredient>)

/**
 * One purchase in the pantry. [amountLeft] is in its ingredient's stock unit, and
 * [centsPerUnit] is what one stock unit of it cost.
 */
data class Lot(
    val id: Long,
    val ingredientId: String,
    val amountLeft: Double,
    val centsPerUnit: Double,
    val expiresAt: Instant,
)

/** How much of a lot a recipe takes, in the ingredient's stock unit. */
data class LotUse(val lotId: Long, val amount: Double)

/**
 * How well the pantry covers one ingredient. [shortfall] runs from 0 (enough on hand) to 1
 * (none on hand). [rescueCents] is the urgency-weighted value of what the recipe would use up.
 */
data class Coverage(
    val ingredientId: String,
    val uses: List<LotUse>,
    val rescueCents: Double,
    val shortfall: Double,
    val soonestExpiry: Instant?,
) {
    val missing: Boolean get() = shortfall >= 1.0
    val short: Boolean get() = shortfall > 0.0 && shortfall < 1.0
}

data class RecipeMatch(val recipeId: String, val score: Double, val coverage: List<Coverage>) {
    val missing: List<String> get() = coverage.filter { it.missing }.map { it.ingredientId }
    val short: List<String> get() = coverage.filter { it.short }.map { it.ingredientId }
    val canCook: Boolean get() = coverage.none { it.shortfall > 0.0 }
}

/**
 * Ranks recipes by how much food they rescue, in dollars:
 *
 *     score = Σ (urgency × value of the amount used) − penalty × Σ shortfall
 *
 * Urgency halves for every [URGENCY_HALF_LIFE] of shelf life left, so $6 of chicken expiring
 * tomorrow outweighs a $2.50 bag of rice that keeps for a year. Each ingredient not on hand costs
 * [MISSING_PENALTY_CENTS], scaled by how much of it is missing. Ingredients are drawn
 * soonest-expiring first, which is also the order they are deducted in when the recipe is
 * cooked. Expired lots are never used.
 */
object RecipeScorer {
    val URGENCY_HALF_LIFE: Duration = Duration.ofDays(2)
    /**
     * A recipe is worth shopping for when it rescues more than a dollar per missing ingredient.
     * At $2, a recipe using $9 of chicken that expires tomorrow lost to a smoothie of long-dated
     * yogurt, only because it also needed garlic, rice and oil.
     */
    const val MISSING_PENALTY_CENTS = 100.0

    /** Being this little short of an ingredient still counts as having it. */
    private const val TOLERANCE = 0.05

    fun urgency(expiresAt: Instant, now: Instant): Double {
        val left = Duration.between(now, expiresAt).toMillis().coerceAtLeast(0).toDouble()
        return 0.5.pow(left / URGENCY_HALF_LIFE.toMillis())
    }

    fun score(recipe: Recipe, ingredients: Map<String, Ingredient>, lots: List<Lot>, now: Instant): RecipeMatch {
        val usable = lots
            .filter { it.expiresAt > now && it.amountLeft > 0.0 }
            .groupBy { it.ingredientId }
            .mapValues { (_, group) -> group.sortedBy { it.expiresAt } }

        val coverage = recipe.ingredients
            .groupBy { it.ingredientId }
            .map { (id, lines) -> cover(id, amountNeeded(lines, ingredients[id]), usable[id].orEmpty(), now) }

        val rescue = coverage.sumOf { it.rescueCents }
        val penalty = coverage.sumOf { it.shortfall } * MISSING_PENALTY_CENTS
        return RecipeMatch(recipe.id, (rescue - penalty) / 100.0, coverage)
    }

    /** The total needed in the stock unit, or null when a line cannot be converted. */
    private fun amountNeeded(lines: List<RecipeIngredient>, ingredient: Ingredient?): Double? {
        if (ingredient == null) return null
        var total = 0.0
        for (line in lines) {
            total += UnitConverter.convert(line.amount, ingredient.stockUnit, ingredient.density) ?: return null
        }
        return total
    }

    private fun cover(id: String, needed: Double?, lots: List<Lot>, now: Instant): Coverage {
        val soonest = lots.firstOrNull()?.expiresAt
        if (lots.isEmpty()) return Coverage(id, emptyList(), 0.0, shortfall = 1.0, soonestExpiry = null)
        // The amount cannot be worked out, so having any counts as enough, and nothing is deducted.
        if (needed == null) return Coverage(id, emptyList(), 0.0, shortfall = 0.0, soonestExpiry = soonest)

        val uses = mutableListOf<LotUse>()
        var remaining = needed
        var rescue = 0.0
        for (lot in lots) {
            if (remaining <= 0.0) break
            val take = min(lot.amountLeft, remaining)
            uses += LotUse(lot.id, take)
            rescue += urgency(lot.expiresAt, now) * take * lot.centsPerUnit
            remaining -= take
        }
        val shortfall = if (needed <= 0.0 || remaining <= needed * TOLERANCE) 0.0 else remaining / needed
        return Coverage(id, uses, rescue, shortfall, soonest)
    }
}
