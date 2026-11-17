package com.pantrypilot.domain.quantity

import com.pantrypilot.domain.quantity.MeasureUnit.CUP
import com.pantrypilot.domain.quantity.MeasureUnit.EACH
import com.pantrypilot.domain.quantity.MeasureUnit.FLUID_OUNCE
import com.pantrypilot.domain.quantity.MeasureUnit.GALLON
import com.pantrypilot.domain.quantity.MeasureUnit.GRAM
import com.pantrypilot.domain.quantity.MeasureUnit.KILOGRAM
import com.pantrypilot.domain.quantity.MeasureUnit.LITER
import com.pantrypilot.domain.quantity.MeasureUnit.MILLILITER
import com.pantrypilot.domain.quantity.MeasureUnit.OUNCE
import com.pantrypilot.domain.quantity.MeasureUnit.POUND
import com.pantrypilot.domain.quantity.MeasureUnit.TABLESPOON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UnitsTest {
    private val flour = Density(gramsPerMl = 0.53)
    private val onion = Density(gramsPerMl = 0.68, gramsPerEach = 150.0)

    private fun convert(amount: Double, from: MeasureUnit, to: MeasureUnit, density: Density = Density()) =
        UnitConverter.convert(Quantity(amount, from), to, density)

    @Test
    fun `converts within a dimension exactly`() {
        assertEquals(3785.411784, convert(1.0, GALLON, MILLILITER)!!, 1e-6)
        assertEquals(141.747615625, convert(5.0, OUNCE, GRAM)!!, 1e-9)
        assertEquals(0.5, convert(500.0, GRAM, KILOGRAM)!!, 1e-12)
    }

    @Test
    fun `converts volume to weight with the ingredient's density`() {
        // The packaging says 1 kg of flour; the recipe says 2 cups.
        assertEquals(250.78, convert(2.0, CUP, GRAM, flour)!!, 0.01)
        assertEquals(13.46, convert(1.0, TABLESPOON, GRAM, Density(gramsPerMl = 0.91))!!, 0.01)
    }

    @Test
    fun `converts between pieces, weight and volume`() {
        assertEquals(300.0, convert(2.0, EACH, GRAM, onion)!!, 1e-9)
        assertEquals(2.0, convert(0.3, KILOGRAM, EACH, onion)!!, 1e-9)
        // A cup of chopped onion is about one onion.
        assertEquals(1.07, convert(1.0, CUP, EACH, onion)!!, 0.01)
    }

    @Test
    fun `cannot convert without the density it needs`() {
        assertNull(convert(1.0, CUP, GRAM))
        assertNull(convert(1.0, EACH, GRAM, flour))
    }

    @Test
    fun `reads units as printed`() {
        assertEquals(FLUID_OUNCE, MeasureUnit.fromSymbol("Fl. Oz"))
        assertEquals(POUND, MeasureUnit.fromSymbol("LBS"))
        assertEquals(EACH, MeasureUnit.fromSymbol("ct"))
        assertNull(MeasureUnit.fromSymbol("bunch"))
    }

    @Test
    fun `finds sizes in receipt and label text`() {
        assertEquals(Quantity(1.0, GALLON), SizeParser.find("WHL MLK 1GAL"))
        assertEquals(Quantity(5.0, OUNCE), SizeParser.find("ORG BBY SPN 5OZ"))
        assertEquals(Quantity(12.0, EACH), SizeParser.find("EGGS LRG 12CT"))
        assertEquals(Quantity(0.68, KILOGRAM), SizeParser.find("0.68 kg @ 1.29/kg"))
        assertEquals(Quantity(1.5, LITER), SizeParser.find("Olive oil 1,5 L"))
        assertNull(SizeParser.find("GRND BF 80/20"))
        assertNull(SizeParser.find("2 LEMONS"))
    }

    @Test
    fun `undoes OCR reading OZ as 0Z`() {
        assertEquals(Quantity(5.0, OUNCE), SizeParser.find("ORG BBY SPN 50Z"))
    }

    @Test
    fun `parses sizes the user typed`() {
        assertEquals(Quantity(500.0, GRAM), SizeParser.parse("500 g"))
        assertEquals(Quantity(500.0, GRAM), SizeParser.parse("500g"))
        assertEquals(Quantity(1.5, LITER), SizeParser.parse(" 1,5 l "))
        assertEquals(Quantity(6.0, EACH), SizeParser.parse("6"))
        assertNull(SizeParser.parse("a bag"))
        assertNull(SizeParser.parse("0 g"))
    }

    @Test
    fun `describes a quantity`() {
        assertEquals("1 gal", Quantity(1.0, GALLON).describe())
        assertEquals("0.68 kg", Quantity(0.68, KILOGRAM).describe())
    }
}
