package com.pantrypilot.app.digest

import android.content.Context
import com.pantrypilot.domain.digest.DigestPlanner
import java.time.LocalTime

data class DigestPrefs(val enabled: Boolean, val time: LocalTime)

/** Whether the daily digest is on, and when it runs. Kept on the device; changing it reschedules. */
object DigestSettings {
    private const val FILE = "digest"
    private const val ENABLED = "enabled"
    private const val MINUTE_OF_DAY = "minute_of_day"

    fun read(context: Context): DigestPrefs {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val default = DigestPlanner.DEFAULT_TIME.hour * 60 + DigestPlanner.DEFAULT_TIME.minute
        val minute = prefs.getInt(MINUTE_OF_DAY, default)
        return DigestPrefs(prefs.getBoolean(ENABLED, true), LocalTime.of(minute / 60, minute % 60))
    }

    fun write(context: Context, value: DigestPrefs) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putBoolean(ENABLED, value.enabled)
            .putInt(MINUTE_OF_DAY, value.time.hour * 60 + value.time.minute)
            .apply()
        DigestScheduler.reschedule(context)
    }
}
