package com.parentalcontrol.childapp.model

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.firebase.database.FirebaseDatabase

class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {

        val event = GeofencingEvent.fromIntent(intent) ?: return

        if (event.hasError()) {
            Log.e("GEOFENCE", "Error: ${event.errorCode}")
            return
        }

        val triggered = event.triggeringGeofences ?: emptyList()
        if (triggered.isEmpty()) return

        val transition = event.geofenceTransition
        val type = when (transition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "ENTER"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "EXIT"
            else -> "UNKNOWN"
        }

        val zoneName = triggered.first().requestId

        // Toast
        Toast.makeText(context, "$type → $zoneName", Toast.LENGTH_LONG).show()

        // Notification
        sendNotification(context, type, zoneName)

        // Firebase Save
        saveToFirebase(type, zoneName)
    }

    private fun sendNotification(context: Context, type: String, zone: String) {
        val channelId = "geofence_alerts"
        val manager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Geofence Alerts",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("Geofence: $type")
            .setContentText("Zone: $zone")
            .setSmallIcon(R.drawable.ic_dialog_map)
            .setAutoCancel(true)
            .build()

        manager.notify((0..10000).random(), notification)
    }

    private fun saveToFirebase(type: String, zone: String) {
        val ref = FirebaseDatabase.getInstance().getReference("geofence_events")

        val data = mapOf(
            "type" to type,
            "zone" to zone,
            "timestamp" to System.currentTimeMillis()
        )

        ref.push().setValue(data)
    }
}