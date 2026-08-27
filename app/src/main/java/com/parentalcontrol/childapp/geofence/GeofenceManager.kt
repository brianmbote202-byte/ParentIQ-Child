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

        Log.d(TAG, "Checking geofences for $childId")

        FirebaseDatabase.getInstance()
            .getReference("geofences")
            .child(childId)
            .get()
            .addOnSuccessListener { snapshot ->

                Log.d(TAG, "Loaded ${snapshot.childrenCount} geofences")

                snapshot.children.forEach { snap ->

                    val latitude = snap.child("latitude")
                        .getValue(Double::class.java) ?: 0.0

                    val longitude = snap.child("longitude")
                        .getValue(Double::class.java) ?: 0.0

                    val radius = snap.child("radius")
                        .getValue(Int::class.java) ?: 0

                    val name = snap.child("name")
                        .getValue(String::class.java) ?: "Unknown"

                    val geofenceKey = snap.key ?: name

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

                    val previousState = geofenceStates[geofenceKey]

                    //---------------------------------------
                    // ENTER
                    //---------------------------------------

                    if (inside && previousState != true) {

                        pendingGeofenceTasks[geofenceKey]?.let {
                            geofenceHandler.removeCallbacks(it)
                        }

                        val task = Runnable {

                            geofenceStates[geofenceKey] = true

                            pendingGeofenceTasks.remove(geofenceKey)

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

                        geofenceHandler.postDelayed(task, 15_000)
                    }

                    //---------------------------------------
                    // EXIT
                    //---------------------------------------

                    else if (!inside && previousState != false) {

                        pendingGeofenceTasks[geofenceKey]?.let {
                            geofenceHandler.removeCallbacks(it)
                        }

                        val task = Runnable {

                            geofenceStates[geofenceKey] = false

                            pendingGeofenceTasks.remove(geofenceKey)

                            sendGeofenceExited(
                                childId,
                                geofenceKey,
                                name
                            )
                        }

                        pendingGeofenceTasks[geofenceKey] = task

                        geofenceHandler.postDelayed(task, 8_000)
                    }
                }
            }
            .addOnFailureListener {
                Log.e(TAG, "Failed to load geofences", it)
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



    //--------get active session----------
    private fun getActiveSessionRef(
        childId: String,
        geofenceId: String
    ) =
        FirebaseDatabase.getInstance()
            .getReference("active_geofence_sessions")
            .child(childId)
            .child(geofenceId)
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

        val sessionRef = getActiveSessionRef(childId, geofenceKey)

        sessionRef.get()
            .addOnSuccessListener { session ->

                // Already inside this geofence?
                if (session.exists()) {
                    Log.d(TAG, "$geofenceName already active")
                    return@addOnSuccessListener
                }

                //----------------------------------------
                // Create new alert
                //----------------------------------------

                val alertRef = FirebaseDatabase.getInstance()
                    .getReference("geofence_alerts")
                    .child(childId)
                    .push()

                val alertId = alertRef.key
                    ?: return@addOnSuccessListener

                val now = System.currentTimeMillis()

                val data = mapOf(
                    "geofenceId" to geofenceKey,
                    "geofenceName" to geofenceName,
                    "transitionType" to "ENTER",
                    "enteredAt" to now,
                    "exitedAt" to 0,
                    "durationMinutes" to 0,
                    "status" to "active",
                    "latitude" to latitude,
                    "longitude" to longitude,
                    "distance" to distanceMeters,
                    "radius" to radius
                )

                //----------------------------------------
                // Step 1: Save alert
                //----------------------------------------

                alertRef.setValue(data)
                    .addOnSuccessListener {

                        //----------------------------------------
                        // Step 2: Save active session
                        //----------------------------------------

                        sessionRef.setValue(
                            mapOf(
                                "alertId" to alertId,
                                "startedAt" to now
                            )
                        )
                            .addOnSuccessListener {

                                //----------------------------------------
                                // Step 3: Update live status
                                //----------------------------------------

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

                                Log.d(
                                    TAG,
                                    "Started geofence session for $geofenceName"
                                )
                            }
                            .addOnFailureListener { e ->

                                Log.e(
                                    TAG,
                                    "Failed to save active session",
                                    e
                                )

                                // Roll back the alert since the session failed
                                alertRef.removeValue()
                            }
                    }
                    .addOnFailureListener { e ->

                        Log.e(
                            TAG,
                            "Failed to create geofence alert",
                            e
                        )
                    }
            }
            .addOnFailureListener { e ->

                Log.e(
                    TAG,
                    "Failed to read active session",
                    e
                )
            }
    }
    // =====================================================
    // EXITED
    // =====================================================
    private fun sendGeofenceExited(
        childId: String,
        geofenceKey: String,
        geofenceName: String
    ) {

        val sessionRef = getActiveSessionRef(childId, geofenceKey)

        sessionRef.get()
            .addOnSuccessListener { session ->

                if (!session.exists()) {
                    Log.d(TAG, "No active session for $geofenceName")
                    return@addOnSuccessListener
                }

                val alertId = session.child("alertId")
                    .getValue(String::class.java)
                    ?: return@addOnSuccessListener

                val alertRef = FirebaseDatabase.getInstance()
                    .getReference("geofence_alerts")
                    .child(childId)
                    .child(alertId)

                val exitedAt = System.currentTimeMillis()

                alertRef.get()
                    .addOnSuccessListener { snapshot ->

                        val enteredAt =
                            snapshot.child("enteredAt")
                                .getValue(Long::class.java)
                                ?: exitedAt

                        val durationMinutes =
                            ((exitedAt - enteredAt) / 60000).toInt()

                        val updates = mapOf<String, Any>(
                            "transitionType" to "EXIT",
                            "exitedAt" to exitedAt,
                            "durationMinutes" to durationMinutes,
                            "status" to "completed"
                        )

                        // Step 1: Update the alert
                        alertRef.updateChildren(updates)
                            .addOnSuccessListener {

                                // Step 2: Remove active session
                                sessionRef.removeValue()
                                    .addOnSuccessListener {

                                        // Step 3: Update current status
                                        markOutside(
                                            childId,
                                            geofenceKey,
                                            geofenceName
                                        )

                                        Log.d(
                                            TAG,
                                            "Completed geofence visit for $geofenceName"
                                        )
                                    }
                                    .addOnFailureListener {
                                        Log.e(
                                            TAG,
                                            "Failed to remove active session",
                                            it
                                        )
                                    }
                            }
                            .addOnFailureListener {
                                Log.e(
                                    TAG,
                                    "Failed to update geofence alert",
                                    it
                                )
                            }
                    }
                    .addOnFailureListener {
                        Log.e(
                            TAG,
                            "Failed to read geofence alert",
                            it
                        )
                    }
            }
            .addOnFailureListener {
                Log.e(
                    TAG,
                    "Failed to read active session",
                    it
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