package com.parentalcontrol.childapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.ActivityRecognitionResult
import com.google.android.gms.location.DetectedActivity

class ActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityRecognitionResult.hasResult(intent)) return

        val result = ActivityRecognitionResult.extractResult(intent)
        if (result == null) {
            Log.w("ActivityReceiver", "No ActivityRecognitionResult found")
            return
        }

        val activity = result.mostProbableActivity
        val activityName = when (activity.type) {
            DetectedActivity.STILL -> "STILL"
            DetectedActivity.WALKING -> "ON_FOOT"
            DetectedActivity.RUNNING -> "RUNNING"
            DetectedActivity.IN_VEHICLE -> "IN_VEHICLE"
            DetectedActivity.ON_BICYCLE -> "ON_BICYCLE"
            else -> "UNKNOWN"
        }

        Log.d("ACTIVITY_UPDATE", "Detected activity: $activityName (${activity.confidence}%)")

        // Save last detected activity (used for adaptive frequency)
        val prefs = context.getSharedPreferences("activity_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("last_activity", activityName).apply()
    }
}
