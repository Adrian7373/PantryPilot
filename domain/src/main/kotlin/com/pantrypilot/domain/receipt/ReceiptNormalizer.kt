package com.pantrypilot.domain.receipt

import com.pantrypilot.domain.match.RuleCandidate
import com.pantrypilot.domain.match.RuleMatcher

/** A receipt name made readable, with the shelf-life rule it most likely belongs to. */
data class NormalizedItem(val displayName: String, val ruleId: String?)

/**
 * Turns a cryptic point-of-sale name such as "ORG BBY SPN 5OZ" into "Organic baby spinach" and a
 * suggested rule. Sizes are dropped, known abbreviations are expanded, and the remaining
 * shorthand is resolved against the words of the rule it matches.
 */
object ReceiptNormalizer {
    fun normalize(rawName: String, candidates: List<RuleCandidate>): NormalizedItem {
        val words = rawName.lowercase()
            .split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.isNotEmpty() && !it[0].isDigit() } // "5oz", "1gal", "80", product codes
            .map { ABBREVIATIONS[it] ?: it }

        val match = RuleMatcher.bestFuzzyMatch(words.map(RuleMatcher::singular), candidates)
        val display = words
            .joinToString(" ") { word -> match?.resolved?.get(RuleMatcher.singular(word)) ?: word }
            .replaceFirstChar(Char::uppercase)
        return NormalizedItem(display.ifEmpty { rawName }, match?.id)
    }

    /** Shorthand that the rule words cannot resolve: modifiers, and forms too short to match safely. */
    private val ABBREVIATIONS = mapOf(
        "org" to "organic", "orgnc" to "organic", "whl" to "whole", "frsh" to "fresh",
        "frz" to "frozen", "frzn" to "frozen", "bnls" to "boneless", "sknls" to "skinless",
        "lg" to "large", "lrg" to "large", "sm" to "small", "med" to "medium", "grn" to "green",
        "chk" to "chicken", "bf" to "beef", "grd" to "ground", "grnd" to "ground",
        "yog" to "yogurt", "tom" to "tomato", "pot" to "potato", "ban" to "banana",
        "veg" to "vegetable", "sce" to "sauce", "chs" to "cheese", "bttr" to "butter",
    )
}
