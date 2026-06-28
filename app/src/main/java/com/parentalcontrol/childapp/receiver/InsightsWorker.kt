package com.parentalcontrol.childapp.receiver

import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.content.Context
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.service.AiInsightGenerator

class InsightWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {

        Log.e("INSIGHT_TEST", "WORKER ENTERED")

        val childId = inputData.getString("childId")

        Log.e("INSIGHT_TEST", "childId = $childId")

        if (childId == null) {
            Log.e("INSIGHT_TEST", "childId is NULL")
            return Result.failure()
        }

        FirebaseDatabase.getInstance()
            .reference
            .child("worker_test")
            .setValue(System.currentTimeMillis())

        Log.e("INSIGHT_TEST", "Before generator")

        try {

            val generator = AiInsightGenerator(childId)

            generator.generateDailyInsights()

            Log.e("INSIGHT_TEST", "After generator")

        } catch (e: Exception) {

            Log.e(
                "INSIGHT_TEST",
                "Generator crashed",
                e
            )
        }

        return Result.success()
    }
}