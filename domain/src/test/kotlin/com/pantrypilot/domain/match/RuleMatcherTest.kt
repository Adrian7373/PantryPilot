package com.pantrypilot.domain.match

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuleMatcherTest {
    private val candidates = listOf(
        RuleCandidate("milk", "Milk", "whole skim dairy"),
        RuleCandidate("chicken_raw", "Chicken (raw)", "breast thigh drumstick poultry"),
        RuleCandidate("broth_carton", "Broth (carton)", "stock"),
        RuleCandidate("spinach_bagged", "Baby spinach (bagged)", ""),
        RuleCandidate("leafy_greens", "Leafy greens", "lettuce kale spinach arugula"),
        RuleCandidate("tomatoes", "Tomatoes", ""),
        RuleCandidate("potatoes", "Potatoes", ""),
    )

    @Test
    fun `matches a product name by shared words`() {
        assertEquals("milk", RuleMatcher.bestMatch("Organic Whole Milk 1L", candidates))
    }

    @Test
    fun `plural and singular forms match`() {
        assertEquals("tomatoes", RuleMatcher.bestMatch("Roma tomato", candidates))
        assertEquals("potatoes", RuleMatcher.bestMatch("russet potato 5lb", candidates))
        assertEquals("milk", RuleMatcher.bestMatch("en:milks", candidates))
    }

    @Test
    fun `more shared words beats fewer`() {
        assertEquals("spinach_bagged", RuleMatcher.bestMatch("Organic baby spinach", candidates))
        assertEquals("leafy_greens", RuleMatcher.bestMatch("Kale", candidates))
    }

    @Test
    fun `a tie goes to the more specific rule`() {
        assertEquals("broth_carton", RuleMatcher.bestMatch("Chicken broth", candidates))
    }

    @Test
    fun `no shared words means no suggestion`() {
        assertNull(RuleMatcher.bestMatch("Dish soap", candidates))
        assertNull(RuleMatcher.bestMatch("", candidates))
    }
}
