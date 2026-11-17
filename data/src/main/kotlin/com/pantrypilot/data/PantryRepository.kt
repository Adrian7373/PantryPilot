package com.pantrypilot.data

import android.content.Context
import androidx.room.withTransaction
import com.pantrypilot.data.db.BarcodeDao
import com.pantrypilot.data.db.BarcodeProductEntity
import com.pantrypilot.data.db.Disposition
import com.pantrypilot.data.db.EventType
import com.pantrypilot.data.db.ItemEventEntity
import com.pantrypilot.data.db.PantryDao
import com.pantrypilot.data.db.PantryDatabase
import com.pantrypilot.data.db.PantryItemEntity
import com.pantrypilot.data.db.ReceiptAliasDao
import com.pantrypilot.data.db.ReceiptAliasEntity
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.data.db.ShelfLifeRuleEntity
import com.pantrypilot.data.remote.OpenFoodFactsClient
import com.pantrypilot.domain.decay.DecayEngine
import com.pantrypilot.domain.decay.ItemEvent
import com.pantrypilot.domain.decay.StorageZone
import com.pantrypilot.domain.insights.UsageKind
import com.pantrypilot.domain.match.RuleCandidate
import com.pantrypilot.domain.match.RuleMatcher
import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.receipt.OcrLine
import com.pantrypilot.domain.receipt.ReceiptLayout
import com.pantrypilot.domain.receipt.ReceiptNormalizer
import com.pantrypilot.domain.receipt.ReceiptParser
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.time.Clock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

