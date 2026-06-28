package com.parentalcontrol.childapp.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

class UsageLoggerWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    companion object {
        private const val TAG = "USAGE_WORKER"
    }

    override fun doWork(): Result {

        Log.d(TAG, "Worker started")

        FirebaseDatabase.getInstance()
            .reference
            .child("worker_test")
            .setValue(System.currentTimeMillis())

        // =====================================================
        // GET INPUTS
        // =====================================================

        val childId =
            inputData.getString("childId")
                ?: return Result.failure()

        val appPackages =
            inputData.getStringArray("trackedApps")
                ?: return Result.failure()

        // =====================================================
        // INITIALIZE
        // =====================================================

        val usageTracker =
            AppUsageTracker(applicationContext)

        val usageLogger =
            UsageLogger(applicationContext, childId)

        val prefs =
            applicationContext.getSharedPreferences(
                "usage_tracker",
                Context.MODE_PRIVATE
            )

        // =====================================================
        // CHECK PERMISSION
        // =====================================================

        if (!usageTracker.hasUsageStatsPermission()) {

            Log.e(TAG, "Usage permission missing")

            usageTracker.requestUsageStatsPermission()

            return Result.failure()
        }

        // =====================================================
        // TRACK TOTAL SCREEN TIME
        // =====================================================

        var totalSecondsToday = 0

        // =====================================================
        // PROCESS EACH APP
        // =====================================================

        appPackages.forEach { appPackage ->

            try {

                // =============================================
                // CURRENT TOTAL USAGE TODAY
                // =============================================

                val currentMinutes =
                    usageTracker
                        .getUsedMinutes(appPackage)
                        .toInt()

                // =============================================
                // PREVIOUSLY RECORDED VALUE
                // =============================================

                val lastMinutes =
                    prefs.getInt(appPackage, 0)

                // =============================================
                // DELTA SINCE LAST WORKER RUN
                // =============================================

                val deltaMinutes =
                    if (currentMinutes >= lastMinutes) {
                        currentMinutes - lastMinutes
                    } else {
                        // Day rolled over
                        currentMinutes
                    }

                prefs.edit()
                    .putInt(appPackage, currentMinutes)
                    .apply()

                val usedSeconds =
                    deltaMinutes * 60

                Log.d(
                    TAG,
                    "$appPackage current=$currentMinutes last=$lastMinutes delta=$deltaMinutes"
                )

                // =============================================
                // GET APP SESSIONS
                // =============================================

                val sessions =
                    usageTracker.getAppSessions(appPackage)

                // =============================================
                // SKIP EMPTY APPS
                // =============================================

                if (
                    usedSeconds <= 0 &&
                    sessions.isEmpty()
                ) {

                    Log.d(
                        TAG,
                        "Skipping $appPackage (no new usage)"
                    )

                    return@forEach
                }

                // =============================================
                // SAVE USAGE
                // =============================================

                if (usedSeconds > 0) {

                    usageLogger.logAppUsage(
                        appPackage,
                        usedSeconds
                    )

                    totalSecondsToday += usedSeconds
                }

                // =============================================
                // SAVE SESSIONS
                // =============================================

                if (sessions.isNotEmpty()) {

                    usageLogger.logAppSessions(
                        appPackage,
                        sessions
                    )
                }

                Log.d(
                    TAG,
                    "$appPackage -> $usedSeconds sec"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Error processing $appPackage",
                    e
                )
            }
        }

        // =====================================================
        // FLUSH ONCE
        // =====================================================

        usageLogger.flushToFirebase()

        // =====================================================
        // SAVE DAILY TOTAL
        // =====================================================

        if (totalSecondsToday > 0) {

            usageLogger.updateDailyScreenTime(
                totalSecondsToday
            )

            Log.d(
                TAG,
                "Daily total saved = $totalSecondsToday sec"
            )
        }

        Log.d(TAG, "Worker completed successfully")

        return Result.success()
    }
}