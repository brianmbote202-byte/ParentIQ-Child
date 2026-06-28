package com.parentalcontrol.childapp.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

class AppUsageResetWorker(
    context: Context,
    workerParams: WorkerParameters
) : Worker(context, workerParams) {

    override fun doWork(): Result {
        val childId: String? = inputData.getString("childId")

        if (childId.isNullOrEmpty()) {
            Log.e(TAG, "No childId provided to reset screen usage")
            return Result.failure()
        }

        resetFirebaseUsage(childId)
        resetLocalUsage()

        return Result.success()
    }

    private fun resetFirebaseUsage(childId: String) {
        try {
            val ref = FirebaseDatabase.getInstance().getReference("screen_usage").child(childId)
            val resetData = mapOf(
                "childId" to childId,
                "usedTodayMillis" to 0L,
                "timestamp" to System.currentTimeMillis()
            )

            ref.setValue(resetData)
                .addOnSuccessListener {
                    Log.d(TAG, "Firebase usage reset successfully for childId: $childId")
                }
                .addOnFailureListener { ex ->
                    Log.e(TAG, "Failed to reset Firebase usage for childId: $childId", ex)
                }
        } catch (ex: Exception) {
            Log.e(TAG, "Exception resetting Firebase usage for childId: $childId", ex)
        }
    }

    private fun resetLocalUsage() {
        try {
            val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putLong(KEY_USED_TODAY, 0L).apply()
            Log.d(TAG, "Local screen usage reset successfully")
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to reset local screen usage", ex)
        }
    }

    companion object {
        private const val TAG = "AppUsageResetWorker"
        private const val PREFS_NAME = "screen_usage"
        private const val KEY_USED_TODAY = "used_today"
    }
}