@Singleton
class PantryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PantryDatabase,
    private val pantryDao: PantryDao,
    private val shelfLifeDao: ShelfLifeDao,
    private val barcodeDao: BarcodeDao,
    private val receiptAliasDao: ReceiptAliasDao,
    private val openFoodFacts: OpenFoodFactsClient,
    private val restock: RestockRepository,
    private val usage: UsageLog,
    private val clock: Clock,
) {
    fun observeActiveItems(): Flow<List<PantryItemEntity>> = pantryDao.observeActive()

    fun observeRules(): Flow<List<ShelfLifeRuleEntity>> = shelfLifeDao.observeAll()

    /** Loads the bundled shelf-life rules and the default staples on first launch. */
    suspend fun seedRulesIfEmpty() = withContext(Dispatchers.IO) {
        if (shelfLifeDao.count() > 0) return@withContext
        val json = context.assets.open(SEED_FILE).bufferedReader().use { it.readText() }
        val rules = Json.decodeFromString<List<ShelfLifeRuleEntity>>(json)
        db.withTransaction {
            shelfLifeDao.insertAll(rules)
            restock.seedDefaultStaples(rules)
        }
    }

    /** Resolves a barcode from what was learned on earlier scans, then from Open Food Facts. */
    suspend fun lookupBarcode(barcode: String): BarcodeLookup {
        if (!isValidBarcode(barcode)) return BarcodeLookup.NotFound
        barcodeDao.product(barcode)?.let { return BarcodeLookup.Found(it.name, it.categories, it.ruleId, it.size) }
        val remote = try {
            openFoodFacts.product(barcode)
        } catch (e: IOException) {
            return BarcodeLookup.Failed
        } ?: return BarcodeLookup.NotFound
        barcodeDao.save(BarcodeProductEntity(barcode, remote.name, remote.categories, size = remote.size))
        return BarcodeLookup.Found(remote.name, remote.categories, learnedRuleId = null, size = remote.size)
    }

    /**
     * Adds an item. [size] is one package, when known. With a [barcode], also remembers the name,
     * rule and size chosen for that product.
     */
    suspend fun addItem(
        name: String,
        rule: ShelfLifeRuleEntity,
        zone: StorageZone,
        priceCents: Long? = null,
        barcode: String? = null,
        size: Quantity? = null,
    ) {
        db.withTransaction {
            insertItem(name, rule, zone, quantity = 1, priceCents, size)
            if (barcode != null && isValidBarcode(barcode)) {
                val known = barcodeDao.product(barcode)
                barcodeDao.save(
                    BarcodeProductEntity(
                        barcode = barcode,
                        name = name,
                        categories = known?.categories.orEmpty(),
                        ruleId = rule.id,
                        size = size?.describe() ?: known?.size.orEmpty(),
                    ),
                )
            }
        }
    }

    /**
     * Reads the purchased items out of a receipt photo's text. Names the user confirmed on an
     * earlier receipt come back as confirmed; the rest are normalized and matched to a rule.
     */
    suspend fun draftReceipt(lines: List<OcrLine>): List<ReceiptDraft> {
        val parsed = ReceiptParser.parse(ReceiptLayout.rows(lines))
        if (parsed.isEmpty()) return emptyList()
        val candidates = shelfLifeDao.all().map { RuleCandidate(it.id, it.name, it.keywords) }
        val learned = receiptAliasDao.aliases(parsed.map { aliasKey(it.rawName) }).associateBy { it.rawName }
        return parsed.map { line ->
            val alias = learned[aliasKey(line.rawName)]
            val normalized = ReceiptNormalizer.normalize(line.rawName, candidates)
            ReceiptDraft(
                rawName = line.rawName,
                name = alias?.name ?: normalized.displayName,
                ruleId = alias?.ruleId ?: normalized.ruleId,
                quantity = line.quantity,
                priceCents = line.priceCents,
                size = line.size,
                learned = alias != null,
            )
        }
    }

    /** Adds the confirmed receipt items, each to its rule's usual zone, and remembers their names. */
    suspend fun addReceiptItems(items: List<ReceiptDraft>) {
        db.withTransaction {
            for (item in items) {
                val rule = item.ruleId?.let { shelfLifeDao.rule(it) } ?: continue
                insertItem(item.name, rule, rule.usualZone(), item.quantity, item.priceCents, item.size)
                receiptAliasDao.save(ReceiptAliasEntity(aliasKey(item.rawName), item.name, rule.id))
            }
        }
    }

    private suspend fun insertItem(
        name: String,
        rule: ShelfLifeRuleEntity,
        zone: StorageZone,
        quantity: Int,
        priceCents: Long?,
        size: Quantity?,
    ) {
        val now = clock.millis()
        val packageAmount = rule.packageAmount(size)
        val id = pantryDao.insertItem(
            PantryItemEntity(
                name = name,
                ruleId = rule.id,
                zone = zone,
                quantity = quantity,
                packageAmount = packageAmount,
                amountLeft = packageAmount * quantity,
                stockUnit = rule.stockUnit,
                priceCents = priceCents,
                purchasedAt = now,
                expiresAt = now,
            ),
        )
        pantryDao.insertEvent(ItemEventEntity(itemId = id, type = EventType.STORED, zone = zone, at = now))
        refresh(id)
        restock.onRestocked(rule.id)
    }

    private fun aliasKey(rawName: String): String = rawName.trim().uppercase()

    suspend fun markOpened(itemId: Long) = record(itemId, EventType.OPENED, zone = null)

    suspend fun moveTo(itemId: Long, zone: StorageZone) {
        db.withTransaction {
            val item = pantryDao.item(itemId) ?: return@withTransaction
            if (zone == StorageZone.FREEZER && item.zone != StorageZone.FREEZER) usage.recordFreezeIfRescue(item)
            record(itemId, EventType.STORED, zone)
        }
    }

    /** Marks an item used or tossed. Returns true when that left a staple low and put it on the shopping list. */
    suspend fun setDisposition(itemId: Long, disposition: Disposition): Boolean {
        val item = pantryDao.item(itemId) ?: return false
        db.withTransaction {
            when (disposition) {
                Disposition.CONSUMED -> usage.record(item, UsageKind.USED, item.amountLeft)
                Disposition.TOSSED -> usage.record(item, UsageKind.TOSSED, item.amountLeft)
                Disposition.ACTIVE -> Unit
            }
            pantryDao.updateItem(item.copy(disposition = disposition, amountLeft = 0.0))
        }
        return restock.checkLow(item.ruleId)
    }

    private suspend fun record(itemId: Long, type: EventType, zone: StorageZone?) {
        db.withTransaction {
            pantryDao.insertEvent(ItemEventEntity(itemId = itemId, type = type, zone = zone, at = clock.millis()))
            refresh(itemId)
        }
    }

    /** Recomputes the cached zone, opened flag and expiry from the item's event history. */
    private suspend fun refresh(itemId: Long) {
        val item = pantryDao.item(itemId) ?: return
        val rule = shelfLifeDao.rule(item.ruleId) ?: return
        val events = pantryDao.events(itemId)
        val history = events.map { event ->
            val at = Instant.ofEpochMilli(event.at)
            when (event.type) {
                EventType.STORED -> ItemEvent.Stored(at, checkNotNull(event.zone))
                EventType.OPENED -> ItemEvent.Opened(at)
            }
        }
        val freshness = DecayEngine.evaluate(rule.toRule(), history, clock.instant())
        pantryDao.updateItem(
            item.copy(
                zone = events.last { it.type == EventType.STORED }.zone ?: item.zone,
                opened = events.any { it.type == EventType.OPENED },
                expiresAt = freshness.expiresAt.toEpochMilli(),
            ),
        )
    }

    companion object {
        private const val SEED_FILE = "shelf_life_rules.json"

        /** EAN-8, UPC-A, EAN-13 and GTIN-14 are all 8 to 14 digits. */
        fun isValidBarcode(barcode: String): Boolean = barcode.length in 8..14 && barcode.all(Char::isDigit)
    }
}

/** One receipt line awaiting the user's confirmation. [learned] means it was confirmed on an earlier receipt. */
data class ReceiptDraft(
    val rawName: String,
    val name: String,
    val ruleId: String?,
    val quantity: Int,
    val priceCents: Long,
    val size: Quantity?,
    val learned: Boolean,
)

sealed interface BarcodeLookup {
    /**
     * [learnedRuleId] is the rule the user confirmed for this product on an earlier scan, if any.
     * [size] is the package size as the label states it, or empty.
     */
    data class Found(val name: String, val categories: String, val learnedRuleId: String?, val size: String) : BarcodeLookup

    data object NotFound : BarcodeLookup

    /** The lookup could not be completed, usually because the device is offline. */
    data object Failed : BarcodeLookup
}

/**
 * Suggests a shelf-life rule for a product. The name is tried first; category words are a fallback
 * because they are broad ("plant based foods") and would otherwise outvote the name.
 */
fun List<ShelfLifeRuleEntity>.suggestFor(name: String, categories: String = ""): ShelfLifeRuleEntity? {
    val candidates = map { RuleCandidate(it.id, it.name, it.keywords) }
    val id = RuleMatcher.bestMatch(name, candidates) ?: RuleMatcher.bestMatch(categories, candidates)
    return firstOrNull { it.id == id }
}
