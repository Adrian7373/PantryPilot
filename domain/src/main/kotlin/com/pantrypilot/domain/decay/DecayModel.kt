package com.pantrypilot.domain.decay

import java.time.Duration
import java.time.Instant

enum class StorageZone { PANTRY, FRIDGE, FREEZER }

/** How long a food keeps in each zone. A null zone means there is no guidance for it. */
data class ShelfLife(
    val pantry: Duration? = null,
    val fridge: Duration? = null,
    val freezer: Duration? = null,
) {
    fun inZone(zone: StorageZone): Duration = when (zone) {
        // No pantry guidance means the food is not safe at room temperature.
        StorageZone.PANTRY -> pantry ?: ROOM_TEMPERATURE_LIMIT
        // A fridge keeps shelf-stable food at least as long as a pantry does.
        StorageZone.FRIDGE -> fridge ?: pantry ?: ROOM_TEMPERATURE_LIMIT
        StorageZone.FREEZER -> freezer ?: listOfNotNull(fridge, pantry).maxOrNull() ?: ROOM_TEMPERATURE_LIMIT
    }

    companion object {
        val ROOM_TEMPERATURE_LIMIT: Duration = Duration.ofHours(2)
    }
}

/** [opened] is null when opening the package does not change how long the food keeps. */
data class ShelfLifeRule(
    val unopened: ShelfLife,
    val opened: ShelfLife? = null,
)

sealed interface ItemEvent {
    val at: Instant

    /** The item was put in [zone]: the purchase itself, or a later move (freeze, thaw). */
    data class Stored(override val at: Instant, val zone: StorageZone) : ItemEvent

    data class Opened(override val at: Instant) : ItemEvent
}

enum class FreshnessStatus { FRESH, USE_SOON, EXPIRED }

data class Freshness(val expiresAt: Instant, val status: FreshnessStatus)
