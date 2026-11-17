package com.pantrypilot.domain.depletion

import java.time.Duration
import java.time.Instant

/** A purchase of one food: when, and how much in its stock unit. */
data class Purchase(val at: Instant, val amount: Double)

/**
 * Estimates how fast a staple is used from how often it is bought, so nobody has to log every
 * splash of milk. If a litre is bought every week, about a litre a week is being used.
 *
 * The estimate is deliberately rough. It only decides when to ask a one-tap question
 * ("still have milk?"), and the answer replaces the guess.
 */
object DepletionEstimator {
    private val DAY_MS = Duration.ofDays(1).toMillis().toDouble()

    /**
     * Amount used per day: everything bought before the latest purchase, spread over the time
     * from the first purchase to the latest. Null until there are two purchases a day or more apart.
     */
    fun dailyRate(purchases: List<Purchase>): Double? {
        val sorted = purchases.sortedBy { it.at }
        if (sorted.size < 2) return null
        val days = days(sorted.first().at, sorted.last().at)
        if (days < 1.0) return null
        return sorted.dropLast(1).sumOf { it.amount } / days
    }

    /**
     * The recorded amount minus the estimated use since [since], which is the latest purchase or
     * the user's latest answer. Use already logged by cooking is also subtracted from the
     * recorded amount, so this errs towards asking early, which costs one tap.
     */
    fun estimateLeft(recorded: Double, dailyRate: Double, since: Instant, now: Instant): Double =
        (recorded - dailyRate * days(since, now).coerceAtLeast(0.0)).coerceAtLeast(0.0)

    /** Ask when the records say there is enough but the estimate says it has probably run low. */
    fun needsCheck(recorded: Double, estimated: Double, par: Double): Boolean = recorded >= par && estimated < par

    private fun days(from: Instant, to: Instant): Double = Duration.between(from, to).toMillis() / DAY_MS
}
