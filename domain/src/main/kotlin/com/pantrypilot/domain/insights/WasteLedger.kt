package com.pantrypilot.domain.insights

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

enum class UsageKind {
    /** Eaten: cooked, or marked used. */
    USED,

    /** Thrown away. */
    TOSSED,

    /** Moved to the freezer while it was about to expire. Only rescues are logged. */
    FROZEN,
}

/**
 * Something that happened to food, valued in cents. [rescued] means it happened while the food
 * was in its last two days. [estimated] means the value came from a typical price, not a receipt.
 */
data class UsageEntry(
    val at: Instant,
    val ruleId: String,
    val name: String,
    val kind: UsageKind,
    val cents: Double,
    val rescued: Boolean,
    val estimated: Boolean,
)

data class WastedFood(val name: String, val cents: Double)

data class MonthSummary(
    val month: YearMonth,
    /** Value of food eaten. */
    val usedCents: Double,
    /** Value of food eaten or frozen in its last two days: what would otherwise have been thrown away. */
    val rescuedCents: Double,
    /** Value of food thrown away. */
    val wastedCents: Double,
    /** True when any value came from a typical price. */
    val estimated: Boolean,
    val topWasted: List<WastedFood>,
) {
    /** Share of food bought that was thrown away, or null when nothing was used or tossed. */
    val wasteShare: Double?
        get() = (usedCents + wastedCents).takeIf { it > 0.0 }?.let { wastedCents / it }

    val isEmpty: Boolean get() = usedCents == 0.0 && rescuedCents == 0.0 && wastedCents == 0.0
}

/** Turns the usage log into monthly totals, by calendar month in the user's time zone. */
object WasteLedger {
    private const val TOP_WASTED = 3

    fun summarize(entries: List<UsageEntry>, month: YearMonth, zone: ZoneId): MonthSummary {
        val inMonth = entries.filter { YearMonth.from(it.at.atZone(zone)) == month }
        val tossed = inMonth.filter { it.kind == UsageKind.TOSSED }
        return MonthSummary(
            month = month,
            usedCents = inMonth.filter { it.kind == UsageKind.USED }.sumOf { it.cents },
            rescuedCents = inMonth.filter { it.rescued && it.kind != UsageKind.TOSSED }.sumOf { it.cents },
            wastedCents = tossed.sumOf { it.cents },
            estimated = inMonth.any { it.estimated },
            topWasted = tossed
                .groupBy { it.ruleId }
                .map { (_, group) -> WastedFood(group.maxBy { it.at }.name, group.sumOf { it.cents }) }
                .sortedByDescending { it.cents }
                .take(TOP_WASTED),
        )
    }

    /** The [count] months ending with [last], oldest first, for the trend chart. */
    fun history(entries: List<UsageEntry>, last: YearMonth, zone: ZoneId, count: Int = 6): List<MonthSummary> =
        (count - 1 downTo 0).map { summarize(entries, last.minusMonths(it.toLong()), zone) }
}
