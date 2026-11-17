package com.pantrypilot.app.digest

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pantrypilot.data.DigestRepository
import com.pantrypilot.data.PantryRepository
import com.pantrypilot.data.RestockRepository
import com.pantrypilot.domain.digest.DigestPlanner
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Duration
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DigestDeps {
    fun digest(): DigestRepository
    fun restock(): RestockRepository
    fun pantry(): PantryRepository
}

fun Context.digestDeps(): DigestDeps = EntryPointAccessors.fromApplication(applicationContext, DigestDeps::class.java)

/**
 * Runs once a day: lists staples that ran low, then sends the "use soon" digest if there is
 * something new. It always schedules the next run, even when this one fails.
 */
class DigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        // Turned off since this run was scheduled: do nothing and schedule nothing.
        if (!DigestSettings.read(applicationContext).enabled) return Result.success()
        val deps = applicationContext.digestDeps()
        try {
            deps.restock().checkAllStaples()
            deps.digest().prepare(ZonedDateTime.now())?.let { DigestNotifier.show(applicationContext, it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed worker would also fail the next run chained after it, so log and carry on.
            Log.e(TAG, "Digest failed", e)
        }
        DigestScheduler.scheduleNext(applicationContext)
        return Result.success()
    }

    private companion object {
        const val TAG = "DigestWorker"
    }
}

/**
 * One delayed job per day at the user's chosen local time (5:30 pm by default), instead of
 * polling. The job is unique by name, so scheduling twice never runs the digest twice.
 */
object DigestScheduler {
    private const val WORK_NAME = "expiry-digest"

    /** At app start: keeps an existing schedule. */
    fun ensureScheduled(context: Context) = enqueue(context, ExistingWorkPolicy.KEEP)

    /** After the clock or time zone changes: the pending run's delay is now wrong, so it is replaced. */
    fun reschedule(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE)

    /** From inside the running job: replacing it would cancel it, so the next run is chained after it. */
    fun scheduleNext(context: Context) = enqueue(context, ExistingWorkPolicy.APPEND_OR_REPLACE)

    private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
        val settings = DigestSettings.read(context)
        if (!settings.enabled) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            return
        }
        val now = ZonedDateTime.now()
        val delay = Duration.between(now, DigestPlanner.nextRun(now, settings.time))
        val request = OneTimeWorkRequestBuilder<DigestWorker>()
            .setInitialDelay(delay.toMillis(), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }
}
