package com.pantrypilot.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.pantrypilot.domain.decay.ShelfLife
import com.pantrypilot.domain.decay.ShelfLifeRule
import com.pantrypilot.domain.decay.StorageZone
import com.pantrypilot.domain.insights.UsageEntry
import com.pantrypilot.domain.insights.UsageKind
import com.pantrypilot.domain.quantity.Density
import com.pantrypilot.domain.quantity.MeasureUnit
import com.pantrypilot.domain.quantity.Quantity
import com.pantrypilot.domain.quantity.UnitConverter
import com.pantrypilot.domain.recipe.Ingredient
import java.time.Duration
import kotlinx.serialization.Serializable

/** Shelf life in days per zone, unopened and opened. Also the shape of the seed JSON. */
@Serializable
@Entity(tableName = "shelf_life_rules")
data class ShelfLifeRuleEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,
    /** Extra space-separated words that identify this food, used to suggest a rule for a product. */
    val keywords: String = "",
    /** The unit this food is counted in: "g", "ml" or "each". */
    val stockUnit: String,
    /** A typical package, in [stockUnit]. Used when no size is printed on the label or receipt. */
    val packageSize: Double,
    /** A rough package price, used to weigh recipes when the real price is not known. */
    val typicalPriceCents: Long,
    val gramsPerMl: Double? = null,
    val gramsPerEach: Double? = null,
    val pantryDays: Int? = null,
    val fridgeDays: Int? = null,
    val freezerDays: Int? = null,
    val openedPantryDays: Int? = null,
    val openedFridgeDays: Int? = null,
    val openedFreezerDays: Int? = null,
) {
    fun toRule(): ShelfLifeRule {
        val hasOpenedRule = openedPantryDays != null || openedFridgeDays != null || openedFreezerDays != null
        return ShelfLifeRule(
            unopened = ShelfLife(pantryDays.days(), fridgeDays.days(), freezerDays.days()),
            opened = if (hasOpenedRule) {
                ShelfLife(openedPantryDays.days(), openedFridgeDays.days(), openedFreezerDays.days())
            } else {
                null
            },
        )
    }

    fun unit(): MeasureUnit = MeasureUnit.fromSymbol(stockUnit) ?: MeasureUnit.GRAM

    fun toIngredient(): Ingredient = Ingredient(id, unit(), Density(gramsPerMl, gramsPerEach))

    /** One package in [stockUnit]: the printed [size] when it converts, else the typical package. */
    fun packageAmount(size: Quantity?): Double =
        size?.let { UnitConverter.convert(it, unit(), Density(gramsPerMl, gramsPerEach)) }
            ?.takeIf { it > 0.0 }
            ?: packageSize

    /** Where this food is normally kept before it is opened. */
    fun usualZone(): StorageZone = when {
        pantryDays != null -> StorageZone.PANTRY
        fridgeDays != null -> StorageZone.FRIDGE
        else -> StorageZone.FREEZER
    }

    private fun Int?.days(): Duration? = this?.let { Duration.ofDays(it.toLong()) }
}

enum class Disposition { ACTIVE, CONSUMED, TOSSED }

/**
 * [zone], [opened] and [expiresAt] are caches derived from the item's events. Times are epoch millis.
 * Amounts are in [stockUnit], copied from the item's rule when it was added.
 */
@Entity(
    tableName = "pantry_items",
    indices = [Index("disposition", "expiresAt")],
)
data class PantryItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val ruleId: String,
    val zone: StorageZone,
    val opened: Boolean = false,
    /** Packages bought. */
    val quantity: Int = 1,
    /** One package's amount. */
    val packageAmount: Double,
    /** What is left across all the packages. */
    val amountLeft: Double,
    val stockUnit: String,
    /** What all the packages cost together. */
    val priceCents: Long? = null,
    val purchasedAt: Long,
    val expiresAt: Long,
    val disposition: Disposition = Disposition.ACTIVE,
)

/**
 * What a barcode resolved to. [ruleId] is set once the user has confirmed a shelf-life rule for it,
 * so the next scan of the same product needs no network and no choosing.
 */
@Entity(tableName = "barcode_products")
data class BarcodeProductEntity(
    @PrimaryKey val barcode: String,
    val name: String,
    val categories: String = "",
    val ruleId: String? = null,
    /** The package size as the label states it, such as "400 g". */
    val size: String = "",
)

/**
 * What the user confirmed a receipt's printed name to mean. [rawName] is upper-cased, so the same
 * store shorthand resolves by itself on the next receipt.
 */
@Entity(tableName = "receipt_aliases")
data class ReceiptAliasEntity(
    @PrimaryKey val rawName: String,
    val name: String,
    val ruleId: String,
)

/** A food the user keeps in stock. It goes on the shopping list when less than [parAmount] is left. */
@Entity(tableName = "staples")
data class StapleEntity(
    @PrimaryKey val ruleId: String,
    /** In the rule's stock unit. */
    val parAmount: Double,
    /** When the user last told us how much is left (a quick check, or ticking it off the list). */
    val checkedAt: Long? = null,
)

/**
 * One row per local day the digest ran. [keys] are what it covered ("item:12", "check:milk"), so
 * the next day can tell whether anything is new. [sent] is false when nothing new was found.
 */
@Entity(tableName = "digest_log")
data class DigestLogEntity(
    @PrimaryKey val windowKey: String,
    val keys: String,
    val sent: Boolean,
    val at: Long,
)

enum class ShoppingReason { LOW_STOCK, RECIPE, MANUAL }

/** [id] is the rule id when there is one, so the same food is never listed twice. */
@Entity(tableName = "shopping_items")
data class ShoppingItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val ruleId: String?,
    val reason: ShoppingReason,
    val note: String?,
    val addedAt: Long,
)

/** One thing that happened to food, valued in cents. See [com.pantrypilot.data.UsageLog]. */
@Entity(tableName = "usage_log", indices = [Index("at")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val ruleId: String,
    val name: String,
    val kind: UsageKind,
    /** In the item's stock unit. */
    val amount: Double,
    val cents: Double,
    val rescued: Boolean,
    val estimated: Boolean,
    val at: Long,
) {
    fun toEntry(): UsageEntry =
        UsageEntry(java.time.Instant.ofEpochMilli(at), ruleId, name, kind, cents, rescued, estimated)
}

enum class EventType { STORED, OPENED }

@Entity(
    tableName = "item_events",
    foreignKeys = [
        ForeignKey(
            entity = PantryItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("itemId")],
)
data class ItemEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val type: EventType,
    val zone: StorageZone? = null,
    val at: Long,
)
