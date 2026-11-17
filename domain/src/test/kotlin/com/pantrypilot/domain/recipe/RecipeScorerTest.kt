package com.pantrypilot.domain.recipe

import com.pantrypilot.domain.quantity.Density
import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.MeasureUnit.CUP
import com.pantrypilot.domain.quantity.MeasureUnit.EACH
import com.pantrypilot.domain.quantity.MeasureUnit.GRAM
import com.pantrypilot.domain.quantity.Quantity
import java.time.Duration
import java.time.Instant
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeScorerTest {
    private val now: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private fun inDays(days: Double): Instant = now.plusMillis((days * Duration.ofDays(1).toMillis()).toLong())

    private val ingredients = listOf(
        Ingredient("chicken", GRAM),
        Ingredient("rice", GRAM, Density(gramsPerMl = 0.78)),
        Ingredient("flour", GRAM, Density(gramsPerMl = 0.53)),
        Ingredient("eggs", EACH, Density(gramsPerEach = 50.0)),
        Ingredient("onions", GRAM, Density(gramsPerEach = 150.0)),
    ).associateBy { it.id }

    private fun recipe(vararg lines: Pair<String, Quantity>) =
        Recipe("r", lines.map { (id, amount) -> RecipeIngredient(id, amount) })

    private fun amount(value: Double, unit: MeasureUnit = GRAM) = Quantity(value, unit)

    private fun score(recipe: Recipe, lots: List<Lot>) = RecipeScorer.score(recipe, ingredients, lots, now)

    @Test
    fun `urgency halves every two days of shelf life left`() {
        assertEquals(1.0, RecipeScorer.urgency(now, now), 1e-12)
        assertEquals(0.5, RecipeScorer.urgency(inDays(2.0), now), 1e-12)
        assertEquals(0.25, RecipeScorer.urgency(inDays(4.0), now), 1e-12)
    }

    @Test
    fun `rescuing costly food that expires soon outranks cheap food that keeps`() {
        val lots = listOf(
            Lot(1, "chicken", 500.0, centsPerUnit = 1.2, expiresAt = inDays(1.0)), // $6, tomorrow
            Lot(2, "rice", 1000.0, centsPerUnit = 0.25, expiresAt = inDays(365.0)), // $2.50, next year
        )
        val chicken = score(recipe("chicken" to amount(400.0)), lots)
        val rice = score(recipe("rice" to amount(200.0)), lots)

        assertEquals(400 * 1.2 * 0.5.pow(0.5) / 100, chicken.score, 1e-9)
        assertTrue(rice.score < 0.01)
        assertTrue(chicken.score > rice.score)
    }

    @Test
    fun `each missing ingredient costs a fixed penalty`() {
        val lots = listOf(Lot(1, "chicken", 500.0, 1.2, inDays(1.0)))
        val complete = score(recipe("chicken" to amount(400.0)), lots)
        val incomplete = score(recipe("chicken" to amount(400.0), "onions" to amount(1.0, EACH)), lots)

        assertEquals(complete.score - RecipeScorer.MISSING_PENALTY_CENTS / 100, incomplete.score, 1e-9)
        assertEquals(listOf("onions"), incomplete.missing)
        assertTrue(complete.canCook)
        assertFalse(incomplete.canCook)
    }

    @Test
    fun `urgent costly food outranks long-dated food even with a few cheap basics missing`() {
        // The case seen on the emulator: chicken expiring tomorrow versus a smoothie of food that keeps.
        val lots = listOf(
            Lot(1, "chicken", 500.0, centsPerUnit = 875.0 / 500, expiresAt = inDays(1.9)),
            Lot(2, "eggs", 12.0, centsPerUnit = 400.0 / 12, expiresAt = inDays(14.0)),
        )
        val chicken = score(
            recipe("chicken" to amount(500.0), "rice" to amount(200.0), "onions" to amount(1.0, EACH), "flour" to amount(10.0)),
            lots,
        )
        val eggsOnly = score(recipe("eggs" to amount(3.0, EACH)), lots)

        assertTrue(chicken.score > eggsOnly.score)
    }

    @Test
    fun `draws from the soonest-expiring lot first and splits across lots`() {
        val lots = listOf(
            Lot(1, "chicken", 300.0, 1.0, inDays(3.0)),
            Lot(2, "chicken", 300.0, 1.0, inDays(1.0)),
        )
        val match = score(recipe("chicken" to amount(400.0)), lots)

        assertEquals(listOf(LotUse(2, 300.0), LotUse(1, 100.0)), match.coverage.single().uses)
        assertEquals(inDays(1.0), match.coverage.single().soonestExpiry)
    }

    @Test
    fun `expired lots are never used`() {
        val lots = listOf(Lot(1, "chicken", 500.0, 1.2, inDays(-1.0)))
        val match = score(recipe("chicken" to amount(400.0)), lots)

        assertEquals(listOf("chicken"), match.missing)
        assertTrue(match.coverage.single().uses.isEmpty())
    }

    @Test
    fun `converts recipe cups and pieces into the stock unit`() {
        val lots = listOf(
            Lot(1, "flour", 1000.0, 0.2, inDays(200.0)),
            Lot(2, "eggs", 12.0, 30.0, inDays(20.0)),
        )
        val match = score(recipe("flour" to amount(2.0, CUP), "eggs" to amount(3.0, EACH)), lots)

        assertEquals(250.78, match.coverage.first { it.ingredientId == "flour" }.uses.single().amount, 0.01)
        assertEquals(3.0, match.coverage.first { it.ingredientId == "eggs" }.uses.single().amount, 1e-9)
        assertTrue(match.canCook)
    }

    @Test
    fun `lines for the same ingredient are added together`() {
        val lots = listOf(Lot(1, "chicken", 500.0, 1.0, inDays(5.0)))
        val match = score(recipe("chicken" to amount(200.0), "chicken" to amount(150.0)), lots)

        assertEquals(350.0, match.coverage.single().uses.single().amount, 1e-9)
    }

    @Test
    fun `a partial shortfall is scaled and a small one is ignored`() {
        val half = score(recipe("chicken" to amount(400.0)), listOf(Lot(1, "chicken", 200.0, 1.0, inDays(5.0))))
        assertEquals(0.5, half.coverage.single().shortfall, 1e-9)
        assertEquals(listOf("chicken"), half.short)

        val nearly = score(recipe("chicken" to amount(400.0)), listOf(Lot(1, "chicken", 390.0, 1.0, inDays(5.0))))
        assertTrue(nearly.canCook)
    }

    @Test
    fun `an amount that cannot be converted counts as on hand without deducting`() {
        // Onions have a weight per piece but no density, so a cup of them cannot be weighed.
        val match = score(recipe("onions" to amount(1.0, CUP)), listOf(Lot(1, "onions", 1000.0, 0.25, inDays(30.0))))

        assertTrue(match.canCook)
        assertTrue(match.coverage.single().uses.isEmpty())
    }
}
