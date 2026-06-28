package com.parentalcontrol.childapp.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.firebase.database.FirebaseDatabase

class UsageUploadWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {

        val childId = inputData.getString("childId") ?: return Result.failure()
        val usedTodayMillis = inputData.getLong("usedTodayMillis", 0L)
        val timestamp = inputData.getLong("timestamp", System.currentTimeMillis())

        val data = mapOf(
            "childId" to childId,
            "usedTodayMillis" to usedTodayMillis,
            "timestamp" to timestamp
        )

        return try {
            FirebaseDatabase.getInstance()
                .getReference("screen_usage")
                .child(childId)
                .setValue(data)

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
