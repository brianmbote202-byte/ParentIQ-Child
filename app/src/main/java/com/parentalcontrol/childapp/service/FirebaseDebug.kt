package com.parentalcontrol.childapp.debug

import android.content.Context
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

object FirebaseDebug {

    fun testAppUsageUpload(context: Context, childId: String) {
        val db = FirebaseDatabase.getInstance().reference
        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            .format(java.util.Date())
        val testAppKey = "com_whatsapp"

        val data = mapOf(
            "usedMinutes" to 5,
            "remainingMinutes" to 55,
            "dailyLimit" to 60,
            "lastChecked" to System.currentTimeMillis()
        )

        db.child("children")
            .child(childId)
            .child("app_usage")
            .child(date)
            .child(testAppKey)
            .setValue(data)
            .addOnSuccessListener {
                Log.d("FIREBASE_TEST", "✅ Firebase write successful: $data")
            }
            .addOnFailureListener { e ->
                Log.e("FIREBASE_TEST", "❌ Firebase write failed: ${e.message}")
            }
    }
}