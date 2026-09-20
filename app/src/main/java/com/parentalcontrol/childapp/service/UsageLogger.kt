package com.parentalcontrol.childapp.service

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class UsageLogger(
    private val context: Context,
    private val childId: String
) {

    companion object {

        private const val TAG = "UsageLogger"

        private const val SESSION_TAG =
            "SESSION_WRITER"
    }

    // =========================================================
    // SCREEN-TIME INTERVAL
    //
    // Represents real foreground usage:
    //
    // start -> end
    //
    // These intervals are used by UsageLoggerWorker to calculate
    // total device screen time.
    //
    // IMPORTANT:
    // UsageLogger does NOT calculate the final screen-time total.
    // The Worker merges intervals from all tracked apps.
    // =========================================================

    data class ScreenTimeInterval(
        val start: Long,
        val end: Long
    )

    // =========================================================
    // DATE
    // =========================================================

    private fun getTodayKey(): String {

        return SimpleDateFormat(
            "yyyy-MM-dd",
            Locale.getDefault()
        ).format(
            Date()
        )
    }

    // =========================================================
    // START
    //
    // Kept for compatibility with existing code.
    //
    // There is no timer or screen-time buffer here.
    // =========================================================

    fun start() {

        Log.d(
            TAG,
            "UsageLogger started for child=$childId"
        )
    }

    // =========================================================
    // STOP
    //
    // Kept for compatibility.
    // =========================================================

    fun stop() {

        Log.d(
            TAG,
            "UsageLogger stopped"
        )
    }

    // =========================================================
    // GET SCREEN-TIME INTERVALS
    //
    // IMPORTANT:
    //
    // This method provides the API expected by the Worker.
    //
    // Screen time means:
    //
    // "Time during which one of the tracked applications was
    // actually in the foreground."
    //
    // This class does NOT add intervals from different apps.
    //
    // The Worker collects intervals from every tracked app and
    // merges overlapping intervals.
    //
    // Example:
    //
    // Chrome:
    // 10:00 -> 10:10
    //
    // YouTube:
    // 10:05 -> 10:15
    //
    // This method returns both intervals.
    //
    // Worker merges them into:
    //
    // 10:00 -> 10:15
    //
    // Therefore screen time = 15 minutes, not 20.
    // =========================================================

    fun getScreenTimeIntervals(
        appPackage: String
    ): List<ScreenTimeInterval> {

        return try {

            val tracker =
                AppUsageTracker(
                    context
                )

            val sessions =
                tracker.getAppSessions(
                    appPackage
                )

            val intervals =
                sessions.mapNotNull { session ->

                    val start =
                        session["from"]

                    val end =
                        session["to"]

                    if (
                        start == null ||
                        end == null ||
                        end <= start
                    ) {
                        null
                    } else {

                        ScreenTimeInterval(
                            start = start,
                            end = end
                        )
                    }
                }

            Log.d(
                TAG,
                "Screen-time intervals for " +
                        "$appPackage = ${intervals.size}"
            )

            intervals

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to get screen-time intervals for " +
                        appPackage,
                e
            )

            emptyList()
        }
    }

    // =========================================================
    // APP USAGE
    //
    // Retained for compatibility with older code.
    //
    // DO NOT use this to calculate the global screen-time total.
    // =========================================================

    @Deprecated(
        message =
            "Screen time is calculated by UsageLoggerWorker. " +
                    "Use getScreenTimeIntervals() if intervals " +
                    "are required.",
        level = DeprecationLevel.WARNING
    )
    fun logAppUsage(
        appPackage: String,
        usedSeconds: Int
    ) {

        Log.w(
            TAG,
            "Ignoring logAppUsage() for $appPackage " +
                    "($usedSeconds sec). " +
                    "Screen time is calculated by UsageLoggerWorker."
        )
    }

    // =========================================================
    // FLUSH
    //
    // Retained for compatibility.
    // =========================================================

    @Deprecated(
        message =
            "Screen-time buffering has been removed. " +
                    "UsageLoggerWorker writes screen time directly.",
        level = DeprecationLevel.WARNING
    )
    fun flushToFirebase() {

        Log.d(
            TAG,
            "flushToFirebase() ignored. " +
                    "Worker owns screen-time writes."
        )
    }

    // =========================================================
    // DAILY SCREEN TIME
    //
    // Retained for compatibility.
    //
    // The Worker calculates the complete daily total and SETS
    // it instead of incrementing it.
    // =========================================================

    @Deprecated(
        message =
            "Daily screen time is now calculated by UsageLoggerWorker.",
        level = DeprecationLevel.WARNING
    )
    fun updateDailyScreenTime(
        incrementSeconds: Int
    ) {

        Log.w(
            TAG,
            "Ignoring updateDailyScreenTime($incrementSeconds). " +
                    "UsageLoggerWorker owns the daily total."
        )
    }

    // =========================================================
    // APP SESSIONS
    //
    // Firebase:
    //
    // child_usage
    //   └── childId
    //       └── app_sessions
    //           └── yyyy-MM-dd
    //               └── package_name
    //                   └── session_start
    //
    // This stores individual application sessions.
    //
    // These sessions are separate from the global screen-time
    // calculation.
    // =========================================================

    fun logAppSessions(
        appPackage: String,
        sessions: List<Map<String, Long>>
    ) {

        if (sessions.isEmpty()) {

            Log.d(
                SESSION_TAG,
                "No sessions for $appPackage"
            )

            return
        }

        Log.d(
            SESSION_TAG,
            "=================================================="
        )

        Log.d(
            SESSION_TAG,
            "Saving app sessions"
        )

        Log.d(
            SESSION_TAG,
            "Child   = $childId"
        )

        Log.d(
            SESSION_TAG,
            "Package = $appPackage"
        )

        Log.d(
            SESSION_TAG,
            "Count   = ${sessions.size}"
        )

        Log.d(
            SESSION_TAG,
            "=================================================="
        )

        val dateKey =
            getTodayKey()

        // =====================================================
        // FIREBASE-SAFE PACKAGE NAME
        // =====================================================

        val safePackage =
            appPackage.replace(
                ".",
                "_"
            )

        val ref =
            FirebaseDatabase
                .getInstance()
                .getReference("child_usage")
                .child(childId)
                .child("app_sessions")
                .child(dateKey)
                .child(safePackage)

        // =====================================================
        // SAVE EACH SESSION
        // =====================================================

        sessions.forEach { session ->

            val from =
                session["from"]

            val to =
                session["to"]

            val durationSeconds =
                session["durationSeconds"]

            if (
                from == null ||
                to == null ||
                durationSeconds == null
            ) {

                Log.w(
                    SESSION_TAG,
                    "Skipping malformed session -> $session"
                )

                return@forEach
            }

            if (to <= from) {

                Log.w(
                    SESSION_TAG,
                    "Skipping invalid session -> " +
                            "$from -> $to"
                )

                return@forEach
            }

            if (durationSeconds <= 0) {

                Log.w(
                    SESSION_TAG,
                    "Skipping zero-duration session -> " +
                            "$session"
                )

                return@forEach
            }

            // =================================================
            // SESSION KEY
            //
            // Using the foreground start timestamp means that
            // repeated Worker executions update the same session
            // instead of creating another session.
            // =================================================

            val sessionRef =
                ref.child(
                    from.toString()
                )

            sessionRef
                .setValue(
                    mapOf(
                        "from" to from,
                        "to" to to,
                        "durationSeconds" to durationSeconds
                    )
                )
                .addOnSuccessListener {

                    Log.d(
                        SESSION_TAG,
                        "✅ Session saved -> " +
                                "$appPackage / " +
                                "$from -> $to / " +
                                "${durationSeconds}s"
                    )
                }
                .addOnFailureListener { error ->

                    Log.e(
                        SESSION_TAG,
                        "❌ Session save failed -> " +
                                "$appPackage / $from",
                        error
                    )
                }
        }
    }
}