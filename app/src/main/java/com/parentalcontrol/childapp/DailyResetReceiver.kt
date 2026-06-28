package com.parentalcontrol.childapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class DailyResetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Daily reset triggered")

        // Reset daily screen usage for all children
        val ref = FirebaseDatabase.getInstance().getReference("screen_usage")
        ref.get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { childSnap ->
                val childId = childSnap.key ?: return@forEach
                childSnap.ref.child("usedTodayMillis").setValue(0)
                    .addOnSuccessListener { Log.d(TAG, "Reset screen usage for $childId") }
                    .addOnFailureListener { e -> Log.e(TAG, "Failed to reset $childId: ${e.message}") }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to fetch screen usage nodes: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DailyResetReceiver"
    }
}
