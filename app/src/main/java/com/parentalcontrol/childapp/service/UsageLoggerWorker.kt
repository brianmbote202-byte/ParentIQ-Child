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
        // GET CHILD ID
        // =====================================================

        val childId =
            inputData.getString("childId")
                ?: return Result.failure()

        // =====================================================
        // GET TRACKED APPS FROM SHARED PREFERENCES
        // =====================================================

        val prefs =
            applicationContext.getSharedPreferences(
                "usage_tracker",
                Context.MODE_PRIVATE
            )

        val appPackages =
            prefs.getStringSet(
                "tracked_apps",
                emptySet()
            )?.toTypedArray()
                ?: emptyArray()

        Log.d(
            TAG,
            "Tracking ${appPackages.size} installed apps"
        )

        if (appPackages.isEmpty()) {

            Log.e(TAG, "No tracked apps found.")

            return Result.retry()
        }

        // =====================================================
        // INITIALIZE
        // =====================================================

        val usageTracker =
            AppUsageTracker(applicationContext)

        val usageLogger =
            UsageLogger(applicationContext, childId)

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
        // PROCESS EVERY INSTALLED APP
        // =====================================================

        appPackages.forEach { appPackage ->

            try {

                val currentMinutes =
                    usageTracker.getUsedMinutes(appPackage).toInt()

                val lastMinutes =
                    prefs.getInt(appPackage, 0)

                val deltaMinutes =
                    if (currentMinutes >= lastMinutes)
                        currentMinutes - lastMinutes
                    else
                        currentMinutes

                prefs.edit()
                    .putInt(appPackage, currentMinutes)
                    .apply()

                val usedSeconds =
                    deltaMinutes * 60

                val sessions =
                    usageTracker.getAppSessions(appPackage)

                if (
                    usedSeconds <= 0 &&
                    sessions.isEmpty()
                ) {
                    return@forEach
                }

                if (usedSeconds > 0) {

                    usageLogger.logAppUsage(
                        appPackage,
                        usedSeconds
                    )

                    totalSecondsToday += usedSeconds
                }

                if (sessions.isNotEmpty()) {

                    usageLogger.logAppSessions(
                        appPackage,
                        sessions
                    )
                }

                Log.d(
                    TAG,
                    "$appPackage -> ${usedSeconds}s"
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
        // FLUSH TO FIREBASE
        // =====================================================

        usageLogger.flushToFirebase()

        if (totalSecondsToday > 0) {

            usageLogger.updateDailyScreenTime(
                totalSecondsToday
            )
        }

        Log.d(TAG, "Worker completed successfully")

        return Result.success()
    }
}