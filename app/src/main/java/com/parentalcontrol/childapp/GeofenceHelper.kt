package com.parentalcontrol.childapp

import com.parentalcontrol.childapp.model.GeofenceReceiver


import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices

class GeofenceHelper(private val context: Context) {

    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(context)

    @SuppressLint("MissingPermission")
    fun addGeofence(latitude: Double, longitude: Double, radius: Float = 200f) {

        val geofence = Geofence.Builder()
            .setRequestId("child_home_zone")
            .setCircularRegion(latitude, longitude, radius)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(
                Geofence.GEOFENCE_TRANSITION_ENTER or
                        Geofence.GEOFENCE_TRANSITION_EXIT
            )
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val intent = Intent(context, GeofenceReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        geofencingClient.addGeofences(request, pendingIntent)
            .addOnSuccessListener {
                Log.d("GEOFENCE", "✅ Geofence added successfully")
            }
            .addOnFailureListener { e ->
                Log.e("GEOFENCE", "❌ Failed to add geofence: ${e.message}")
            }
    }
}
