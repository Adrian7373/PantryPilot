package com.pantrypilot.domain.decay

import com.pantrypilot.domain.decay.ItemEvent.Opened
import com.pantrypilot.domain.decay.ItemEvent.Stored
import com.pantrypilot.domain.decay.StorageZone.FREEZER
import com.pantrypilot.domain.decay.StorageZone.FRIDGE
import com.pantrypilot.domain.decay.StorageZone.PANTRY
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class DecayEngineTest {
    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")
    private fun day(n: Long): Instant = t0.plus(Duration.ofDays(n))
    private fun days(n: Long): Duration = Duration.ofDays(n)

    private val broth = ShelfLifeRule(
        unopened = ShelfLife(pantry = days(365)),
        opened = ShelfLife(fridge = days(4)),
    )
    private val milk = ShelfLifeRule(
        unopened = ShelfLife(fridge = days(7), freezer = days(90)),
        opened = ShelfLife(fridge = days(5), freezer = days(90)),
    )
    private val chicken = ShelfLifeRule(ShelfLife(fridge = days(2), freezer = days(270)))

    @Test
    fun `unopened pantry item lasts its full shelf life`() {
        val result = DecayEngine.evaluate(broth, listOf(Stored(t0, PANTRY)), now = day(10))
        assertEquals(day(365), result.expiresAt)
        assertEquals(FreshnessStatus.FRESH, result.status)
    }

    @Test
    fun `opening a shelf-stable item shifts the countdown from months to days`() {
        val events = listOf(Stored(t0, PANTRY), Stored(day(100), FRIDGE), Opened(day(100)))
        val result = DecayEngine.evaluate(broth, events, now = day(101))
        assertEquals(day(104), result.expiresAt)
    }

    @Test
    fun `opened broth left in the pantry expires within hours`() {
        val events = listOf(Stored(t0, PANTRY), Opened(day(100)))
        val result = DecayEngine.evaluate(broth, events, now = day(101))
        assertEquals(day(100).plus(Duration.ofHours(2)), result.expiresAt)
        assertEquals(FreshnessStatus.EXPIRED, result.status)
    }

    @Test
    fun `opening never extends past the unopened expiry`() {
        val events = listOf(Stored(t0, FRIDGE), Opened(day(5)))
        val result = DecayEngine.evaluate(milk, events, now = day(5))
        assertEquals(day(7), result.expiresAt)
    }

    @Test
    fun `freezer pauses decay and thawing resumes it`() {
        val events = listOf(Stored(t0, FRIDGE), Stored(day(1), FREEZER), Stored(day(31), FRIDGE))
        val result = DecayEngine.evaluate(chicken, events, now = day(31))
        assertEquals(day(32), result.expiresAt)
        assertEquals(FreshnessStatus.USE_SOON, result.status)
    }

    @Test
    fun `frozen item expires when the freezer shelf life runs out`() {
        val events = listOf(Stored(t0, FRIDGE), Stored(day(1), FREEZER))
        val result = DecayEngine.evaluate(chicken, events, now = day(50))
        assertEquals(day(271), result.expiresAt)
        assertEquals(FreshnessStatus.FRESH, result.status)
    }

    @Test
    fun `thawing after the freezer limit does not revive the item`() {
        val events = listOf(Stored(t0, FRIDGE), Stored(day(1), FREEZER), Stored(day(300), FRIDGE))
        val result = DecayEngine.evaluate(chicken, events, now = day(300))
        assertEquals(day(271), result.expiresAt)
        assertEquals(FreshnessStatus.EXPIRED, result.status)
    }

    @Test
    fun `moving from pantry to fridge keeps the fraction of life left`() {
        val bread = ShelfLifeRule(ShelfLife(pantry = days(4), fridge = days(12)))
        val events = listOf(Stored(t0, PANTRY), Stored(day(2), FRIDGE))
        val result = DecayEngine.evaluate(bread, events, now = day(2))
        assertEquals(day(8), result.expiresAt)
    }

    @Test
    fun `events after now are ignored`() {
        val events = listOf(Stored(t0, FRIDGE), Stored(day(1), FREEZER))
        val result = DecayEngine.evaluate(chicken, events, now = t0.plus(Duration.ofHours(1)))
        assertEquals(day(2), result.expiresAt)
        assertEquals(FreshnessStatus.USE_SOON, result.status)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `history must start with a stored event`() {
        DecayEngine.evaluate(milk, listOf(Opened(t0)), now = day(1))
    }
}
