package com.parentalcontrol.childapp.utils

import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.util.*

object UsageTracker {

    /**
     * Returns the usage of a given app today in milliseconds.
     */
    fun getUsageForApp(appPackage: String, context: Context): Long {
        val usageStatsManager =
            context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return 0L

        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis
        val endTime = System.currentTimeMillis()

        val usageStatsList: List<UsageStats> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )
        } else {
            emptyList()
        }

        var total = 0L
        for (stats in usageStatsList) {
            if (stats.packageName == appPackage) {
                total += stats.totalTimeInForeground
            }
        }

        Log.d("UsageTracker", "App $appPackage usage today: $total ms")
        return total
    }
}