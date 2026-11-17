package com.pantrypilot.app

import android.app.Application
import com.pantrypilot.app.digest.DigestNotifier
import com.pantrypilot.app.digest.DigestScheduler
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.RestockRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class PantryPilotApp : Application() {
    @Inject lateinit var repository: PantryRepository

    @Inject lateinit var restock: RestockRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        DigestNotifier.createChannel(this)
        DigestScheduler.ensureScheduled(this)
        appScope.launch {
            repository.seedRulesIfEmpty()
            restock.checkAllStaples()
        }
    }
}
