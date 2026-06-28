package com.parentalcontrol.childapp.storage

import android.content.Context
import android.content.SharedPreferences

object ScreenTimeManager {

    private const val PREFS_NAME = "screen_time_prefs"
    private const val KEY_REMAINING_TIME = "remaining_time"

    fun getRemainingScreenTime(context: Context): Long {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getLong(KEY_REMAINING_TIME, 60_000L) // default 60s
    }

    fun setRemainingScreenTime(context: Context, millis: Long) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putLong(KEY_REMAINING_TIME, millis).apply()
    }

    fun reduceScreenTime(context: Context, millis: Long) {
        val remaining = getRemainingScreenTime(context) - millis
        setRemainingScreenTime(context, if (remaining > 0) remaining else 0)
    }
}
