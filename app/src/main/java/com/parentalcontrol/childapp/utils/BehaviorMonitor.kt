package com.parentalcontrol.childapp.utils

import android.util.Log

object BehaviorMonitor {

    private const val TAG = "BehaviorMonitor"

    // Time window to analyze behavior
    private const val WINDOW_MS = 2 * 60 * 1000L   // 2 minutes

    // Prevent alert spam
    private const val ALERT_COOLDOWN = 60 * 1000L  // 1 minute

    private val recentEvents = mutableListOf<Event>()

    private var lastAlertTime = 0L

    data class Event(
        val category: String,
        val timestamp: Long
    )

    data class BehaviorAlert(
        val type: String,
        val severity: String,
        val count: Int
    )

    fun recordEvent(category: String): BehaviorAlert? {

        val now = System.currentTimeMillis()

        // Add new event
        recentEvents.add(Event(category, now))

        // Remove old events outside window
        recentEvents.removeAll { now - it.timestamp > WINDOW_MS }

        val adultCount = recentEvents.count { it.category == "adult" }
        val gamblingCount = recentEvents.count { it.category == "gambling" }
        val violenceCount = recentEvents.count { it.category == "violence" }

        val sexualCount = recentEvents.count { it.category == "sexual" }

        // Prevent alert spam
        if (now - lastAlertTime < ALERT_COOLDOWN) {
            return null
        }

        // 🚨 Adult session detection
        if (adultCount >= 3) {
            lastAlertTime = now

            Log.d(TAG, "Adult browsing session detected")

            return BehaviorAlert(
                type = "adult_session",
                severity = "critical",
                count = adultCount
            )
        }

        // 🎰 Gambling pattern
        if (gamblingCount >= 4) {
            lastAlertTime = now

            Log.d(TAG, "Gambling behavior detected")

            return BehaviorAlert(
                type = "gambling_session",
                severity = "high",
                count = gamblingCount
            )
        }

        // 🔫 Violence pattern
        if (violenceCount >= 4) {
            lastAlertTime = now

            Log.d(TAG, "Violence pattern detected")

            return BehaviorAlert(
                type = "violence_pattern",
                severity = "medium",
                count = violenceCount
            )
        }

        // ⚠️ Escalation pattern (sexual → adult)
        if (sexualCount >= 2 && adultCount >= 1) {
            lastAlertTime = now

            Log.d(TAG, "Escalation detected: sexual → adult")

            return BehaviorAlert(
                type = "content_escalation",
                severity = "high",
                count = sexualCount + adultCount
            )
        }

        return null
    }
}