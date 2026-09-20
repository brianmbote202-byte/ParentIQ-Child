package com.parentalcontrol.childapp.geofence

import android.location.Location
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class GeofenceManager {

    companion object {
        private const val TAG = "GeofenceManager"

        // Child must remain inside for this long before ENTER is confirmed.
        private const val ENTER_DELAY_MS = 15_000L

        // Child must remain outside for this long before EXIT is confirmed.
        private const val EXIT_DELAY_MS = 8_000L
    }

    // ---------------------------------------------------------
    // Current state of each geofence
    //
    // true  = inside
    // false = outside
    // null  = not established yet
    // ---------------------------------------------------------

    private val geofenceStates =
        mutableMapOf<String, Boolean>()

    // ---------------------------------------------------------
    // Delayed ENTER / EXIT tasks
    // ---------------------------------------------------------

    private val geofenceHandler =
        Handler(Looper.getMainLooper())

    private val pendingGeofenceTasks =
        mutableMapOf<String, Runnable>()


    // =========================================================
    // CHECK ALL GEOFENCES
    // =========================================================

    fun checkGeofences(
        childId: String,
        currentLat: Double,
        currentLng: Double
    ) {

        if (childId.isBlank()) {

            Log.e(
                TAG,
                "❌ Cannot check geofences: childId is empty"
            )

            return
        }

        Log.d(
            TAG,
            "=========================================="
        )

        Log.d(
            TAG,
            "📍 CHECKING GEOFENCES"
        )

        Log.d(
            TAG,
            "Child ID = $childId"
        )

        Log.d(
            TAG,
            "Current location = $currentLat, $currentLng"
        )

        FirebaseDatabase.getInstance()
            .getReference("geofences")
            .child(childId)
            .get()
            .addOnSuccessListener { snapshot ->

                if (!snapshot.exists()) {

                    Log.d(
                        TAG,
                        "ℹ️ No geofences configured for child=$childId"
                    )

                    return@addOnSuccessListener
                }

                snapshot.children.forEach { geofenceSnapshot ->

                    val geofenceKey =
                        geofenceSnapshot.key
                            ?: return@forEach

                    // -------------------------------------------------
                    // GEOFENCE CENTER
                    // -------------------------------------------------

                    val geofenceLat =
                        geofenceSnapshot
                            .child("latitude")
                            .getValue(Double::class.java)
                            ?: return@forEach

                    val geofenceLng =
                        geofenceSnapshot
                            .child("longitude")
                            .getValue(Double::class.java)
                            ?: return@forEach

                    // -------------------------------------------------
                    // RADIUS
                    // -------------------------------------------------

                    val radius =
                        geofenceSnapshot
                            .child("radius")
                            .getValue(Int::class.java)
                            ?: 0

                    if (radius <= 0) {

                        Log.w(
                            TAG,
                            "⚠️ Invalid radius for geofence=$geofenceKey"
                        )

                        return@forEach
                    }

                    // -------------------------------------------------
                    // NAME
                    // -------------------------------------------------

                    val geofenceName =
                        geofenceSnapshot
                            .child("name")
                            .getValue(String::class.java)
                            ?: "Unknown"

                    // -------------------------------------------------
                    // CALCULATE DISTANCE
                    // -------------------------------------------------

                    val distanceMeters =
                        calculateDistance(
                            childLat = currentLat,
                            childLng = currentLng,
                            geofenceLat = geofenceLat,
                            geofenceLng = geofenceLng
                        )

                    // -------------------------------------------------
                    // CHECK INSIDE / OUTSIDE
                    // -------------------------------------------------

                    val inside =
                        isInsideGeofence(
                            childLat = currentLat,
                            childLng = currentLng,
                            geofenceLat = geofenceLat,
                            geofenceLng = geofenceLng,
                            radius = radius
                        )

                    // -------------------------------------------------
                    // PREVIOUS STATE
                    // -------------------------------------------------

                    val previousState =
                        geofenceStates[geofenceKey]

                    Log.d(
                        TAG,
                        "------------------------------------------"
                    )

                    Log.d(
                        TAG,
                        "📍 Geofence: $geofenceName"
                    )

                    Log.d(
                        TAG,
                        "ID: $geofenceKey"
                    )

                    Log.d(
                        TAG,
                        "Distance: ${distanceMeters}m"
                    )

                    Log.d(
                        TAG,
                        "Radius: ${radius}m"
                    )

                    Log.d(
                        TAG,
                        "Inside: $inside"
                    )

                    Log.d(
                        TAG,
                        "Previous state: $previousState"
                    )

                    // =================================================
                    // FIRST OBSERVATION
                    // =================================================
                    //
                    // Important:
                    //
                    // If previousState == null, this is the first
                    // time this geofence has been checked.
                    //
                    // We establish the state but DO NOT create an
                    // ENTER or EXIT alert.
                    //
                    // This prevents a false EXIT alert when the
                    // service starts while the child is outside.
                    // =================================================

                    if (previousState == null) {

                        geofenceStates[geofenceKey] =
                            inside

                        Log.d(
                            TAG,
                            "🟡 Initial state established"
                        )

                        Log.d(
                            TAG,
                            "$geofenceName -> inside=$inside"
                        )

                        return@forEach
                    }

                    // =================================================
                    // CHILD MOVED INSIDE
                    // =================================================

                    if (
                        previousState == false &&
                        inside
                    ) {

                        Log.d(
                            TAG,
                            "🟢 ENTER transition detected"
                        )

                        scheduleEnter(
                            childId = childId,
                            geofenceKey = geofenceKey,
                            geofenceName = geofenceName,
                            latitude = currentLat,
                            longitude = currentLng,
                            distanceMeters = distanceMeters,
                            radius = radius
                        )

                        return@forEach
                    }

                    // =================================================
                    // CHILD MOVED OUTSIDE
                    // =================================================

                    if (
                        previousState == true &&
                        !inside
                    ) {

                        Log.d(
                            TAG,
                            "🔴 EXIT transition detected"
                        )

                        scheduleExit(
                            childId = childId,
                            geofenceKey = geofenceKey,
                            geofenceName = geofenceName
                        )

                        return@forEach
                    }

                    // =================================================
                    // NO TRANSITION
                    // =================================================

                    Log.d(
                        TAG,
                        "➡️ No geofence transition"
                    )
                }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed to load geofences",
                    error
                )
            }
    }


    // =========================================================
    // SCHEDULE ENTER
    // =========================================================

    private fun scheduleEnter(
        childId: String,
        geofenceKey: String,
        geofenceName: String,
        latitude: Double,
        longitude: Double,
        distanceMeters: Float,
        radius: Int
    ) {

        // Cancel an existing task for this geofence.

        pendingGeofenceTasks[geofenceKey]
            ?.let { existingTask ->

                geofenceHandler.removeCallbacks(
                    existingTask
                )
            }

        val task =
            Runnable {

                pendingGeofenceTasks.remove(
                    geofenceKey
                )

                // -------------------------------------------------
                // Confirm inside state
                // -------------------------------------------------

                geofenceStates[geofenceKey] =
                    true

                Log.d(
                    TAG,
                    "=========================================="
                )

                Log.d(
                    TAG,
                    "🟢 ENTER CONFIRMED"
                )

                Log.d(
                    TAG,
                    "Geofence = $geofenceName"
                )

                Log.d(
                    TAG,
                    "Distance = ${distanceMeters}m"
                )

                Log.d(
                    TAG,
                    "Radius = ${radius}m"
                )

                Log.d(
                    TAG,
                    "=========================================="
                )

                sendGeofenceEntered(
                    childId = childId,
                    geofenceKey = geofenceKey,
                    geofenceName = geofenceName,
                    latitude = latitude,
                    longitude = longitude,
                    distanceMeters = distanceMeters,
                    radius = radius
                )
            }

        pendingGeofenceTasks[geofenceKey] =
            task

        Log.d(
            TAG,
            "⏳ ENTER scheduled for $geofenceName"
        )

        Log.d(
            TAG,
            "Waiting ${ENTER_DELAY_MS / 1000}s"
        )

        geofenceHandler.postDelayed(
            task,
            ENTER_DELAY_MS
        )
    }


    // =========================================================
    // SCHEDULE EXIT
    // =========================================================

    private fun scheduleExit(
        childId: String,
        geofenceKey: String,
        geofenceName: String
    ) {

        // Cancel an existing task for this geofence.

        pendingGeofenceTasks[geofenceKey]
            ?.let { existingTask ->

                geofenceHandler.removeCallbacks(
                    existingTask
                )
            }

        val task =
            Runnable {

                pendingGeofenceTasks.remove(
                    geofenceKey
                )

                // -------------------------------------------------
                // Confirm outside state
                // -------------------------------------------------

                geofenceStates[geofenceKey] =
                    false

                Log.d(
                    TAG,
                    "=========================================="
                )

                Log.d(
                    TAG,
                    "🔴 EXIT CONFIRMED"
                )

                Log.d(
                    TAG,
                    "Geofence = $geofenceName"
                )

                Log.d(
                    TAG,
                    "=========================================="
                )

                sendGeofenceExited(
                    childId = childId,
                    geofenceKey = geofenceKey,
                    geofenceName = geofenceName
                )
            }

        pendingGeofenceTasks[geofenceKey] =
            task

        Log.d(
            TAG,
            "⏳ EXIT scheduled for $geofenceName"
        )

        Log.d(
            TAG,
            "Waiting ${EXIT_DELAY_MS / 1000}s"
        )

        geofenceHandler.postDelayed(
            task,
            EXIT_DELAY_MS
        )
    }


    // =========================================================
    // CHECK WHETHER CHILD IS INSIDE
    // =========================================================

    private fun isInsideGeofence(
        childLat: Double,
        childLng: Double,
        geofenceLat: Double,
        geofenceLng: Double,
        radius: Int
    ): Boolean {

        val results =
            FloatArray(1)

        Location.distanceBetween(
            childLat,
            childLng,
            geofenceLat,
            geofenceLng,
            results
        )

        return results[0] <= radius
    }


    // =========================================================
    // CALCULATE DISTANCE
    // =========================================================

    private fun calculateDistance(
        childLat: Double,
        childLng: Double,
        geofenceLat: Double,
        geofenceLng: Double
    ): Float {

        val results =
            FloatArray(1)

        Location.distanceBetween(
            childLat,
            childLng,
            geofenceLat,
            geofenceLng,
            results
        )

        return results[0]
    }


    // =========================================================
    // ACTIVE SESSION REFERENCE
    // =========================================================

    private fun getActiveSessionRef(
        childId: String,
        geofenceId: String
    ) =
        FirebaseDatabase.getInstance()
            .getReference("active_geofence_sessions")
            .child(childId)
            .child(geofenceId)


    // =========================================================
    // ENTER GEOFENCE
    // =========================================================

    private fun sendGeofenceEntered(
        childId: String,
        geofenceKey: String,
        geofenceName: String,
        latitude: Double,
        longitude: Double,
        distanceMeters: Float,
        radius: Int
    ) {

        val sessionRef =
            getActiveSessionRef(
                childId = childId,
                geofenceId = geofenceKey
            )

        // ---------------------------------------------------------
        // Prevent duplicate active ENTER records
        // ---------------------------------------------------------

        sessionRef
            .get()
            .addOnSuccessListener { session ->

                if (session.exists()) {

                    Log.d(
                        TAG,
                        "ℹ️ Active session already exists for $geofenceName"
                    )

                    return@addOnSuccessListener
                }

                // -------------------------------------------------
                // Create alert
                // -------------------------------------------------

                val alertRef =
                    FirebaseDatabase.getInstance()
                        .getReference("geofence_alerts")
                        .child(childId)
                        .push()

                val alertId =
                    alertRef.key

                        ?: run {

                            Log.e(
                                TAG,
                                "❌ Could not create geofence alert ID"
                            )

                            return@addOnSuccessListener
                        }

                val now =
                    System.currentTimeMillis()

                val data =
                    mapOf(
                        "geofenceId" to geofenceKey,
                        "geofenceName" to geofenceName,
                        "transitionType" to "ENTER",
                        "enteredAt" to now,
                        "exitedAt" to 0L,
                        "durationMinutes" to 0,
                        "status" to "active",
                        "latitude" to latitude,
                        "longitude" to longitude,
                        "distance" to distanceMeters,
                        "radius" to radius
                    )

                // -------------------------------------------------
                // Save alert
                // -------------------------------------------------

                alertRef
                    .setValue(data)
                    .addOnSuccessListener {

                        Log.d(
                            TAG,
                            "✅ ENTER alert saved"
                        )

                        // -------------------------------------------------
                        // Save active session
                        // -------------------------------------------------

                        val sessionData =
                            mapOf(
                                "alertId" to alertId,
                                "startedAt" to now
                            )

                        sessionRef
                            .setValue(sessionData)
                            .addOnSuccessListener {

                                Log.d(
                                    TAG,
                                    "✅ Active geofence session saved"
                                )

                                // -------------------------------------------------
                                // Update live status
                                // -------------------------------------------------

                                updateCurrentGeofenceStatus(
                                    childId = childId,
                                    geofenceKey = geofenceKey,
                                    geofenceName = geofenceName,
                                    latitude = latitude,
                                    longitude = longitude,
                                    inside = true,
                                    distanceMeters = distanceMeters,
                                    radius = radius
                                )
                            }
                            .addOnFailureListener { error ->

                                Log.e(
                                    TAG,
                                    "❌ Failed to save active session",
                                    error
                                )

                                // If session creation fails,
                                // remove the alert so we don't leave
                                // an inconsistent active alert.

                                alertRef.removeValue()
                            }
                    }
                    .addOnFailureListener { error ->

                        Log.e(
                            TAG,
                            "❌ Failed to save ENTER alert",
                            error
                        )
                    }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed checking active geofence session",
                    error
                )
            }
    }


    // =========================================================
    // EXIT GEOFENCE
    // =========================================================

    private fun sendGeofenceExited(
        childId: String,
        geofenceKey: String,
        geofenceName: String
    ) {

        val sessionRef =
            getActiveSessionRef(
                childId = childId,
                geofenceId = geofenceKey
            )

        sessionRef
            .get()
            .addOnSuccessListener { session ->

                // -------------------------------------------------
                // No active session
                // -------------------------------------------------

                if (!session.exists()) {

                    Log.d(
                        TAG,
                        "ℹ️ No active session for EXIT: $geofenceName"
                    )

                    markOutside(
                        childId = childId,
                        geofenceKey = geofenceKey,
                        geofenceName = geofenceName
                    )

                    return@addOnSuccessListener
                }

                // -------------------------------------------------
                // Get alert ID
                // -------------------------------------------------

                val alertId =
                    session
                        .child("alertId")
                        .getValue(String::class.java)

                        ?: run {

                            Log.e(
                                TAG,
                                "❌ Active session has no alertId"
                            )

                            return@addOnSuccessListener
                        }

                val alertRef =
                    FirebaseDatabase.getInstance()
                        .getReference("geofence_alerts")
                        .child(childId)
                        .child(alertId)

                val exitedAt =
                    System.currentTimeMillis()

                // -------------------------------------------------
                // Read original ENTER
                // -------------------------------------------------

                alertRef
                    .get()
                    .addOnSuccessListener { alertSnapshot ->

                        val enteredAt =
                            alertSnapshot
                                .child("enteredAt")
                                .getValue(Long::class.java)
                                ?: exitedAt

                        val durationMinutes =
                            (
                                    (exitedAt - enteredAt)
                                            / 60_000L
                                    )
                                .toInt()
                                .coerceAtLeast(0)

                        val updates =
                            mapOf<String, Any>(
                                "transitionType" to "EXIT",
                                "exitedAt" to exitedAt,
                                "durationMinutes" to durationMinutes,
                                "status" to "completed"
                            )

                        // -------------------------------------------------
                        // Update alert
                        // -------------------------------------------------

                        alertRef
                            .updateChildren(updates)
                            .addOnSuccessListener {

                                Log.d(
                                    TAG,
                                    "✅ EXIT alert updated"
                                )

                                Log.d(
                                    TAG,
                                    "Duration = ${durationMinutes} minutes"
                                )

                                // -------------------------------------------------
                                // Remove active session
                                // -------------------------------------------------

                                sessionRef
                                    .removeValue()
                                    .addOnSuccessListener {

                                        Log.d(
                                            TAG,
                                            "✅ Active session removed"
                                        )

                                        // -------------------------------------------------
                                        // Update live status
                                        // -------------------------------------------------

                                        markOutside(
                                            childId = childId,
                                            geofenceKey = geofenceKey,
                                            geofenceName = geofenceName
                                        )
                                    }
                                    .addOnFailureListener { error ->

                                        Log.e(
                                            TAG,
                                            "❌ Failed removing active session",
                                            error
                                        )
                                    }
                            }
                            .addOnFailureListener { error ->

                                Log.e(
                                    TAG,
                                    "❌ Failed updating EXIT alert",
                                    error
                                )
                            }
                    }
                    .addOnFailureListener { error ->

                        Log.e(
                            TAG,
                            "❌ Failed reading ENTER alert",
                            error
                        )
                    }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed reading active session",
                    error
                )
            }
    }


    // =========================================================
    // UPDATE CURRENT GEOFENCE STATUS
    // =========================================================

    private fun updateCurrentGeofenceStatus(
        childId: String,
        geofenceKey: String,
        geofenceName: String,
        latitude: Double,
        longitude: Double,
        inside: Boolean,
        distanceMeters: Float,
        radius: Int
    ) {

        val ref =
            FirebaseDatabase.getInstance()
                .getReference("current_geofence_status")
                .child(childId)
                .child(geofenceKey)

        val now =
            System.currentTimeMillis()

        val data =
            mapOf(
                "geofenceId" to geofenceKey,
                "geofenceName" to geofenceName,
                "inside" to inside,
                "since" to now,
                "latitude" to latitude,
                "longitude" to longitude,
                "lastSeen" to now,
                "distanceOutsideMeters" to
                        maxOf(
                            0f,
                            distanceMeters - radius
                        )
            )

        ref
            .setValue(data)
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "✅ Current geofence status updated: $geofenceName"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed updating current geofence status",
                    error
                )
            }
    }


    // =========================================================
    // MARK OUTSIDE
    // =========================================================

    private fun markOutside(
        childId: String,
        geofenceKey: String,
        geofenceName: String
    ) {

        val ref =
            FirebaseDatabase.getInstance()
                .getReference("current_geofence_status")
                .child(childId)
                .child(geofenceKey)

        val updates =
            mapOf<String, Any>(
                "geofenceId" to geofenceKey,
                "geofenceName" to geofenceName,
                "inside" to false,
                "lastSeen" to System.currentTimeMillis()
            )

        ref
            .updateChildren(updates)
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "✅ Geofence marked outside: $geofenceName"
                )
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed marking geofence outside",
                    error
                )
            }
    }


    // =========================================================
    // CLEANUP
    // =========================================================
    //
    // Call this if the manager/service is being destroyed.
    // It prevents delayed ENTER/EXIT callbacks from firing after
    // the location service has stopped.
    // =========================================================

    fun cleanup() {

        pendingGeofenceTasks.values
            .forEach { task ->

                geofenceHandler.removeCallbacks(
                    task
                )
            }

        pendingGeofenceTasks.clear()

        geofenceStates.clear()

        Log.d(
            TAG,
            "🧹 GeofenceManager cleaned up"
        )
    }
}