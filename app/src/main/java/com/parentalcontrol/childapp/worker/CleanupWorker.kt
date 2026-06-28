package com.parentalcontrol.childapp.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

class CleanupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    private val cutoffTime: Long = System.currentTimeMillis() - 24 * 60 * 60 * 1000 // 24 hours

    override fun doWork(): Result {
        return try {
            val nodesToClean = listOf("activity_logs", "geofence_events", "app_status")
            nodesToClean.forEach { cleanNodePerChild(it) }

            Log.d(TAG, "✅ Cleanup job triggered successfully")
            Result.success()
        } catch (ex: Exception) {
            Log.e(TAG, "❌ Cleanup failed: ${ex.message}", ex)
            Result.retry()
        }
    }

    private fun cleanNodePerChild(node: String) {
        val ref = FirebaseDatabase.getInstance().getReference(node)
        ref.get().addOnSuccessListener { snapshot ->
            if (!snapshot.exists()) return@addOnSuccessListener

            snapshot.children.forEach { childSnap ->
                val childId = childSnap.key ?: return@forEach
                childSnap.ref.orderByChild("timestamp").endAt(cutoffTime.toDouble()).get()
                    .addOnSuccessListener { logsSnap ->
                        var deleted = 0
                        logsSnap.children.forEach { logSnap ->
                            logSnap.ref.removeValue()
                                .addOnSuccessListener { deleted++ }
                                .addOnFailureListener { e -> Log.e(TAG, "Failed to delete ${logSnap.key}: ${e.message}") }
                        }
                        Log.d(TAG, "Deleted $deleted old entries from $node/$childId")
                    }
                    .addOnFailureListener { e -> Log.e(TAG, "Failed to fetch logs for $node/$childId: ${e.message}") }
            }
        }.addOnFailureListener { e -> Log.e(TAG, "Failed to fetch node $node: ${e.message}") }
    }

    companion object {
        private const val TAG = "CleanupWorker"
    }
}
