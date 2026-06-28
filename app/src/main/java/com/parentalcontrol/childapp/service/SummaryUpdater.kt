package com.parentalcontrol.childapp

import com.google.firebase.database.FirebaseDatabase

class SummaryUpdater(private val childId: String) {

    private val db = FirebaseDatabase.getInstance().reference

    // Call this to push all daily stats at once
    fun pushDailySummary(
        name: String,
        battery: Int,
        online: Boolean,
        location: String,
        screenTimeMinutes: Int,
        alertsToday: Int
    ) {
        val summaryRef = db.child("daily_stats").child(childId)

        val data = mapOf(
            "name" to name,
            "battery" to battery,
            "online" to online,
            "location" to location,
            "screenTimeToday" to screenTimeMinutes,
            "alertsToday" to alertsToday
        )

        summaryRef.setValue(data)
            .addOnSuccessListener {
                // Optional: log success
                println("Daily summary pushed successfully")
            }
            .addOnFailureListener { e ->
                println("Error pushing daily summary: ${e.message}")
            }
    }
}