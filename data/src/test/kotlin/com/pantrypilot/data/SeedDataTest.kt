package com.pantrypilot.data

import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.quantity.UnitConverter
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks that the bundled rules and recipes fit together, so a typo fails here and not in the app. */
class SeedDataTest {
    private val rules: List<ShelfLifeRuleEntity> =
        Json.decodeFromString(File("src/main/assets/shelf_life_rules.json").readText())
    private val recipes: List<RecipeEntry> =
        Json.decodeFromString(File("src/main/assets/recipes.json").readText())
    private val rulesById = rules.associateBy { it.id }

    @Test
    fun `every rule has a stock unit, a package and a price`() {
        assertEquals(rules.size, rulesById.size)
        for (rule in rules) {
            assertTrue(rule.id, MeasureUnit.fromSymbol(rule.stockUnit) in setOf(MeasureUnit.GRAM, MeasureUnit.MILLILITER, MeasureUnit.EACH))
            assertTrue(rule.id, rule.packageSize > 0.0)
            assertTrue(rule.id, rule.typicalPriceCents > 0)
        }
    }

    @Test
    fun `every recipe line names a rule and converts to its stock unit`() {
        assertEquals(recipes.size, recipes.map { it.id }.toSet().size)
        for (recipe in recipes) {
            assertTrue(recipe.id, recipe.steps.isNotEmpty())
            for (line in recipe.ingredients) {
                val where = "${recipe.id} / ${line.id}"
                val rule = rulesById[line.id]
                assertNotNull("$where: no such rule", rule)
                val unit = MeasureUnit.fromSymbol(line.unit)
                assertNotNull("$where: unknown unit ${line.unit}", unit)
                val ingredient = rule!!.toIngredient()
                val converted = UnitConverter.convert(Quantity(line.amount, unit!!), ingredient.stockUnit, ingredient.density)
                assertNotNull("$where: ${line.unit} does not convert to ${rule.stockUnit}", converted)
                // A recipe should never need more than a few packages of anything.
                assertTrue("$where: needs $converted ${rule.stockUnit}", converted!! <= rule.packageSize * 4)
            }
        }
    }

    @Test
    fun `printed package sizes convert to the stock unit`() {
        val milk = rulesById.getValue("milk")
        assertEquals(3785.41, milk.packageAmount(Quantity(1.0, MeasureUnit.GALLON)), 0.01)
        assertEquals(1000.0, milk.packageAmount(null), 0.0)
        assertEquals(12.0, rulesById.getValue("eggs").packageAmount(Quantity(12.0, MeasureUnit.EACH)), 0.0)
        // Ground beef has no volume density, so a size in litres cannot convert and the typical package is used.
        assertEquals(500.0, rulesById.getValue("ground_beef").packageAmount(Quantity(1.0, MeasureUnit.LITER)), 0.0)
    }
}
