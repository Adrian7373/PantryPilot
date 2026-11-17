package com.pantrypilot.app.digest

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pantrypilot.app.MainActivity
import com.pantrypilot.app.R
import com.pantrypilot.app.TopLevelTab
import com.pantrypilot.app.expiryShort
import com.pantrypilot.app.shortFoodName
import com.pantrypilot.data.Digest
import com.pantrypilot.domain.decay.StorageZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** The single daily notification, grouping everything at risk instead of one alert per item. */
object DigestNotifier {
    private const val CHANNEL_ID = "digest"
    private const val NOTIFICATION_ID = 1001
    private const val MAX_LINES = 6

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Use soon", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "One daily summary of food to use in the next two days"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(context: Context, digest: Digest) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)

        val itemLines = digest.items.map { "${it.name} · ${expiryLabel(it.expiresAt)}" }
        val checkLines = digest.checks.map { "Still have ${it.rule.name.shortFoodName().lowercase()}? Probably running low" }
        val lines = itemLines + checkLines
        val title = when {
            digest.items.size == 1 -> "1 thing to use in the next 2 days"
            digest.items.isNotEmpty() -> "${digest.items.size} things to use in the next 2 days"
            else -> "Quick check on your staples"
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(MAX_LINES).forEach(style::addLine)
        if (lines.size > MAX_LINES) style.setSummaryText("+${lines.size - MAX_LINES} more")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(lines.joinToString(", ") { it.substringBefore(" ·") })
            .setStyle(style)
            .setContentIntent(openTab(context, TopLevelTab.PANTRY, requestCode = 0))
            .setAutoCancel(true)

        if (digest.items.isNotEmpty()) {
            builder.addAction(0, "Find a recipe", openTab(context, TopLevelTab.RECIPES, requestCode = 1))
        }
        val freezable = digest.items.filter { it.canFreeze }
        if (freezable.isNotEmpty()) {
            val label = if (freezable.size == digest.items.size) "Freeze all" else "Freeze ${freezable.size}"
            builder.addAction(0, label, freeze(context, freezable.map { it.id }.toLongArray()))
        }
        if (digest.checks.isNotEmpty()) {
            builder.addAction(0, "Check staples", openTab(context, TopLevelTab.RESTOCK, requestCode = 2))
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    private fun expiryLabel(expiresAt: Long): String = when (val short = expiryShort(expiresAt)) {
        "expired" -> "expired"
        "today", "tomorrow" -> "use by $short"
        else -> "$short left"
    }

    private fun openTab(context: Context, tab: TopLevelTab, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_TAB, tab.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun freeze(context: Context, ids: LongArray): PendingIntent {
        val intent = Intent(context, DigestActionReceiver::class.java)
            .setAction(DigestActionReceiver.ACTION_FREEZE)
            .putExtra(DigestActionReceiver.EXTRA_ITEM_IDS, ids)
        return PendingIntent.getBroadcast(context, 3, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

/** Handles "Freeze" from the notification without opening the app. */
class DigestActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FREEZE) return
        val ids = intent.getLongArrayExtra(EXTRA_ITEM_IDS) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val pantry = context.digestDeps().pantry()
                ids.forEach { pantry.moveTo(it, StorageZone.FREEZER) }
                DigestNotifier.cancel(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_FREEZE = "com.pantrypilot.app.action.FREEZE"
        const val EXTRA_ITEM_IDS = "itemIds"
    }
}

/** A changed clock or time zone makes the pending digest's delay wrong, so it is worked out again. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_TIMEZONE_CHANGED || intent.action == Intent.ACTION_TIME_CHANGED) {
            DigestScheduler.reschedule(context)
        }
    }
}
