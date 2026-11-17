package com.pantrypilot.domain.digest

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestPlannerTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val newYork = ZoneId.of("America/New_York")
    private val at = LocalTime.of(17, 30)

    private fun time(text: String, zone: ZoneId): ZonedDateTime = ZonedDateTime.of(java.time.LocalDateTime.parse(text), zone)

    @Test
    fun `runs later today when the time has not passed`() {
        assertEquals(time("2026-11-17T17:30", manila), DigestPlanner.nextRun(time("2026-11-17T09:00", manila), at))
    }

    @Test
    fun `runs tomorrow once today's time has passed or is now`() {
        assertEquals(time("2026-11-18T17:30", manila), DigestPlanner.nextRun(time("2026-11-17T20:00", manila), at))
        assertEquals(time("2026-11-18T17:30", manila), DigestPlanner.nextRun(time("2026-11-17T17:30", manila), at))
    }

    @Test
    fun `keeps local time across a daylight saving change`() {
        // Clocks go forward overnight on 8 March 2026 in New York, so that day is 23 hours long.
        val next = DigestPlanner.nextRun(time("2026-03-07T18:00", newYork), at)
        assertEquals(time("2026-03-08T17:30", newYork), next)
        assertEquals(Duration.ofHours(22).plusMinutes(30), Duration.between(time("2026-03-07T18:00", newYork), next))
    }

    @Test
    fun `follows the device into a new time zone`() {
        // The same instant is morning in Manila and the previous evening in New York.
        val instant = Instant.parse("2026-11-17T01:00:00Z")
        assertEquals(time("2026-11-17T17:30", manila), DigestPlanner.nextRun(instant.atZone(manila), at))
        assertEquals(time("2026-11-17T17:30", newYork), DigestPlanner.nextRun(instant.atZone(newYork), at))
        assertEquals("2026-11-16", DigestPlanner.windowKey(instant.atZone(newYork)))
    }

    @Test
    fun `at risk means expiring within 48 hours, or already expired`() {
        val now = Instant.parse("2026-11-17T10:00:00Z")
        assertTrue(DigestPlanner.isAtRisk(now.minus(Duration.ofDays(1)), now))
        assertTrue(DigestPlanner.isAtRisk(now.plus(Duration.ofHours(48)), now))
        assertFalse(DigestPlanner.isAtRisk(now.plus(Duration.ofHours(49)), now))
    }

    @Test
    fun `sends only when something is new since the last digest`() {
        assertTrue(DigestPlanner.shouldSend(setOf("item:1"), lastSent = null))
        assertFalse(DigestPlanner.shouldSend(setOf("item:1"), lastSent = setOf("item:1", "item:2")))
        assertTrue(DigestPlanner.shouldSend(setOf("item:1", "item:3"), lastSent = setOf("item:1")))
        assertFalse(DigestPlanner.shouldSend(emptySet(), lastSent = null))
    }
}
