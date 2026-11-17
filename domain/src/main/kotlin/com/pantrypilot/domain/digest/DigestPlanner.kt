package com.pantrypilot.domain.digest

import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Decides when the "use soon" digest runs and whether it has anything new to say.
 *
 * One evaluation a day, at a fixed local time, replaces polling the database. Times are worked
 * out in the device's zone, so the digest follows the user across time zones and daylight saving.
 */
object DigestPlanner {
    /** Food expiring within this window is at risk. */
    val WINDOW: Duration = Duration.ofHours(48)

    /** Early evening, while "cook it tonight" is still an option. */
    val DEFAULT_TIME: LocalTime = LocalTime.of(17, 30)

    /** The next time the digest should run strictly after [now], at [at] local time. */
    fun nextRun(now: ZonedDateTime, at: LocalTime = DEFAULT_TIME): ZonedDateTime {
        val today = now.toLocalDate().atTime(at).atZone(now.zone)
        return if (today.isAfter(now)) today else now.toLocalDate().plusDays(1).atTime(at).atZone(now.zone)
    }

    /** One digest per local calendar day. A retried run on the same day finds this key and does nothing. */
    fun windowKey(now: ZonedDateTime): String = now.toLocalDate().toString()

    /** Whether something expires before the window ends, including food that has already expired. */
    fun isAtRisk(expiresAt: Instant, now: Instant): Boolean = !expiresAt.isAfter(now.plus(WINDOW))

    /**
     * Sends only when there is something the last digest did not mention, so the same items do
     * not trigger a notification every day.
     */
    fun shouldSend(current: Set<String>, lastSent: Set<String>?): Boolean =
        current.isNotEmpty() && (lastSent == null || !lastSent.containsAll(current))
}
