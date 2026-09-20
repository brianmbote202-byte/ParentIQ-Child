package com.parentalcontrol.childapp.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import android.util.Log
import java.util.Calendar

class AppUsageTracker(
    private val context: Context
) {

    companion object {

        private const val TAG = "USAGE_TRACKER"

        /*
         * Prevent a corrupted/missing event sequence from
         * creating an absurdly long interval.
         */
        private const val MAX_REASONABLE_SESSION_MILLIS =
            24L * 60L * 60L * 1000L

        /*
         * We look backwards before midnight so we can determine
         * whether the screen was already interactive when today
         * started.
         */
        private const val STATE_LOOKBACK_MILLIS =
            2L * 60L * 60L * 1000L
    }

    // =========================================================
    // USAGE ACCESS
    // =========================================================

    fun hasUsageStatsPermission(): Boolean {

        val appOps =
            context.getSystemService(
                Context.APP_OPS_SERVICE
            ) as AppOpsManager

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    // =========================================================
    // REQUEST PERMISSION
    // =========================================================

    fun requestUsageStatsPermission() {

        try {

            val intent =
                Intent(
                    Settings.ACTION_USAGE_ACCESS_SETTINGS
                ).apply {
                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK
                }

            context.startActivity(intent)

            Log.w(
                TAG,
                "⚠️ Usage Access not granted. " +
                        "Opening Usage Access settings."
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to open Usage Access settings",
                e
            )
        }
    }

    // =========================================================
    // START OF TODAY
    // =========================================================

    private fun getStartOfToday(): Long {

        val calendar =
            Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            0
        )

        calendar.set(
            Calendar.MINUTE,
            0
        )

        calendar.set(
            Calendar.SECOND,
            0
        )

        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        return calendar.timeInMillis
    }

    // =========================================================
    // USAGE STATS MANAGER
    // =========================================================

    private fun getUsageStatsManager():
            UsageStatsManager {

        return context.getSystemService(
            Context.USAGE_STATS_SERVICE
        ) as UsageStatsManager
    }

    // =========================================================
    // DEVICE SCREEN-TIME INTERVALS
    //
    // IMPORTANT:
    //
    // Device screen time is determined from:
    //
    //     SCREEN_INTERACTIVE
    //             ->
    //     SCREEN_NON_INTERACTIVE
    //
    // KEYGUARD events are logged for diagnostics but are NOT
    // required to start screen time.
    //
    // This is important because KEYGUARD events are not always
    // sufficient/reliable for reconstructing the complete
    // unlocked state on every Android device.
    //
    // Screen interactive time includes:
    //
    //     Home screen
    //     Launcher
    //     Settings
    //     Chrome
    //     YouTube
    //     Messages
    //     etc.
    //
    // Screen-off time does not count.
    // =========================================================
    fun getScreenTimeIntervals(
        dayStart: Long,
        now: Long
    ): List<Pair<Long, Long>> {

        if (!hasUsageStatsPermission()) {
            Log.e(TAG, "Usage Access permission missing")
            return emptyList()
        }

        if (now <= dayStart) {
            return emptyList()
        }

        val manager = getUsageStatsManager()

        /*
         * We need events before midnight so that we can reconstruct
         * the state at the beginning of today.
         */
        val queryStart = maxOf(
            0L,
            dayStart - STATE_LOOKBACK_MILLIS
        )

        val events = manager.queryEvents(
            queryStart,
            now
        )

        val result = mutableListOf<Pair<Long, Long>>()

        /*
         * Current device state.
         */
        var interactive = false
        var unlocked = false

        /*
         * Start of the currently valid:
         *
         *     interactive + unlocked
         *
         * interval.
         */
        var activeStart: Long? = null

        val event = UsageEvents.Event()

        while (events.hasNextEvent()) {

            events.getNextEvent(event)

            val timestamp = event.timeStamp

            if (timestamp < queryStart || timestamp > now) {
                continue
            }

            when (event.eventType) {

                // =================================================
                // SCREEN BECAME INTERACTIVE
                // =================================================

                UsageEvents.Event.SCREEN_INTERACTIVE -> {

                    interactive = true

                    Log.d(
                        TAG,
                        "SCREEN_INTERACTIVE " +
                                formatDateTime(timestamp)
                    )

                    /*
                     * We can only count time if the phone is also
                     * unlocked.
                     */
                    if (
                        timestamp >= dayStart &&
                        unlocked &&
                        activeStart == null
                    ) {

                        activeStart = timestamp

                        Log.d(
                            TAG,
                            "START ACTIVE SCREEN TIME " +
                                    "(interactive)"
                        )
                    }
                }

                // =================================================
                // SCREEN BECAME NON-INTERACTIVE
                // =================================================

                UsageEvents.Event.SCREEN_NON_INTERACTIVE -> {

                    /*
                     * Close an active interval BEFORE changing state.
                     */
                    closeScreenInterval(
                        result = result,
                        activeStart = activeStart,
                        end = timestamp,
                        reason = "screen non-interactive"
                    )

                    activeStart = null
                    interactive = false

                    Log.d(
                        TAG,
                        "SCREEN_NON_INTERACTIVE " +
                                formatDateTime(timestamp)
                    )
                }

                // =================================================
                // DEVICE UNLOCKED
                // =================================================

                UsageEvents.Event.KEYGUARD_HIDDEN -> {

                    unlocked = true

                    Log.d(
                        TAG,
                        "KEYGUARD_HIDDEN " +
                                formatDateTime(timestamp)
                    )

                    /*
                     * If screen is already interactive, active usage
                     * starts at the unlock moment.
                     */
                    if (
                        timestamp >= dayStart &&
                        interactive &&
                        activeStart == null
                    ) {

                        activeStart = timestamp

                        Log.d(
                            TAG,
                            "START ACTIVE SCREEN TIME " +
                                    "(unlock)"
                        )
                    }
                }

                // =================================================
                // DEVICE LOCKED
                // =================================================

                UsageEvents.Event.KEYGUARD_SHOWN -> {

                    /*
                     * Close the interval at the exact lock time.
                     */
                    closeScreenInterval(
                        result = result,
                        activeStart = activeStart,
                        end = timestamp,
                        reason = "keyguard shown"
                    )

                    activeStart = null
                    unlocked = false

                    Log.d(
                        TAG,
                        "KEYGUARD_SHOWN " +
                                formatDateTime(timestamp)
                    )
                }
            }
        }

        // =========================================================
        // CURRENTLY ACTIVE
        // =========================================================

        /*
         * If the phone is currently:
         *
         *     interactive + unlocked
         *
         * count from activeStart until now.
         */
        if (
            activeStart != null &&
            interactive &&
            unlocked &&
            now > activeStart
        ) {

            closeScreenInterval(
                result = result,
                activeStart = activeStart,
                end = now,
                reason = "currently active"
            )
        }

        // =========================================================
        // CLEAN + CLIP TO TODAY
        // =========================================================

        val cleaned = result
            .mapNotNull { (rawStart, rawEnd) ->

                val start = maxOf(
                    rawStart,
                    dayStart
                )

                val end = minOf(
                    rawEnd,
                    now
                )

                if (end <= start) {
                    null
                } else {
                    start to end
                }
            }
            .sortedBy { it.first }

        // =========================================================
        // MERGE
        // =========================================================

        val merged = mergeTimeIntervals(cleaned)

        // =========================================================
        // LOG RESULT
        // =========================================================

        var totalMillis = 0L

        Log.d(TAG, "==================================================")
        Log.d(TAG, "ACTIVE SCREEN TIME RESULT")

        merged.forEach { (start, end) ->

            val duration = end - start

            totalMillis += duration

            Log.d(
                TAG,
                "SCREEN INTERVAL: " +
                        "${formatDateTime(start)} -> " +
                        "${formatDateTime(end)} " +
                        "(${duration / 1000L}s)"
            )
        }

        Log.d(
            TAG,
            "Total seconds = ${totalMillis / 1000L}"
        )

        Log.d(
            TAG,
            "Total minutes = ${totalMillis / 60000L}"
        )

        Log.d(
            TAG,
            "Total hours = ${totalMillis / 3600000L}"
        )

        Log.d(TAG, "Intervals = ${merged.size}")
        Log.d(TAG, "==================================================")

        return merged
    }
    private fun closeScreenInterval(
        result: MutableList<Pair<Long, Long>>,
        activeStart: Long?,
        end: Long,
        reason: String
    ) {

        if (activeStart == null) {
            return
        }

        if (end <= activeStart) {
            return
        }

        val duration = end - activeStart

        if (
            duration > MAX_REASONABLE_SESSION_MILLIS
        ) {

            Log.w(
                TAG,
                "Ignoring unreasonable screen interval: " +
                        "${formatDateTime(activeStart)} -> " +
                        "${formatDateTime(end)} " +
                        "duration=${duration / 1000L}s " +
                        "reason=$reason"
            )

            return
        }

        result.add(
            activeStart to end
        )

        Log.d(
            TAG,
            "END ACTIVE SCREEN TIME " +
                    "($reason): " +
                    "${formatDateTime(activeStart)} -> " +
                    "${formatDateTime(end)} " +
                    "duration=${duration / 1000L}s"
        )
    }

    // =========================================================
    // MERGE TIME INTERVALS
    // =========================================================

    private fun mergeTimeIntervals(
        intervals: List<Pair<Long, Long>>
    ): List<Pair<Long, Long>> {

        if (intervals.isEmpty()) {
            return emptyList()
        }

        val sorted =
            intervals
                .filter {
                    it.second > it.first
                }
                .sortedBy {
                    it.first
                }

        if (sorted.isEmpty()) {
            return emptyList()
        }

        val merged =
            mutableListOf<Pair<Long, Long>>()

        var currentStart =
            sorted.first().first

        var currentEnd =
            sorted.first().second

        for (i in 1 until sorted.size) {

            val next =
                sorted[i]

            /*
             * Merge overlapping or directly adjacent intervals.
             */
            if (
                next.first <= currentEnd
            ) {

                currentEnd =
                    maxOf(
                        currentEnd,
                        next.second
                    )

            } else {

                merged.add(
                    currentStart to currentEnd
                )

                currentStart =
                    next.first

                currentEnd =
                    next.second
            }
        }

        merged.add(
            currentStart to currentEnd
        )

        return merged
    }

    // =========================================================
    // GET APP USAGE FROM USAGE STATS
    //
    // This is separate from device screen time.
    //
    // totalTimeInForeground is useful as a diagnostic and
    // often provides a much cleaner app-specific measurement
    // than reconstructing every foreground/background event.
    // =========================================================

    fun getUsageStatsForApp(
        appPackage: String
    ): Long {

        if (!hasUsageStatsPermission()) {

            Log.w(
                TAG,
                "⚠️ Cannot read usage stats for $appPackage: " +
                        "Usage Access missing"
            )

            return 0L
        }

        val startOfDay =
            getStartOfToday()

        val now =
            System.currentTimeMillis()

        if (now <= startOfDay) {
            return 0L
        }

        val usageStatsManager =
            getUsageStatsManager()

        val stats =
            usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startOfDay,
                now
            )

        val matchingStats =
            stats.filter {
                it.packageName == appPackage
            }

        var totalMillis =
            0L

        matchingStats.forEach {
            totalMillis +=
                it.totalTimeInForeground
        }

        val totalSeconds =
            totalMillis / 1000L

        Log.d(
            TAG,
            "=================================================="
        )

        Log.d(
            TAG,
            "USAGE STATS"
        )

        Log.d(
            TAG,
            "Package = $appPackage"
        )

        Log.d(
            TAG,
            "Foreground seconds = $totalSeconds"
        )

        Log.d(
            TAG,
            "Foreground minutes = ${totalSeconds / 60L}"
        )

        Log.d(
            TAG,
            "=================================================="
        )

        return totalSeconds
    }

    // =========================================================
    // GET USED SECONDS FOR AN APP
    //
    // Kept compatible with your existing code.
    //
    // This continues using reconstructed application sessions.
    // =========================================================

    fun getUsedSeconds(
        appPackage: String
    ): Long {

        if (!hasUsageStatsPermission()) {

            Log.w(
                TAG,
                "⚠️ Cannot read usage for $appPackage: " +
                        "Usage Access missing"
            )

            return 0L
        }

        val startOfDay =
            getStartOfToday()

        val now =
            System.currentTimeMillis()

        if (now <= startOfDay) {
            return 0L
        }

        val sessions =
            getAppSessions(
                appPackage
            )

        var totalMillis =
            0L

        sessions.forEach { session ->

            val start =
                session["from"]
                    ?: return@forEach

            val end =
                session["to"]
                    ?: return@forEach

            if (end > start) {

                totalMillis +=
                    end - start
            }
        }

        val totalSeconds =
            totalMillis / 1000L

        Log.d(
            TAG,
            "$appPackage -> " +
                    "$totalSeconds seconds today"
        )

        return totalSeconds
    }

    // =========================================================
    // CURRENT USAGE MINUTES
    // =========================================================

    fun getUsedMinutes(
        appPackage: String
    ): Long {

        return getUsedSeconds(
            appPackage
        ) / 60L
    }

    // =========================================================
    // LIMIT
    // =========================================================

    fun isLimitReached(
        appPackage: String,
        dailyLimitMinutes: Int
    ): Boolean {

        return getUsedMinutes(
            appPackage
        ) >= dailyLimitMinutes
    }

    // =========================================================
    // REMAINING
    // =========================================================

    fun getRemainingMinutes(
        appPackage: String,
        dailyLimitMinutes: Long
    ): Long {

        val used =
            getUsedMinutes(
                appPackage
            )

        return (
                dailyLimitMinutes - used
                ).coerceAtLeast(0L)
    }

    // =========================================================
    // GET EXACT APP SESSIONS
    //
    // Application foreground/background sessions.
    //
    // These are NOT device screen-time intervals.
    // =========================================================

    fun getAppSessions(
        appPackage: String
    ): List<Map<String, Long>> {

        if (!hasUsageStatsPermission()) {

            Log.w(
                TAG,
                "⚠️ Cannot read sessions for $appPackage: " +
                        "Usage Access missing"
            )

            return emptyList()
        }

        val startOfDay =
            getStartOfToday()

        val now =
            System.currentTimeMillis()

        if (now <= startOfDay) {
            return emptyList()
        }

        val usageStatsManager =
            getUsageStatsManager()

        val events =
            usageStatsManager.queryEvents(
                startOfDay,
                now
            )

        val sessions =
            mutableListOf<Map<String, Long>>()

        var foregroundStart: Long? =
            null

        val event =
            UsageEvents.Event()

        while (events.hasNextEvent()) {

            events.getNextEvent(event)

            if (
                event.packageName !=
                appPackage
            ) {
                continue
            }

            when (event.eventType) {

                UsageEvents.Event.MOVE_TO_FOREGROUND -> {

                    /*
                     * Ignore duplicate foreground events.
                     */
                    if (
                        foregroundStart == null
                    ) {

                        foregroundStart =
                            event.timeStamp

                        Log.d(
                            TAG,
                            "$appPackage FOREGROUND @ " +
                                    formatDateTime(
                                        event.timeStamp
                                    )
                        )
                    }
                }

                UsageEvents.Event.MOVE_TO_BACKGROUND -> {

                    val start =
                        foregroundStart

                    if (start != null) {

                        val end =
                            event.timeStamp

                        if (
                            end > start &&
                            end - start <=
                            MAX_REASONABLE_SESSION_MILLIS
                        ) {

                            sessions.add(
                                createSession(
                                    start,
                                    end
                                )
                            )

                            Log.d(
                                TAG,
                                "$appPackage BACKGROUND @ " +
                                        formatDateTime(end) +
                                        " duration=" +
                                        ((end - start) / 1000L) +
                                        "s"
                            )
                        }

                        foregroundStart =
                            null
                    }
                }
            }
        }

        // =====================================================
        // APP STILL IN FOREGROUND
        // =====================================================

        val openStart =
            foregroundStart

        if (
            openStart != null &&
            now > openStart
        ) {

            val duration =
                now - openStart

            if (
                duration <=
                MAX_REASONABLE_SESSION_MILLIS
            ) {

                sessions.add(
                    createSession(
                        openStart,
                        now
                    )
                )

                Log.d(
                    TAG,
                    "$appPackage currently foreground -> " +
                            "session closed at now"
                )
            }
        }

        Log.d(
            TAG,
            "Sessions for $appPackage: " +
                    sessions.size
        )

        return sessions
    }

    // =========================================================
    // CREATE APP SESSION
    // =========================================================

    private fun createSession(
        start: Long,
        end: Long
    ): Map<String, Long> {

        val durationSeconds =
            ((end - start) / 1000L)
                .coerceAtLeast(1L)

        return mapOf(
            "from" to start,
            "to" to end,
            "durationSeconds" to durationSeconds
        )
    }

    // =========================================================
    // FORMAT DATE/TIME FOR LOGGING
    // =========================================================

    private fun formatDateTime(
        timestamp: Long
    ): String {

        val calendar =
            Calendar.getInstance()

        calendar.timeInMillis =
            timestamp

        val year =
            calendar.get(Calendar.YEAR)

        val month =
            calendar.get(Calendar.MONTH) + 1

        val day =
            calendar.get(Calendar.DAY_OF_MONTH)

        val hour =
            calendar.get(Calendar.HOUR_OF_DAY)

        val minute =
            calendar.get(Calendar.MINUTE)

        val second =
            calendar.get(Calendar.SECOND)

        return String.format(
            "%04d-%02d-%02d %02d:%02d:%02d",
            year,
            month,
            day,
            hour,
            minute,
            second
        )
    }
}