package com.parentalcontrol.childapp.geofence

import android.R.attr.radius
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class GeofenceManager {

    companion object {
        private const val TAG = "GeofenceManager"
    }

    // Prevent duplicate enter/exit alerts
    private val geofenceStates =
        mutableMapOf<String, Boolean>()

    // Active sessions
    private val activeGeofenceSessions =
        mutableMapOf<String, String>()

    //======geofence handler======
    private val geofenceHandler = Handler(Looper.getMainLooper())

    //========track pending events=======
    private val pendingGeofenceTasks =
        mutableMapOf<String, Runnable>()

    // =====================================================
    // CHECK GEOFENCES
    // =====================================================

    fun checkGeofences(

        childId: String,

        currentLat: Double,

        currentLng: Double
    ) {

        Log.d(
            "GeofenceManager",
            "Checking geofences for $childId"
        )

        FirebaseDatabase.getInstance()
            .getReference("geofences")
            .child(childId)

            .get()

            .addOnSuccessListener { snapshot ->

                snapshot.children.forEach { snap ->

                    Log.d(
                        "GeofenceManager",
                        "Loaded ${snapshot.childrenCount} geofences"
                    )


                    val latitude =
                        snap.child("latitude")
                            .getValue(Double::class.java)
                            ?: 0.0

                    val longitude =
                        snap.child("longitude")
                            .getValue(Double::class.java)
                            ?: 0.0

                    val radius =
                        snap.child("radius")
                            .getValue(Int::class.java)
                            ?: 0

                    val name =
                        snap.child("name")
                            .getValue(String::class.java)
                            ?: "Unknown"

                    val inside = isInsideGeofence(

                        currentLat,
                        currentLng,

                        latitude,
                        longitude,

                        radius
                    )

                    val distanceMeters = calculateDistance(
                        currentLat,
                        currentLng,
                        latitude,
                        longitude
                    )

                    val geofenceKey =
                        snap.key ?: name

                    val previousState =
                        geofenceStates[geofenceKey]

                    // ENTERED
                    if (inside && previousState != true) {

                        val geofenceKey = snap.key ?: name

                        // cancel any previous pending task
                        pendingGeofenceTasks[geofenceKey]?.let {
                            geofenceHandler.removeCallbacks(it)
                        }

                        val task = Runnable {

                            geofenceStates[geofenceKey] = true

                            sendGeofenceEntered(
                                childId,
                                geofenceKey,
                                name,
                                currentLat,
                                currentLng,
                                distanceMeters,
                                radius


                            )
                        }


                        pendingGeofenceTasks[geofenceKey] = task

                        geofenceHandler.postDelayed(task, 15000) // 15 seconds debounce
                    }

                    // EXITED
                    else if (!inside && previousState != false) {

                        val geofenceKey = snap.key ?: name

                        pendingGeofenceTasks[geofenceKey]?.let {
                            geofenceHandler.removeCallbacks(it)
                        }

                        val task = Runnable {

                            geofenceStates[geofenceKey] = false

                            sendGeofenceExited(
                                childId,
                                geofenceKey,
                                name
                            )
                        }

                        pendingGeofenceTasks[geofenceKey] = task

                        geofenceHandler.postDelayed(task, 8000) // 8 seconds debounce
                    }
                }
            }
    }

    // =====================================================
    // INSIDE GEOFENCE
    // =====================================================

    private fun isInsideGeofence(

        childLat: Double,
        childLng: Double,

        geofenceLat: Double,
        geofenceLng: Double,

        radius: Int

    ): Boolean {

        val results = FloatArray(1)

        Location.distanceBetween(

            childLat,
            childLng,

            geofenceLat,
            geofenceLng,

            results
        )

        return results[0] <= radius
    }

    //----------------calculate geofence distance---------
    private fun calculateDistance(
        childLat: Double,
        childLng: Double,
        geofenceLat: Double,
        geofenceLng: Double
    ): Float {

        val results = FloatArray(1)

        Location.distanceBetween(
            childLat,
            childLng,
            geofenceLat,
            geofenceLng,
            results
        )

        return results[0]
    }

    // =====================================================
    // ENTERED
    // =====================================================

    private fun sendGeofenceEntered(
        childId: String,
        geofenceKey: String,
        geofenceName: String,
        latitude: Double,
        longitude: Double,
        distanceMeters: Float,
        radius: Int
    ) {

        val timestamp =
            System.currentTimeMillis()

        val ref = FirebaseDatabase.getInstance()
            .getReference("geofence_alerts")
            .child(childId)
            .push()

        val alertKey =
            ref.key ?: return

        val data = mapOf(

            "geofenceId" to geofenceKey,

            "geofenceName" to geofenceName,

            "transitionType" to "ENTER",

            "enteredAt" to timestamp,

            "exitedAt" to 0,

            "durationMinutes" to 0,

            "status" to "active",

            "latitude" to latitude,

            "longitude" to longitude,

           "distance"  to  distanceMeters,

            "radius" to  radius
        )

        ref.setValue(data)


        updateCurrentGeofenceStatus(
            childId,
            geofenceKey,
            geofenceName,
            latitude,
            longitude,
            true,
            distanceMeters,
            radius
        )

        activeGeofenceSessions[geofenceKey] =
            alertKey
    }

    // =====================================================
    // EXITED
    // =====================================================

    private fun sendGeofenceExited(

        childId: String,

        geofenceKey: String,

        geofenceName: String
    ) {

        val alertKey =
            activeGeofenceSessions[geofenceKey]
                ?: return

        val exitedAt =
            System.currentTimeMillis()

        val ref = FirebaseDatabase.getInstance()
            .getReference("geofence_alerts")
            .child(childId)
            .child(alertKey)

        ref.get().addOnSuccessListener { snapshot ->

            val enteredAt =
                snapshot.child("enteredAt")
                    .getValue(Long::class.java)
                    ?: exitedAt

            val durationMinutes =
                ((exitedAt - enteredAt) / 1000 / 60).toInt()

            val updates = mapOf<String, Any>(

                "transitionType" to "EXIT",

                "exitedAt" to exitedAt,

                "durationMinutes" to durationMinutes,

                "status" to "completed"
            )

            ref.updateChildren(updates)

            markOutside(
                childId,
                geofenceKey,
                geofenceName
            )

            activeGeofenceSessions.remove(
                geofenceKey
            )
        }
    }
    //live status
    private fun updateCurrentGeofenceStatus(
        childId: String,
        geofenceKey: String,
        geofenceName: String,
        latitude: Double,
        longitude: Double,
        inside: Boolean,
        distanceMeters: Float,
        radius: Int
    ){

        val ref = FirebaseDatabase.getInstance()
            .getReference("current_geofence_status")
            .child(childId)
            .child(geofenceKey)

        val data = mapOf(

            "geofenceId" to geofenceKey,

            "geofenceName" to geofenceName,

            "inside" to inside,

            "since" to System.currentTimeMillis(),

            "latitude" to latitude,

            "longitude" to longitude,

            "lastSeen" to System.currentTimeMillis(),

            "distanceOutsideMeters" to maxOf(0f, distanceMeters - radius)

        )

        ref.setValue(data)

    }
    ///live status clear
    private fun clearCurrentGeofenceStatus(

        childId: String,

        geofenceKey: String
    ) {

        FirebaseDatabase.getInstance()
            .getReference("current_geofence_status")
            .child(childId)
            .child(geofenceKey)
            .removeValue()
    }

    //-------mark outside--------
    private fun markOutside(
        childId: String,
        geofenceKey: String,
        geofenceName: String
    ) {

        FirebaseDatabase.getInstance()
            .getReference("current_geofence_status")
            .child(childId)
            .child(geofenceKey)
            .updateChildren(
                mapOf(
                    "geofenceId" to geofenceKey,
                    "geofenceName" to geofenceName,
                    "inside" to false,
                    "lastSeen" to System.currentTimeMillis()
                )
            )
    }

}