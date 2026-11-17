package com.pantrypilot.data

import androidx.room.withTransaction
import com.pantrypilot.data.db.DigestDao
import com.pantrypilot.data.db.DigestLogEntity
import com.pantrypilot.data.db.PantryDao
import com.pantrypilot.data.db.PantryDatabase
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.domain.decay.StorageZone
import com.pantrypilot.domain.digest.DigestPlanner
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** [canFreeze] is true when the food keeps in the freezer and is not frozen or expired yet. */
data class DigestItem(val id: Long, val name: String, val expiresAt: Long, val canFreeze: Boolean)

data class Digest(val items: List<DigestItem>, val checks: List<StapleCheck>)

@Singleton
class DigestRepository @Inject constructor(
    private val db: PantryDatabase,
    private val pantryDao: PantryDao,
    private val shelfLifeDao: ShelfLifeDao,
    private val digestDao: DigestDao,
    private val restock: RestockRepository,
) {
    /**
     * Prepares today's digest, at most once per local day: a retried or duplicate run finds the
     * day already logged and returns null. Also returns null when nothing is new since the last
     * digest that was sent.
     */
    suspend fun prepare(now: ZonedDateTime): Digest? = db.withTransaction {
        val key = DigestPlanner.windowKey(now)
        if (digestDao.log(key) != null) return@withTransaction null

        val items = atRisk(now.toInstant())
        val checks = restock.pendingChecks()
        val current = items.map { "item:${it.id}" }.toSet() + checks.map { "check:${it.rule.id}" }
        val lastSent = digestDao.lastSent()?.keys?.split(',')?.filter(String::isNotEmpty)?.toSet()
        val send = DigestPlanner.shouldSend(current, lastSent)

        digestDao.insert(DigestLogEntity(key, current.joinToString(","), send, now.toInstant().toEpochMilli()))
        if (send) Digest(items, checks) else null
    }

    private suspend fun atRisk(now: Instant): List<DigestItem> {
        val rules = shelfLifeDao.all().associateBy { it.id }
        return pantryDao.activeItems()
            .filter { DigestPlanner.isAtRisk(Instant.ofEpochMilli(it.expiresAt), now) }
            .sortedBy { it.expiresAt }
            .map { item ->
                val freezable = rules[item.ruleId]?.freezerDays != null &&
                    item.zone != StorageZone.FREEZER &&
                    item.expiresAt > now.toEpochMilli()
                DigestItem(item.id, item.name, item.expiresAt, freezable)
            }
    }
}
