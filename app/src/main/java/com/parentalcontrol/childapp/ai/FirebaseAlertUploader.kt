package com.parentalcontrol.childapp.ai

import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object FirebaseAlertUploader {

    private const val TAG = "FirebaseAlertUploader"

    /**
     * Upload AI or browsing alert to Firebase.
     *
     * @param childId child node id
     * @param type alert type (adult_session, gambling, etc.)
     * @param level severity (CRITICAL, HIGH, etc.)
     * @param score risk score 0..1
     * @param categories risk categories
     */
    fun uploadAlert(
        childId: String,
        type: String,
        level: String,
        score: Float,
        categories: Set<String>
    ) {

        val data = mapOf(
            "type" to type,
            "level" to level,
            "riskScore" to score,
            "categories" to categories.toList(),
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance()
            .getReference("children")
            .child(childId)
            .child("ai_alerts")
            .push()
            .setValue(data)
            .addOnSuccessListener {
                Log.d(TAG, "alert uploaded: $type")
            }
            .addOnFailureListener {
                Log.e(TAG, "upload failed", it)
            }
    }
}