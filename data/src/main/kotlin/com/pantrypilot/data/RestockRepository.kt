package com.pantrypilot.data

import androidx.room.withTransaction
import com.pantrypilot.data.db.Disposition
import com.pantrypilot.data.db.PantryDao
import com.pantrypilot.data.db.PantryDatabase
import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.data.db.RestockDao
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.data.db.ShoppingItemEntity
import com.pantrypilot.data.db.ShoppingReason
import com.pantrypilot.data.db.StapleEntity
import com.pantrypilot.domain.depletion.DepletionEstimator
import com.pantrypilot.domain.depletion.Purchase
import com.pantrypilot.domain.insights.UsageKind
import java.time.Clock
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * A staple and how much of it is on hand, not counting expired food. Amounts are in the rule's
 * stock unit. [tracked] is false until it has been bought at least once.
 */
data class StapleStatus(val rule: ShelfLifeRuleEntity, val parAmount: Double, val onHand: Double, val tracked: Boolean) {
    val low: Boolean get() = tracked && onHand < parAmount
}

/** A staple that has probably run low going by how fast it is usually used, though the records say otherwise. */
data class StapleCheck(val rule: ShelfLifeRuleEntity, val recorded: Double, val estimated: Double, val dailyRate: Double)

enum class CheckAnswer { PLENTY, LOW, OUT }

