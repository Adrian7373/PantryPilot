package com.pantrypilot.domain.receipt

import com.pantrypilot.domain.match.RuleCandidate
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiptNormalizerTest {
    private val candidates = listOf(
        RuleCandidate("milk", "Milk", "whole skim lowfat dairy"),
        RuleCandidate("chicken_raw", "Chicken (raw)", "breast thigh drumstick wing poultry"),
        RuleCandidate("ground_beef", "Ground beef", "mince minced burger"),
        RuleCandidate("bacon", "Bacon", ""),
        RuleCandidate("bananas", "Bananas", ""),
        RuleCandidate("spinach_bagged", "Baby spinach (bagged)", ""),
        RuleCandidate("leafy_greens", "Leafy greens", "lettuce kale arugula cabbage salad"),
        RuleCandidate("tomatoes", "Tomatoes", ""),
        RuleCandidate("canned_tomatoes", "Canned tomatoes", "diced crushed"),
    )

    private fun normalize(raw: String) = ReceiptNormalizer.normalize(raw, candidates)

    @Test
    fun `expands consonant shorthand and drops the size`() {
        assertEquals(NormalizedItem("Organic baby spinach", "spinach_bagged"), normalize("ORG BBY SPN 5OZ"))
        assertEquals(NormalizedItem("Whole milk", "milk"), normalize("WHL MLK 1GAL"))
        assertEquals(NormalizedItem("Chicken breast boneless", "chicken_raw"), normalize("CHKN BRST BNLS"))
    }

    @Test
    fun `expands dictionary abbreviations`() {
        assertEquals(NormalizedItem("Ground beef", "ground_beef"), normalize("GRND BF 80/20"))
    }

    @Test
    fun `a prefix resolves to the full word`() {
        assertEquals(NormalizedItem("Banana", "bananas"), normalize("BAN"))
        assertEquals(NormalizedItem("Bananas", "bananas"), normalize("BANANAS"))
    }

    @Test
    fun `prefers the rule that explains more of the name`() {
        assertEquals("canned_tomatoes", normalize("DICED TOMATOES 14OZ").ruleId)
        assertEquals("tomatoes", normalize("ROMA TOMATOES").ruleId)
    }

    @Test
    fun `non-food stays readable with no rule`() {
        assertEquals(NormalizedItem("Paper towels", null), normalize("PAPER TOWELS"))
    }
}
