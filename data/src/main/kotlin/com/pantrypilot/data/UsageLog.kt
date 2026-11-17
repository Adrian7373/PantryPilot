package com.pantrypilot.data

import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.data.db.UsageDao
import com.pantrypilot.data.db.UsageEntity
import com.pantrypilot.domain.digest.DigestPlanner
import com.pantrypilot.domain.insights.UsageEntry
import com.pantrypilot.domain.insights.UsageKind
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Records what happened to food and what it was worth, for the Insights dashboard. Values come
 * from the receipt price when known, otherwise from the rule's typical price.
 */
@Singleton
class UsageLog @Inject constructor(
    private val usageDao: UsageDao,
    private val shelfLifeDao: ShelfLifeDao,
    private val clock: Clock,
) {
    fun observeEntries(): Flow<List<UsageEntry>> = usageDao.observeAll().map { rows -> rows.map { it.toEntry() } }

    /** [amount] is in the item's stock unit. Pass the item as it was before the change. */
    suspend fun record(item: PantryItemEntity, kind: UsageKind, amount: Double) {
        if (amount <= 0.0) return
        val now = clock.millis()
        val total = item.packageAmount * item.quantity
        val packageCents = item.priceCents?.toDouble()
            ?: ((shelfLifeDao.rule(item.ruleId)?.typicalPriceCents ?: 0L) * item.quantity).toDouble()
        usageDao.insert(
            UsageEntity(
                itemId = item.id,
                ruleId = item.ruleId,
                name = item.name,
                kind = kind,
                amount = amount,
                cents = if (total > 0.0) packageCents * amount.coerceAtMost(total) / total else 0.0,
                rescued = isInLastDays(item, now),
                estimated = item.priceCents == null,
                at = now,
            ),
        )
    }

    /** Freezing counts only as a rescue: moving food that keeps for weeks is just storage. */
    suspend fun recordFreezeIfRescue(item: PantryItemEntity) {
        if (isInLastDays(item, clock.millis())) record(item, UsageKind.FROZEN, item.amountLeft)
    }

    private fun isInLastDays(item: PantryItemEntity, now: Long): Boolean {
        val left = item.expiresAt - now
        return left > 0 && left <= DigestPlanner.WINDOW.toMillis()
    }
}
