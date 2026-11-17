package com.pantrypilot.domain.insights

import com.pantrypilot.domain.insights.UsageKind.FROZEN
import com.pantrypilot.domain.insights.UsageKind.TOSSED
import com.pantrypilot.domain.insights.UsageKind.USED
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WasteLedgerTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val november = YearMonth.of(2026, 11)

    private fun entry(
        at: String,
        kind: UsageKind,
        cents: Double,
        ruleId: String = "milk",
        name: String = "Milk",
        rescued: Boolean = false,
        estimated: Boolean = false,
    ) = UsageEntry(Instant.parse(at), ruleId, name, kind, cents, rescued, estimated)

    @Test
    fun `totals what was eaten, rescued and thrown away`() {
        val entries = listOf(
            entry("2026-11-03T10:00:00Z", USED, 300.0),
            entry("2026-11-05T10:00:00Z", USED, 875.0, "chicken_raw", "Chicken", rescued = true),
            entry("2026-11-06T10:00:00Z", FROZEN, 620.0, "ground_beef", "Ground beef", rescued = true),
            entry("2026-11-08T10:00:00Z", TOSSED, 350.0, "spinach_bagged", "Spinach", rescued = false),
        )
        val summary = WasteLedger.summarize(entries, november, manila)

        assertEquals(1175.0, summary.usedCents, 1e-9)
        assertEquals(1495.0, summary.rescuedCents, 1e-9)
        assertEquals(350.0, summary.wastedCents, 1e-9)
        assertEquals(350.0 / 1525.0, summary.wasteShare!!, 1e-9)
        assertFalse(summary.estimated)
    }

    @Test
    fun `months follow the user's time zone`() {
        // 31 October 20:00 UTC is already 1 November in Manila.
        val entries = listOf(entry("2026-10-31T20:00:00Z", USED, 100.0))
        assertEquals(100.0, WasteLedger.summarize(entries, november, manila).usedCents, 1e-9)
        assertEquals(0.0, WasteLedger.summarize(entries, november, ZoneId.of("UTC")).usedCents, 1e-9)
    }

    @Test
    fun `ranks the foods most wasted by value`() {
        val entries = listOf(
            entry("2026-11-02T10:00:00Z", TOSSED, 100.0, "bread", "Bread"),
            entry("2026-11-09T10:00:00Z", TOSSED, 150.0, "bread", "Bread"),
            entry("2026-11-04T10:00:00Z", TOSSED, 900.0, "fish_fresh", "Salmon"),
            entry("2026-11-05T10:00:00Z", TOSSED, 50.0, "bananas", "Bananas"),
            entry("2026-11-06T10:00:00Z", TOSSED, 10.0, "garlic", "Garlic"),
        )
        val top = WasteLedger.summarize(entries, november, manila).topWasted

        assertEquals(listOf("Salmon", "Bread", "Bananas"), top.map { it.name })
        assertEquals(250.0, top[1].cents, 1e-9)
    }

    @Test
    fun `flags months that used typical prices`() {
        val entries = listOf(entry("2026-11-03T10:00:00Z", USED, 300.0, estimated = true))
        assertTrue(WasteLedger.summarize(entries, november, manila).estimated)
    }

    @Test
    fun `an empty month has no waste share`() {
        val summary = WasteLedger.summarize(emptyList(), november, manila)
        assertTrue(summary.isEmpty)
        assertNull(summary.wasteShare)
    }

    @Test
    fun `history covers the months ending with the chosen one, oldest first`() {
        val history = WasteLedger.history(emptyList(), november, manila, count = 6)
        assertEquals(YearMonth.of(2026, 6), history.first().month)
        assertEquals(november, history.last().month)
        assertEquals(6, history.size)
    }
}
