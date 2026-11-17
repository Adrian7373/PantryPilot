package com.pantrypilot.domain.quantity

import kotlin.math.abs
import kotlin.math.roundToLong

enum class Dimension { MASS, VOLUME, COUNT }

/** A unit, and how many of its dimension's base unit (gram, millilitre or piece) it holds. */
enum class MeasureUnit(val dimension: Dimension, val baseAmount: Double, val symbol: String) {
    GRAM(Dimension.MASS, 1.0, "g"),
    KILOGRAM(Dimension.MASS, 1000.0, "kg"),
    OUNCE(Dimension.MASS, 28.349523125, "oz"),
    POUND(Dimension.MASS, 453.59237, "lb"),
    MILLILITER(Dimension.VOLUME, 1.0, "ml"),
    LITER(Dimension.VOLUME, 1000.0, "l"),
    TEASPOON(Dimension.VOLUME, 4.92892159375, "tsp"),
    TABLESPOON(Dimension.VOLUME, 14.78676478125, "tbsp"),
    FLUID_OUNCE(Dimension.VOLUME, 29.5735295625, "fl oz"),
    CUP(Dimension.VOLUME, 236.5882365, "cup"),
    PINT(Dimension.VOLUME, 473.176473, "pt"),
    QUART(Dimension.VOLUME, 946.352946, "qt"),
    GALLON(Dimension.VOLUME, 3785.411784, "gal"),
    EACH(Dimension.COUNT, 1.0, "each"),
    ;

    companion object {
        private val NAMES: Map<String, MeasureUnit> = mapOf(
            "g" to GRAM, "gr" to GRAM, "gram" to GRAM, "grams" to GRAM,
            "kg" to KILOGRAM, "kilo" to KILOGRAM, "kilos" to KILOGRAM,
            "oz" to OUNCE, "lb" to POUND, "lbs" to POUND,
            "ml" to MILLILITER, "l" to LITER, "lt" to LITER, "ltr" to LITER, "liter" to LITER, "litre" to LITER,
            "tsp" to TEASPOON, "tbsp" to TABLESPOON, "floz" to FLUID_OUNCE,
            "cup" to CUP, "cups" to CUP, "pt" to PINT, "pint" to PINT, "qt" to QUART, "quart" to QUART,
            "gal" to GALLON, "gallon" to GALLON,
            "each" to EACH, "ea" to EACH, "ct" to EACH, "count" to EACH, "pc" to EACH, "pcs" to EACH, "pk" to EACH,
        )

        /** Reads a unit as written on a label or receipt, ignoring case, spaces and dots ("Fl. Oz"). */
        fun fromSymbol(text: String): MeasureUnit? = NAMES[text.lowercase().filter(Char::isLetter)]
    }
}

data class Quantity(val amount: Double, val unit: MeasureUnit) {
    /** "1 gal", "0.68 kg", "12 each". */
    fun describe(): String {
        val rounded = amount.roundToLong()
        val number = if (abs(amount - rounded) < 1e-9) rounded.toString() else amount.toString()
        return "$number ${unit.symbol}"
    }
}

/** How an ingredient's weight relates to its volume and to one piece of it. Null where it is not known. */
data class Density(val gramsPerMl: Double? = null, val gramsPerEach: Double? = null)

object UnitConverter {
    /**
     * Converts [quantity] into [target]. Within a dimension this is exact. Across dimensions it goes
     * through weight using [density], and returns null when the density it needs is not known.
     */
    fun convert(quantity: Quantity, target: MeasureUnit, density: Density = Density()): Double? {
        val base = quantity.amount * quantity.unit.baseAmount
        if (quantity.unit.dimension == target.dimension) return base / target.baseAmount
        val grams = toGrams(base, quantity.unit.dimension, density) ?: return null
        val targetBase = fromGrams(grams, target.dimension, density) ?: return null
        return targetBase / target.baseAmount
    }

    private fun toGrams(base: Double, dimension: Dimension, density: Density): Double? = when (dimension) {
        Dimension.MASS -> base
        Dimension.VOLUME -> density.gramsPerMl?.let { base * it }
        Dimension.COUNT -> density.gramsPerEach?.let { base * it }
    }

    private fun fromGrams(grams: Double, dimension: Dimension, density: Density): Double? = when (dimension) {
        Dimension.MASS -> grams
        Dimension.VOLUME -> density.gramsPerMl?.let { grams / it }
        Dimension.COUNT -> density.gramsPerEach?.let { grams / it }
    }
}

/** Finds package sizes such as "1GAL", "5 oz", "500g" or "12CT" in label and receipt text. */
object SizeParser {
    private const val UNITS = """fl\.?\s?oz|kg|gal|g|lbs|lb|oz|ml|ltr|lt|l|qt|pt|ct|ea|pk|pcs|pc"""
    private val SIZE = Regex("""(?<![\d.,])(\d+(?:[.,]\d+)?)\s?($UNITS)\b""", RegexOption.IGNORE_CASE)
    private val TYPED = Regex("""^(\d+(?:[.,]\d+)?)\s*([\p{L} .]*)$""")

    /** OCR often reads "OZ" after a digit as "0Z", turning "5OZ" into "50Z". */
    private val MISREAD_OZ = Regex("""(\d)0Z\b""", RegexOption.IGNORE_CASE)

    /** The first size mentioned anywhere in [text], or null. */
    fun find(text: String): Quantity? {
        val match = SIZE.find(MISREAD_OZ.replace(text, "$1OZ")) ?: return null
        val unit = MeasureUnit.fromSymbol(match.groupValues[2]) ?: return null
        return Quantity(number(match.groupValues[1]), unit).takeIf { it.amount > 0.0 }
    }

    /** Reads a size the user typed, such as "500 g" or "1,5 l". A bare number counts pieces. */
    fun parse(text: String): Quantity? {
        val match = TYPED.matchEntire(text.trim()) ?: return null
        val unitText = match.groupValues[2].trim()
        val unit = if (unitText.isEmpty()) MeasureUnit.EACH else MeasureUnit.fromSymbol(unitText) ?: return null
        return Quantity(number(match.groupValues[1]), unit).takeIf { it.amount > 0.0 }
    }

    private fun number(text: String): Double = text.replace(',', '.').toDouble()
}
