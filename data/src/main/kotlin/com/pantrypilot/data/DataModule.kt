package com.pantrypilot.data

import android.content.Context
import androidx.room.Room
import com.pantrypilot.data.db.BarcodeDao
import com.pantrypilot.data.db.DigestDao
import com.pantrypilot.data.db.PantryDao
import com.pantrypilot.data.db.PantryDatabase
import com.pantrypilot.data.db.ReceiptAliasDao
import com.pantrypilot.data.db.RestockDao
import com.pantrypilot.data.db.ShelfLifeDao
import com.pantrypilot.data.db.UsageDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): PantryDatabase =
        Room.databaseBuilder(context, PantryDatabase::class.java, "pantry.db")
            // Before the first release, a schema change starts the database over. Replace this with
            // real migrations once people keep data in the app.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    fun pantryDao(db: PantryDatabase): PantryDao = db.pantryDao()

    @Provides
    fun shelfLifeDao(db: PantryDatabase): ShelfLifeDao = db.shelfLifeDao()

    @Provides
    fun barcodeDao(db: PantryDatabase): BarcodeDao = db.barcodeDao()

    @Provides
    fun receiptAliasDao(db: PantryDatabase): ReceiptAliasDao = db.receiptAliasDao()

    @Provides
    fun restockDao(db: PantryDatabase): RestockDao = db.restockDao()

    @Provides
    fun digestDao(db: PantryDatabase): DigestDao = db.digestDao()

    @Provides
    fun usageDao(db: PantryDatabase): UsageDao = db.usageDao()

    @Provides
    fun clock(): Clock = Clock.systemUTC()
}