@Singleton
class RestockRepository @Inject constructor(
    private val db: PantryDatabase,
    private val restockDao: RestockDao,
    private val pantryDao: PantryDao,
    private val shelfLifeDao: ShelfLifeDao,
    private val usage: UsageLog,
    private val clock: Clock,
) {
    fun observeShoppingList(): Flow<List<ShoppingItemEntity>> = restockDao.observeShopping()

    fun observeStaples(): Flow<List<StapleStatus>> =
        combine(restockDao.observeStaples(), pantryDao.observeAll(), shelfLifeDao.observeAll()) { staples, items, rules ->
            val now = clock.millis()
            val rulesById = rules.associateBy { it.id }
            staples.mapNotNull { staple ->
                val rule = rulesById[staple.ruleId] ?: return@mapNotNull null
                val history = items.filter { it.ruleId == staple.ruleId }
                StapleStatus(rule, staple.parAmount, onHand(history, now), tracked = history.isNotEmpty())
            }.sortedBy { it.rule.name }
        }

    fun observeChecks(): Flow<List<StapleCheck>> =
        combine(
            restockDao.observeStaples(),
            pantryDao.observeAll(),
            shelfLifeDao.observeAll(),
            restockDao.observeShopping(),
        ) { staples, items, rules, shopping ->
            checks(staples, items, rules, shopping.map { it.id }.toSet(), clock.millis())
        }

    suspend fun pendingChecks(): List<StapleCheck> =
        checks(restockDao.allStaples(), pantryDao.allItems(), shelfLifeDao.all(), restockDao.shoppingIds().toSet(), clock.millis())

    /** Records the answer to "still have it?". It also resets the estimate, since the user has just looked. */
    suspend fun answerCheck(ruleId: String, answer: CheckAnswer) {
        db.withTransaction {
            restockDao.setCheckedAt(ruleId, clock.millis())
            when (answer) {
                CheckAnswer.PLENTY -> Unit
                CheckAnswer.LOW -> listStaple(ruleId, "Running low")
                CheckAnswer.OUT -> {
                    for (item in pantryDao.activeFor(ruleId)) {
                        usage.record(item, UsageKind.USED, item.amountLeft)
                        pantryDao.updateItem(item.copy(amountLeft = 0.0, disposition = Disposition.CONSUMED))
                    }
                    listStaple(ruleId, "Ran out")
                }
            }
        }
    }

    /** Keeps [rule] in stock from now on, and lists it straight away if it is already low. */
    suspend fun addStaple(rule: ShelfLifeRuleEntity) {
        restockDao.saveStaples(listOf(StapleEntity(rule.id, rule.packageSize * PAR_FRACTION)))
        checkLow(rule.id)
    }

    suspend fun removeStaple(ruleId: String) = restockDao.deleteStaple(ruleId)

    /** Called once, when the rules are first loaded. */
    suspend fun seedDefaultStaples(rules: List<ShelfLifeRuleEntity>) {
        restockDao.saveStaples(
            rules.filter { it.id in DEFAULT_STAPLES }.map { StapleEntity(it.id, it.packageSize * PAR_FRACTION) },
        )
    }

    /**
     * Puts a staple on the shopping list when less than its par is left. Called after cooking or
     * using something up. Returns true only when this call added it, so callers can say so.
     */
    suspend fun checkLow(ruleId: String): Boolean {
        restockDao.staple(ruleId) ?: return false
        return if (isLow(ruleId)) listStaple(ruleId, "Running low") else false
    }

    /**
     * Daily sweep, for staples that ran low without an event, such as the last carton expiring.
     * Skips staples never bought (nobody wants ten items listed on day one) and staples the user
     * answered for in the past week. Returns the names added.
     */
    suspend fun checkAllStaples(): List<String> {
        val now = clock.millis()
        val items = pantryDao.allItems()
        val added = mutableListOf<String>()
        for (staple in restockDao.allStaples()) {
            if (items.none { it.ruleId == staple.ruleId }) continue
            val checkedAt = staple.checkedAt
            if (checkedAt != null && now - checkedAt < QUIET_AFTER_ANSWER.toMillis()) continue
            if (isLow(staple.ruleId) && listStaple(staple.ruleId, "Running low")) {
                shelfLifeDao.rule(staple.ruleId)?.let { added += it.name }
            }
        }
        return added
    }

    /** Returns false when the food was already on the list. */
    suspend fun addToList(name: String, ruleId: String?, reason: ShoppingReason, note: String? = null): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val item = ShoppingItemEntity(
            id = ruleId ?: "name:${trimmed.lowercase()}",
            name = trimmed,
            ruleId = ruleId,
            reason = reason,
            note = note,
            addedAt = clock.millis(),
        )
        return restockDao.addShopping(item) != -1L
    }

    /** Ticking a staple off the list counts as an answer, so the daily sweep leaves it alone for a while. */
    suspend fun removeFromList(id: String) {
        db.withTransaction {
            restockDao.deleteShopping(id)
            if (restockDao.staple(id) != null) restockDao.setCheckedAt(id, clock.millis())
        }
    }

    /** Buying a food takes it off the list. */
    suspend fun onRestocked(ruleId: String) = restockDao.deleteShopping(ruleId)

    private suspend fun isLow(ruleId: String): Boolean {
        val staple = restockDao.staple(ruleId) ?: return false
        return onHand(pantryDao.activeFor(ruleId), clock.millis()) < staple.parAmount
    }

    private suspend fun listStaple(ruleId: String, note: String): Boolean {
        val rule = shelfLifeDao.rule(ruleId) ?: return false
        return addToList(rule.name, ruleId, ShoppingReason.LOW_STOCK, note)
    }

    private fun onHand(items: List<PantryItemEntity>, now: Long): Double =
        items.filter { it.disposition == Disposition.ACTIVE && it.expiresAt > now }.sumOf { it.amountLeft }

    private fun checks(
        staples: List<StapleEntity>,
        items: List<PantryItemEntity>,
        rules: List<ShelfLifeRuleEntity>,
        listed: Set<String>,
        now: Long,
    ): List<StapleCheck> {
        val rulesById = rules.associateBy { it.id }
        return staples.mapNotNull { staple ->
            if (staple.ruleId in listed) return@mapNotNull null
            val rule = rulesById[staple.ruleId] ?: return@mapNotNull null
            val history = items.filter { it.ruleId == staple.ruleId }
            val rate = DepletionEstimator.dailyRate(
                history.map { Purchase(Instant.ofEpochMilli(it.purchasedAt), it.packageAmount * it.quantity) },
            ) ?: return@mapNotNull null
            val active = history.filter { it.disposition == Disposition.ACTIVE && it.expiresAt > now }
            if (active.isEmpty()) return@mapNotNull null
            val recorded = active.sumOf { it.amountLeft }
            val since = maxOf(active.maxOf { it.purchasedAt }, staple.checkedAt ?: 0L)
            val estimated = DepletionEstimator.estimateLeft(recorded, rate, Instant.ofEpochMilli(since), Instant.ofEpochMilli(now))
            if (DepletionEstimator.needsCheck(recorded, estimated, staple.parAmount)) {
                StapleCheck(rule, recorded, estimated, rate)
            } else {
                null
            }
        }.sortedBy { it.rule.name }
    }

    companion object {
        /** A staple is low when less than this share of a typical package is left. */
        const val PAR_FRACTION = 0.25

        private val QUIET_AFTER_ANSWER: Duration = Duration.ofDays(7)

        private val DEFAULT_STAPLES = setOf(
            "milk", "eggs", "butter", "bread", "rice_white", "pasta_dry", "flour", "cooking_oil", "onions", "garlic",
        )
    }
}
