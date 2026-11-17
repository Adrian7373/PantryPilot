package com.pantrypilot.domain.depletion

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepletionEstimatorTest {
    private val start: Instant = Instant.parse("2026-11-01T09:00:00Z")
    private fun day(n: Double): Instant = start.plusMillis((n * Duration.ofDays(1).toMillis()).toLong())

    @Test
    fun `a litre of milk bought every week means about a litre a week used`() {
        val purchases = listOf(Purchase(day(0.0), 1000.0), Purchase(day(7.0), 1000.0), Purchase(day(14.0), 1000.0))
        assertEquals(1000.0 / 7, DepletionEstimator.dailyRate(purchases)!!, 1e-9)
    }

    @Test
    fun `the latest purchase is not counted, since it is still being used`() {
        val purchases = listOf(Purchase(day(0.0), 1000.0), Purchase(day(10.0), 4000.0))
        assertEquals(100.0, DepletionEstimator.dailyRate(purchases)!!, 1e-9)
    }

    @Test
    fun `no estimate from one purchase, or from purchases on the same day`() {
        assertNull(DepletionEstimator.dailyRate(listOf(Purchase(day(0.0), 1000.0))))
        assertNull(DepletionEstimator.dailyRate(listOf(Purchase(day(0.0), 1000.0), Purchase(day(0.5), 1000.0))))
    }

    @Test
    fun `estimates what is left from the time since the last purchase or answer`() {
        assertEquals(400.0, DepletionEstimator.estimateLeft(1000.0, 100.0, since = day(0.0), now = day(6.0)), 1e-9)
        assertEquals(0.0, DepletionEstimator.estimateLeft(1000.0, 100.0, since = day(0.0), now = day(30.0)), 1e-9)
    }

    @Test
    fun `asks only when the records look fine but the estimate looks low`() {
        assertTrue(DepletionEstimator.needsCheck(recorded = 1000.0, estimated = 200.0, par = 250.0))
        assertFalse(DepletionEstimator.needsCheck(recorded = 1000.0, estimated = 600.0, par = 250.0))
        // Already low on record: that goes straight on the shopping list instead.
        assertFalse(DepletionEstimator.needsCheck(recorded = 100.0, estimated = 0.0, par = 250.0))
    }
}
