package com.pantrypilot.app

import com.pantrypilot.domain.decay.StorageZone
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

fun StorageZone.label(): String = when (this) {
    StorageZone.PANTRY -> "Pantry"
    StorageZone.FRIDGE -> "Fridge"
    StorageZone.FREEZER -> "Freezer"
}

/** "Chicken" for "Chicken (raw)". */
fun String.shortFoodName(): String = substringBefore(" (")

/** An amount in a stock unit, as people say it: "250 g", "1.5 l", "3". */
fun formatAmount(amount: Double, stockUnit: String): String = when (stockUnit) {
    "g" -> if (amount >= 1000) "${number(amount / 1000)} kg" else "${amount.roundToLong()} g"
    "ml" -> if (amount >= 1000) "${number(amount / 1000)} l" else "${amount.roundToLong()} ml"
    else -> number(amount)
}

/** How long until a date, for tight spaces: "expired", "today", "tomorrow", "5 days". */
fun expiryShort(expiresAt: Long, now: Long = System.currentTimeMillis()): String {
    val left = expiresAt - now
    val day = TimeUnit.DAYS.toMillis(1)
    return when {
        left <= 0 -> "expired"
        left < day -> "today"
        left < 2 * day -> "tomorrow"
        else -> "${(left + day - 1) / day} days"
    }
}

private fun number(value: Double): String {
    val rounded = value.roundToLong()
    return if (abs(value - rounded) < 0.05) rounded.toString() else String.format(Locale.getDefault(), "%.1f", value)
}
