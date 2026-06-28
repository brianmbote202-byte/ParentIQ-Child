package com.parentalcontrol.childapp.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import java.text.SimpleDateFormat
import java.util.*

class UsageLogger(
    private val context: Context,
    private val childId: String
) {

    companion object {
        private const val TAG = "UsageLogger"
    }

    // =========================================================
    // BUFFER
    // stores app usage in SECONDS
    // =========================================================

    private val usageBuffer =
        mutableMapOf<String, Int>()

    private val handler =
        Handler(Looper.getMainLooper())

    // =========================================================
    // AUTO FLUSH EVERY 2 MIN
    // =========================================================

    private val flushRunnable =
        object : Runnable {

            override fun run() {

                flushToFirebase()

                handler.postDelayed(
                    this,
                    2 * 60 * 1000
                )
            }
        }

    // =========================================================
    // START LOGGER
    // =========================================================

    fun start() {

        Log.d(TAG, "UsageLogger started")

        handler.post(flushRunnable)
    }

    // =========================================================
    // STOP LOGGER
    // =========================================================

    fun stop() {

        Log.d(TAG, "UsageLogger stopped")

        handler.removeCallbacks(flushRunnable)

        handler.removeCallbacksAndMessages(null)

        usageBuffer.clear()
    }

    // =========================================================
    // LOG APP USAGE
    // =========================================================

    fun logAppUsage(
        appPackage: String,
        usedSeconds: Int
    ) {

        if (usedSeconds <= 0) return

        val key =
            appPackage.replace(".", "_")

        val current =
            usageBuffer[key] ?: 0

        usageBuffer[key] =
            current + usedSeconds

        Log.d(
            TAG,
            "Buffered -> $appPackage : $usedSeconds sec"
        )
    }

    // =========================================================
    // FLUSH TO FIREBASE
    // =========================================================

    fun flushToFirebase() {

        if (usageBuffer.isEmpty()) {

            Log.d(TAG, "No usage to flush")

            return
        }

        val calendar =
            Calendar.getInstance()

        val hour =
            calendar.get(Calendar.HOUR_OF_DAY)

        val dateKey =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(Date())

        // =====================================================
        // TOTAL SCREEN TIME
        // =====================================================

        val totalSeconds =
            usageBuffer.values.sum()

        // =====================================================
        // SAVE HOURLY SCREEN TIME
        // screen_time/{childId}/daily/{date}/{hour}
        // =====================================================

        val hourlyRef =
            FirebaseDatabase.getInstance()
                .getReference("screen_time")
                .child(childId)
                .child("daily")
                .child(dateKey)
                .child(hour.toString())

        hourlyRef.setValue(
            ServerValue.increment(
                totalSeconds.toLong()
            )
        ).addOnSuccessListener {

            Log.d(
                TAG,
                "Hourly screen time saved successfully"
            )
        }.addOnFailureListener {

            Log.e(
                TAG,
                "Failed saving hourly screen time"
            )
        }

        // =====================================================
        // SAVE DAILY TOTAL
        // child_usage/{childId}/daily_stats/{date}
        // =====================================================

        val dailyRef =
            FirebaseDatabase.getInstance()
                .getReference("child_usage")
                .child(childId)
                .child("daily_stats")
                .child(dateKey)
                .child("totalScreenTime")

        dailyRef.setValue(
            ServerValue.increment(
                totalSeconds.toLong()
            )
        )

        Log.d(
            TAG,
            "Saved daily total: $totalSeconds sec"
        )

        // =====================================================
        // CLEAR BUFFER
        // =====================================================

        usageBuffer.clear()
    }

    // =========================================================
    // OPTIONAL:
    // FORCE DAILY UPDATE
    // =========================================================

    fun updateDailyScreenTime(
        incrementSeconds: Int
    ) {

        if (incrementSeconds <= 0) return

        val dateKey =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(Date())

        val ref =
            FirebaseDatabase.getInstance()
                .getReference("child_usage")
                .child(childId)
                .child("daily_stats")
                .child(dateKey)
                .child("totalScreenTime")

        ref.setValue(
            ServerValue.increment(
                incrementSeconds.toLong()
            )
        )
    }

    // =========================================================
    // OPTIONAL:
    // APP SESSIONS
    // =========================================================

    fun logAppSessions(
        appPackage: String,
        sessions: List<Map<String, Long>>
    ) {

        Log.d(
            "SESSION_WRITER",
            "Package=$appPackage SessionCount=${sessions.size}"
        )

        sessions.take(3).forEach {
            Log.d(
                "SESSION_WRITER",
                "Session=$it"
            )
        }

        if (sessions.isEmpty()) return

        val dateKey =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            ).format(Date())

        val ref =
            FirebaseDatabase.getInstance()
                .getReference("child_usage")
                .child(childId)
                .child("app_sessions")
                .child(dateKey)
                .child(appPackage.replace(".", "_"))

        sessions.forEach { session ->

            val from =
                session["from"] ?: return@forEach

            ref.child(from.toString())
                .setValue(session)
        }
    }
}