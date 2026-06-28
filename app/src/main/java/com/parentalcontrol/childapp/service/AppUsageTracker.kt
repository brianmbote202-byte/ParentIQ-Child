package com.parentalcontrol.childapp.service

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Calendar
import android.app.usage.UsageEvents
import kotlin.math.max

class AppUsageTracker(private val context: Context) {


    /**
     * Checks if Usage Access permission is granted
     */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Prompts user to grant Usage Access if missing
     */
    fun requestUsageStatsPermission() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        Log.w("USAGE_TRACKER", "⚠️ Usage Access not granted. Open settings to enable.")
    }

    /**
     * Returns total usage in minutes for the app today
     */
    fun getUsedMinutes(appPackage: String): Long {
        if (!hasUsageStatsPermission()) {
            Log.w("USAGE_TRACKER", "⚠️ Cannot read usage for $appPackage: Usage Access missing")
            requestUsageStatsPermission()
            return 0
        }

        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val statsList = usageStatsManager.queryUsageStats(
            UsageStatsManager.INTERVAL_DAILY,
            startOfDay,
            now
        )

        var totalTime = 0L
        statsList?.forEach { usage ->
            if (usage.packageName == appPackage) {
                totalTime += usage.totalTimeInForeground
            }
        }

        val millis = getTotalFromEvents(appPackage, startOfDay, now)

        Log.d("USAGE_TRACKER", "App: $appPackage Minutes: ${totalTime / 1000 / 60}")
        return totalTime / 1000 / 60 // milliseconds → minutes
    }

    fun isLimitReached(appPackage: String, dailyLimitMinutes: Int): Boolean {
        return getUsedMinutes(appPackage) >= dailyLimitMinutes
    }

    fun getRemainingMinutes(appPackage: String, dailyLimitMinutes: Long): Long {
        val used = getUsedMinutes(appPackage)
        return (dailyLimitMinutes - used).coerceAtLeast(0)
    }

    fun getAppSessions(appPackage: String): List<Map<String, Long>> {
        if (!hasUsageStatsPermission()) {
            Log.w("USAGE_TRACKER", "⚠️ Cannot read sessions for $appPackage: Usage Access missing")
            requestUsageStatsPermission()
            return emptyList()
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startOfDay = cal.timeInMillis
        val now = System.currentTimeMillis()

        val events = usageStatsManager.queryEvents(startOfDay, now)
        val sessions = mutableListOf<Map<String, Long>>()

        var startTime: Long? = null
        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (event.packageName != appPackage) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    startTime = event.timeStamp
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (startTime != null) {
                        val endTime = event.timeStamp
                        //val duration = max((endTime - startTime!!) / 1000 / 60, 1)
                        val durationSeconds =
                            ((endTime - startTime!!) / 1000)
                                .coerceAtLeast(1)

                        /*sessions.add(
                            mapOf(
                                "from" to startTime!!,
                                "to" to endTime,
                                "durationMinutes" to duration
                            )
                        )*/
                        sessions.add(
                            mapOf(
                                "from" to startTime!!,
                                "to" to endTime,
                                "durationSeconds" to durationSeconds
                            )
                        )

                        startTime = null

                    }
                }
            }
        }

        Log.d("USAGE_TRACKER", "Sessions for $appPackage: ${sessions.size}")
        return sessions
    }
    private fun getTotalFromEvents(appPackage: String, start: Long, end: Long): Long {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

        val events = usageStatsManager.queryEvents(start, end)
        val event = UsageEvents.Event()

        var lastStart: Long? = null
        var total = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            if (event.packageName != appPackage) continue

            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    lastStart = event.timeStamp
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    if (lastStart != null) {
                        total += (event.timeStamp - lastStart!!)
                        lastStart = null
                    }
                }
            }
        }

        return total
    }
}