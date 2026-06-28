package com.parentalcontrol.childapp.service

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.parentalcontrol.childapp.receiver.InsightWorker
import java.util.concurrent.TimeUnit

object InsightScheduler {

    fun scheduleInsights(context: Context, childId: String) {

        val inputData = workDataOf(
            "childId" to childId
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<InsightWorker>(
            24, TimeUnit.HOURS
        )
            .setInputData(inputData)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(
                "daily_insights_$childId",
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
    }
}