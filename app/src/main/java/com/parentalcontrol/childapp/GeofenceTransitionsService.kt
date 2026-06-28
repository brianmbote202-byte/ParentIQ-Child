package com.parentalcontrol.childapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.JobIntentService
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.database.FirebaseDatabase

class GeofenceTransitionsService : JobIntentService() {

    override fun onHandleWork(intent: Intent) {
        val geofencingEvent = GeofencingEvent.fromIntent(intent) ?: return
        if (geofencingEvent.hasError()) return

        val transitionType = geofencingEvent.geofenceTransition
        val triggeringGeofences = geofencingEvent.triggeringGeofences ?: return

        for (geofence in triggeringGeofences) {
            val transition = when (transitionType) {
                Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTERED"
                Geofence.GEOFENCE_TRANSITION_EXIT -> "EXITED"
                else -> "UNKNOWN"
            }

            logToFirebase(geofence.requestId, transition)
            showNotification(geofence.requestId, transition)
        }
    }

    private fun logToFirebase(zoneId: String, transition: String) {
        val ref = FirebaseDatabase.getInstance()
            .getReference("geofence_events")
            .push()

        ref.setValue(
            mapOf(
                "zoneId" to zoneId,
                "transition" to transition,
                "timestamp" to System.currentTimeMillis()
            )
        )
    }

    private fun showNotification(zoneId: String, transition: String) {
        val channelId = "geofence_channel"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Geofence Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Child geofence events"
                enableLights(true)
                lightColor = Color.RED
            }
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_location)
            .setContentTitle("Geofence $transition")
            .setContentText("Child has $transition zone: $zoneId")
            .setAutoCancel(true)
            .build()

        manager.notify(zoneId.hashCode(), notification)
    }

    companion object {
        private const val JOB_ID = 9001

        fun enqueueWork(context: Context, intent: Intent) {
            enqueueWork(
                context,
                GeofenceTransitionsService::class.java,
                JOB_ID,
                intent
            )
        }
    }
}
