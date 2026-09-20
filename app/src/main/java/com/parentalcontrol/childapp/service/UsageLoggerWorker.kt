package com.parentalcontrol.childapp.service

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.TimeUnit

class UsageLoggerWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {

        private const val TAG = "USAGE_WORKER"

        // =====================================================
        // 5-MINUTE BUCKET
        // =====================================================

        private const val BUCKET_MILLIS =
            5 * 60 * 1000L

        private const val BUCKET_SECONDS =
            5 * 60L

        // =====================================================
        // FIREBASE TIMEOUT
        // =====================================================

        private const val FIREBASE_TIMEOUT_SECONDS =
            20L

        // =====================================================
        // EVENT LOOKBACK
        //
        // We look slightly before midnight so that if the child
        // unlocked the phone before midnight and remained using
        // it after midnight, we have a better chance of knowing
        // the state at the beginning of the day.
        // =====================================================

        private const val STATE_LOOKBACK_MILLIS =
            60 * 60 * 1000L
    }

    // =========================================================
    // TIME INTERVAL
    // =========================================================

    private data class UsageInterval(
        val start: Long,
        val end: Long
    )

    // =========================================================
    // MAIN WORK
    // =========================================================

    override fun doWork(): Result {

        Log.d(
            TAG,
            "=================================================="
        )

        Log.d(
            TAG,
            "UsageLoggerWorker STARTED"
        )

        Log.d(
            TAG,
            "=================================================="
        )

        try {

            // =================================================
            // CHILD ID
            // =================================================

            val childId =
                inputData.getString("childId")
                    ?: run {

                        Log.e(
                            TAG,
                            "❌ childId missing from Worker input"
                        )

                        return Result.failure()
                    }

            Log.d(
                TAG,
                "Child ID = $childId"
            )

            // =================================================
            // USAGE TRACKER
            // =================================================

            val usageTracker =
                AppUsageTracker(
                    applicationContext
                )

            // =================================================
            // USAGE ACCESS
            // =================================================

            if (!usageTracker.hasUsageStatsPermission()) {

                Log.e(
                    TAG,
                    "❌ Usage Access permission missing"
                )

                /*
                 * Do not launch Settings from WorkManager.
                 *
                 * Permission should be requested by the setup/
                 * activity flow.
                 */

                return Result.failure()
            }

            // =================================================
            // TODAY
            // =================================================

            val todayStart =
                getStartOfToday()

            val now =
                System.currentTimeMillis()

            Log.d(
                TAG,
                "Today start = $todayStart"
            )

            Log.d(
                TAG,
                "Now         = $now"
            )

            // =================================================
            // APP SESSION LOGGER
            //
            // This is independent of device screen-time.
            //
            // Screen time is calculated from the whole device.
            // App sessions are still stored separately.
            // =================================================

            val usageLogger =
                UsageLogger(
                    applicationContext,
                    childId
                )

            // =================================================
            // LOG TRACKED APP SESSIONS
            //
            // IMPORTANT:
            //
            // This is OPTIONAL for screen-time.
            //
            // Even if there are zero tracked apps, the worker
            // still calculates device screen time.
            // =================================================

            logTrackedAppSessions(
                usageTracker = usageTracker,
                usageLogger = usageLogger
            )

            // =================================================
            // DEVICE SCREEN TIME
            //
            // THIS IS THE PRIMARY SCREEN-TIME CALCULATION.
            //
            // It does NOT depend on tracked apps.
            //
            // It measures:
            //
            // UNLOCKED + SCREEN INTERACTIVE
            //
            // Therefore:
            //
            // Phone unlocked
            // Home screen
            // No app opened
            //
            // STILL COUNTS.
            // =================================================

            val screenIntervals =
                usageTracker.getScreenTimeIntervals(
                    dayStart = todayStart,
                    now = now
                )

            Log.d(
                TAG,
                "Screen-time intervals = ${screenIntervals.size}"
            )

            screenIntervals.forEach { interval ->

                Log.d(
                    TAG,
                    "SCREEN INTERVAL: " +
                            "${formatDateTime(interval.first)} -> " +
                            "${formatDateTime(interval.second)} " +
                            "(" +
                            "${(interval.second - interval.first) / 1000L}s" +
                            ")"
                )
            }

            // =================================================
            // CONVERT TO 5-MINUTE BUCKETS
            // =================================================

            val bucketData =
                createFiveMinuteBuckets(
                    screenIntervals
                )

            // =================================================
            // TOTAL SCREEN TIME
            // =================================================

            val totalSeconds =
                bucketData.values.sum()

            Log.d(
                TAG,
                "=================================================="
            )

            Log.d(
                TAG,
                "SCREEN TIME RESULT"
            )

            Log.d(
                TAG,
                "Total seconds = $totalSeconds"
            )

            Log.d(
                TAG,
                "Total minutes = ${totalSeconds / 60L}"
            )

            Log.d(
                TAG,
                "Total hours   = ${totalSeconds / 3600L}"
            )

            Log.d(
                TAG,
                "Buckets       = ${bucketData.size}"
            )

            Log.d(
                TAG,
                "=================================================="
            )

            // =================================================
            // WRITE TO FIREBASE
            // =================================================

            val firebaseSuccess =
                writeScreenTimeToFirebase(
                    childId = childId,
                    bucketData = bucketData,
                    totalSeconds = totalSeconds
                )

            if (!firebaseSuccess) {

                Log.e(
                    TAG,
                    "❌ Firebase screen-time write failed"
                )

                return Result.retry()
            }

            Log.d(
                TAG,
                "=================================================="
            )

            Log.d(
                TAG,
                "UsageLoggerWorker FINISHED SUCCESSFULLY"
            )

            Log.d(
                TAG,
                "=================================================="
            )

            return Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ UsageLoggerWorker failed",
                e
            )

            return Result.retry()
        }
    }

    // =========================================================
    // LOG TRACKED APPLICATION SESSIONS
    //
    // This does NOT determine screen time.
    //
    // It exists only to preserve your app-specific usage data.
    // =========================================================

    private fun logTrackedAppSessions(
        usageTracker: AppUsageTracker,
        usageLogger: UsageLogger
    ) {

        try {

            val prefs =
                applicationContext.getSharedPreferences(
                    "usage_tracker",
                    Context.MODE_PRIVATE
                )

            val appPackages =
                prefs.getStringSet(
                    "tracked_apps",
                    emptySet()
                )
                    ?.filter {
                        it.isNotBlank()
                    }
                    ?.distinct()
                    ?.toList()
                    ?: emptyList()

            Log.d(
                TAG,
                "Tracked applications = ${appPackages.size}"
            )

            // =================================================
            // NO TRACKED APPS
            //
            // This is NOT an error anymore.
            //
            // Device screen time is still calculated.
            // =================================================

            if (appPackages.isEmpty()) {

                Log.d(
                    TAG,
                    "No tracked apps. " +
                            "Continuing with device screen-time calculation."
                )

                return
            }

            // =================================================
            // PROCESS EACH APPLICATION
            // =================================================

            appPackages.forEach { appPackage ->

                try {

                    Log.d(
                        TAG,
                        "--------------------------------------------------"
                    )

                    Log.d(
                        TAG,
                        "Processing app: $appPackage"
                    )

                    val sessions =
                        usageTracker.getAppSessions(
                            appPackage
                        )

                    Log.d(
                        TAG,
                        "$appPackage -> " +
                                "${sessions.size} sessions"
                    )

                    if (sessions.isNotEmpty()) {

                        usageLogger.logAppSessions(
                            appPackage,
                            sessions
                        )

                        Log.d(
                            TAG,
                            "$appPackage -> app sessions queued"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Error processing app $appPackage",
                        e
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to log tracked app sessions",
                e
            )
        }
    }

    // =========================================================
    // CREATE FIVE-MINUTE BUCKETS
    //
    // Input:
    //
    // 22:03 -> 22:11
    //
    // Output:
    //
    // 22:00 -> 120
    // 22:05 -> 300
    // 22:10 -> 60
    //
    // Values are actual seconds.
    // =========================================================

    private fun createFiveMinuteBuckets(
        intervals: List<Pair<Long, Long>>
    ): LinkedHashMap<String, Long> {

        val result =
            LinkedHashMap<String, Long>()

        val todayStart =
            getStartOfToday()

        val now =
            System.currentTimeMillis()

        // =====================================================
        // CREATE BUCKETS FOR TODAY
        //
        // Only buckets that have started are created.
        // =====================================================

        var bucketStart =
            todayStart

        while (bucketStart <= now) {

            result[
                formatBucketTime(bucketStart)
            ] = 0L

            bucketStart +=
                BUCKET_MILLIS
        }

        // =====================================================
        // DISTRIBUTE SCREEN-TIME INTERVALS
        // =====================================================

        intervals.forEach { interval ->

            val originalStart =
                interval.first

            val originalEnd =
                interval.second

            // =================================================
            // CLAMP TO TODAY
            // =================================================

            val start =
                maxOf(
                    originalStart,
                    todayStart
                )

            val end =
                minOf(
                    originalEnd,
                    now
                )

            if (end <= start) {
                return@forEach
            }

            // =================================================
            // FIND FIRST BUCKET
            // =================================================

            var currentBucket =
                floorToFiveMinutes(
                    start
                )

            // =================================================
            // DISTRIBUTE INTERVAL
            // =================================================

            while (currentBucket < end) {

                val bucketEnd =
                    currentBucket +
                            BUCKET_MILLIS

                // =================================================
                // INTERSECTION
                // =================================================

                val overlapStart =
                    maxOf(
                        start,
                        currentBucket
                    )

                val overlapEnd =
                    minOf(
                        end,
                        bucketEnd
                    )

                if (overlapEnd > overlapStart) {

                    val seconds =
                        (
                                overlapEnd -
                                        overlapStart
                                ) / 1000L

                    if (seconds > 0L) {

                        val key =
                            formatBucketTime(
                                currentBucket
                            )

                        val existing =
                            result[key] ?: 0L

                        result[key] =
                            existing + seconds
                    }
                }

                currentBucket =
                    bucketEnd
            }
        }

        // =====================================================
        // SAFETY LIMIT
        //
        // A five-minute bucket cannot contain more than
        // five minutes of screen time.
        // =====================================================

        result.keys.forEach { key ->

            result[key] =
                (result[key] ?: 0L)
                    .coerceIn(
                        0L,
                        BUCKET_SECONDS
                    )
        }

        return result
    }

    // =========================================================
    // WRITE SCREEN TIME
    // =========================================================

    private fun writeScreenTimeToFirebase(
        childId: String,
        bucketData: Map<String, Long>,
        totalSeconds: Long
    ): Boolean {

        return try {

            val database =
                FirebaseDatabase.getInstance()

            val dateKey =
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault()
                ).format(
                    Date()
                )

            // =================================================
            // SCREEN TIME PATH
            //
            // screen_time
            //   childId
            //      daily
            //         00:00 -> 0
            //         00:05 -> 0
            //         00:10 -> 254
            //         00:15 -> 300
            // =================================================

            val dailyRef =
                database
                    .getReference("screen_time")
                    .child(childId)
                    .child("daily")
                    .child(dateKey)

            // =================================================
            // BUILD BUCKET UPDATE
            // =================================================

            val updates =
                HashMap<String, Any>()

            bucketData.forEach { (bucketKey, seconds) ->

                updates[bucketKey] =
                    seconds
            }

            // =================================================
            // WRITE BUCKETS
            //
            // SET/UPDATE, NOT INCREMENT.
            //
            // This means WorkManager can run repeatedly without
            // double-counting the same usage.
            // =================================================

            Tasks.await(
                dailyRef.updateChildren(
                    updates
                ),
                FIREBASE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            Log.d(
                TAG,
                "✅ Screen-time buckets written"
            )

            // =================================================
            // DAILY TOTAL
            //
            // IMPORTANT:
            //
            // SET, NOT INCREMENT.
            // =================================================

            val totalRef =
                database
                    .getReference("child_usage")
                    .child(childId)
                    .child("daily_stats")
                    .child(dateKey)
                    .child("totalScreenTime")

            Tasks.await(
                totalRef.setValue(
                    totalSeconds
                ),
                FIREBASE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            Log.d(
                TAG,
                "✅ Daily total written"
            )

            Log.d(
                TAG,
                "Date = $dateKey"
            )

            Log.d(
                TAG,
                "Total = $totalSeconds seconds"
            )

            Log.d(
                TAG,
                "Total = ${totalSeconds / 60L} minutes"
            )

            true

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Firebase screen-time write failed",
                e
            )

            false
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
    // FLOOR TIMESTAMP TO FIVE MINUTES
    // =========================================================

    private fun floorToFiveMinutes(
        timestamp: Long
    ): Long {

        val calendar =
            Calendar.getInstance()

        calendar.timeInMillis =
            timestamp

        val minute =
            calendar.get(
                Calendar.MINUTE
            )

        val bucketMinute =
            (minute / 5) * 5

        calendar.set(
            Calendar.MINUTE,
            bucketMinute
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
    // FORMAT BUCKET
    // =========================================================

    private fun formatBucketTime(
        timestamp: Long
    ): String {

        return SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).format(
            Date(timestamp)
        )
    }

    // =========================================================
    // DEBUG DATE/TIME
    // =========================================================

    private fun formatDateTime(
        timestamp: Long
    ): String {

        return SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(
            Date(timestamp)
        )
    }
}