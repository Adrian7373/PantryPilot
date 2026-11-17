package com.pantrypilot.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry_items WHERE disposition = 'ACTIVE' ORDER BY expiresAt")
    fun observeActive(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items WHERE disposition = 'ACTIVE'")
    suspend fun activeItems(): List<PantryItemEntity>

    /** Every item ever added, used up and tossed included: the purchase history. */
    @Query("SELECT * FROM pantry_items")
    fun observeAll(): Flow<List<PantryItemEntity>>

    @Query("SELECT * FROM pantry_items")
    suspend fun allItems(): List<PantryItemEntity>

    @Query("SELECT * FROM pantry_items WHERE disposition = 'ACTIVE' AND ruleId = :ruleId")
    suspend fun activeFor(ruleId: String): List<PantryItemEntity>

    @Query("SELECT * FROM pantry_items WHERE id = :id")
    suspend fun item(id: Long): PantryItemEntity?

    @Insert
    suspend fun insertItem(item: PantryItemEntity): Long

    @Update
    suspend fun updateItem(item: PantryItemEntity)

    @Insert
    suspend fun insertEvent(event: ItemEventEntity)

    @Query("SELECT * FROM item_events WHERE itemId = :itemId ORDER BY at, id")
    suspend fun events(itemId: Long): List<ItemEventEntity>
}

@Dao
interface ShelfLifeDao {
    @Query("SELECT COUNT(*) FROM shelf_life_rules")
    suspend fun count(): Int

    @Query("SELECT * FROM shelf_life_rules")
    suspend fun all(): List<ShelfLifeRuleEntity>

    @Query("SELECT * FROM shelf_life_rules ORDER BY name")
    fun observeAll(): Flow<List<ShelfLifeRuleEntity>>

    @Query("SELECT * FROM shelf_life_rules WHERE id = :id")
    suspend fun rule(id: String): ShelfLifeRuleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rules: List<ShelfLifeRuleEntity>)
}

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcode_products WHERE barcode = :barcode")
    suspend fun product(barcode: String): BarcodeProductEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(product: BarcodeProductEntity)
}

@Dao
interface ReceiptAliasDao {
    @Query("SELECT * FROM receipt_aliases WHERE rawName IN (:rawNames)")
    suspend fun aliases(rawNames: List<String>): List<ReceiptAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(alias: ReceiptAliasEntity)
}

@Dao
interface RestockDao {
    @Query("SELECT * FROM staples")
    fun observeStaples(): Flow<List<StapleEntity>>

    @Query("SELECT * FROM staples WHERE ruleId = :ruleId")
    suspend fun staple(ruleId: String): StapleEntity?

    @Query("SELECT * FROM staples")
    suspend fun allStaples(): List<StapleEntity>

    @Query("UPDATE staples SET checkedAt = :at WHERE ruleId = :ruleId")
    suspend fun setCheckedAt(ruleId: String, at: Long)

    @Query("SELECT id FROM shopping_items")
    suspend fun shoppingIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveStaples(staples: List<StapleEntity>)

    @Query("DELETE FROM staples WHERE ruleId = :ruleId")
    suspend fun deleteStaple(ruleId: String)

    @Query("SELECT * FROM shopping_items ORDER BY addedAt")
    fun observeShopping(): Flow<List<ShoppingItemEntity>>

    /** Returns -1 when the item was already on the list. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addShopping(item: ShoppingItemEntity): Long

    @Query("DELETE FROM shopping_items WHERE id = :id")
    suspend fun deleteShopping(id: String)
}

@Dao
interface DigestDao {
    @Query("SELECT * FROM digest_log WHERE windowKey = :windowKey")
    suspend fun log(windowKey: String): DigestLogEntity?

    @Query("SELECT * FROM digest_log WHERE sent = 1 ORDER BY at DESC LIMIT 1")
    suspend fun lastSent(): DigestLogEntity?

    /** Ignores a second row for the same day, so a retried run cannot send twice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(log: DigestLogEntity): Long
}

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(entry: UsageEntity)

    @Query("SELECT * FROM usage_log ORDER BY at")
    fun observeAll(): Flow<List<UsageEntity>>
}

@Database(
    entities = [
        ShelfLifeRuleEntity::class,
        PantryItemEntity::class,
        ItemEventEntity::class,
        BarcodeProductEntity::class,
        ReceiptAliasEntity::class,
        StapleEntity::class,
        ShoppingItemEntity::class,
        DigestLogEntity::class,
        UsageEntity::class,
    ],
    version = 4,
)
abstract class PantryDatabase : RoomDatabase() {
    abstract fun barcodeDao(): BarcodeDao
    abstract fun receiptAliasDao(): ReceiptAliasDao
    abstract fun restockDao(): RestockDao
    abstract fun digestDao(): DigestDao
    abstract fun usageDao(): UsageDao
    abstract fun pantryDao(): PantryDao
    abstract fun shelfLifeDao(): ShelfLifeDao
}
