package com.pantrypilot.domain.decay

import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Computes when an item expires by replaying its event history.
 *
 * Outside the freezer an item has a fraction of its shelf life left. Moving between pantry and
 * fridge keeps that fraction and applies it to the new zone's shelf life. Opening converts the
 * time left into the opened shelf life, so it can shorten the countdown but never extend it.
 *
 * The freezer pauses that fraction. Each stay in the freezer has its own clock: the item is
 * good until the freezer shelf life runs out, and thawing in time resumes the paused countdown.
 */
object DecayEngine {
    val DEFAULT_USE_SOON_WINDOW: Duration = Duration.ofHours(48)

    fun evaluate(
        rule: ShelfLifeRule,
        events: List<ItemEvent>,
        now: Instant,
        useSoonWindow: Duration = DEFAULT_USE_SOON_WINDOW,
    ): Freshness {
        val history = events.filter { it.at <= now }.sortedBy { it.at }
        val first = history.firstOrNull()
        require(first is ItemEvent.Stored) { "An item's history must start with a Stored event" }

        var zone = first.zone
        var opened = false
        var chilledLeft = 1.0 // fraction of shelf life left outside the freezer
        var frozenLeft = 1.0 // fraction of the current freezer stay left
        var cursor = first.at
        var expiredAt: Instant? = null

        fun lifeMillis(inZone: StorageZone, isOpened: Boolean): Double {
            val shelfLife = if (isOpened) rule.opened ?: rule.unopened else rule.unopened
            return shelfLife.inZone(inZone).toMillis().toDouble()
        }

        fun timeLeftMillis(): Double =
            (if (zone == StorageZone.FREEZER) frozenLeft else chilledLeft) * lifeMillis(zone, opened)

        fun advanceTo(time: Instant) {
            if (expiredAt == null) {
                val elapsed = Duration.between(cursor, time).toMillis().toDouble()
                val left = timeLeftMillis()
                if (elapsed >= left) {
                    expiredAt = cursor.plusMillis(left.roundToLong())
                } else {
                    val used = elapsed / lifeMillis(zone, opened)
                    if (zone == StorageZone.FREEZER) frozenLeft -= used else chilledLeft -= used
                }
            }
            cursor = time
        }

        for (event in history.drop(1)) {
            advanceTo(event.at)
            when (event) {
                is ItemEvent.Stored -> if (event.zone != zone) {
                    if (event.zone == StorageZone.FREEZER) frozenLeft = 1.0
                    zone = event.zone
                }

                is ItemEvent.Opened -> if (!opened) {
                    val reference = if (zone == StorageZone.FREEZER) StorageZone.FRIDGE else zone
                    val leftUnopened = chilledLeft * lifeMillis(reference, false)
                    chilledLeft = min(1.0, leftUnopened / lifeMillis(reference, true))
                    opened = true
                }
            }
        }

        val expiresAt = expiredAt ?: cursor.plusMillis(timeLeftMillis().roundToLong())
        val status = when {
            expiresAt <= now -> FreshnessStatus.EXPIRED
            expiresAt <= now.plus(useSoonWindow) -> FreshnessStatus.USE_SOON
            else -> FreshnessStatus.FRESH
        }
        return Freshness(expiresAt, status)
    }
}
