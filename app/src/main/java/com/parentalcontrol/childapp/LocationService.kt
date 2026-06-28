package com.parentalcontrol.childapp.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.*
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.R
import android.location.Geocoder
import com.parentalcontrol.childapp.geofence.GeofenceManager
import java.util.Locale
import okhttp3.*
import org.json.JSONObject
import java.io.IOException


class ChildLocationService : Service() {

    companion object {


        @Volatile var isRunning = false

        private const val TAG = "ChildLocationService"
        private const val CHANNEL_ID = "location_tracking_channel"
        private const val NOTIF_ID = 2001


        // Minimum distance to save history (meters)
        private const val MIN_DISTANCE_METERS = 20f

        // Maximum accuracy allowed (meters)
        private const val MAX_ACCEPTABLE_ACCURACY = 50f

        // Interval to save stationary points (milliseconds)
        private const val HISTORY_INTERVAL = 3 * 60 * 1000L

        // Interval to push batched points to Firebase (milliseconds) → 10s for smoother trail
        private const val BATCH_PUSH_INTERVAL = 10_000L

        // Maximum points in batch before forcing push
        private const val MAX_BATCH_SIZE = 10

        // Minimum speed to consider "moving" (m/s)
        private const val MIN_SPEED_MOVING = 0.5f

        // Auto-delete points older than 24h
        private const val HISTORY_RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val GEOCODING_API_KEY = "AIzaSyDhHgP9rG40Hs63BsLuX84BFBi7TBPuFeo"

        //---------------last uploaded children---------
        private var lastUploadedLocation: Location? = null





    }

    private lateinit var geocoder: android.location.Geocoder
    private var lastBatteryLevel = -1

    private var lastPlaceData: Map<String, Any> = emptyMap()

    private val httpClient = OkHttpClient()

    // Prevent repeated enter/exit spam
    private val geofenceStates =
        mutableMapOf<String, Boolean>()

    private val geofenceManager =
        GeofenceManager()


    // -----------------------------
    // Real-time listener for smooth trail
    // -----------------------------
    interface LocationUpdateListener {
        fun onNewLocation(location: Location)
    }
    var locationUpdateListener: LocationUpdateListener? = null

    // -----------------------------
    private var fusedLocationClient: FusedLocationProviderClient? = null
    private var childId: String? = null
    private lateinit var locationRequest: LocationRequest

    private var lastHistoryLocation: Location? = null
    private var lastBatchPushTime = 0L
    private var lastLat: Double? = null
    private var lastLng: Double? = null

    private val historyBatch = mutableMapOf<Long, Map<String, Any>>()

    private val handler = Handler(Looper.getMainLooper())
    private val stationaryRunnable = object : Runnable {
        override fun run() {
            saveStationaryPoint()
            handler.postDelayed(this, HISTORY_INTERVAL)
        }
    }

    // -----------------------------
    // Service Lifecycle
    // -----------------------------
    override fun onCreate() {
        super.onCreate()
        geocoder = Geocoder(this, Locale.getDefault())
        Log.d(TAG, "Service created")

        lastBatteryLevel = getBatteryLevel()
        lastPlaceData = mapOf(
            "street" to "",
            "estate" to "",
            "county" to "",
            "city" to "",
            "country" to ""
        )
        Log.d(TAG, "Initial place cache: $lastPlaceData")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        startForegroundNotification()
        childId = loadChildId()

        if (childId.isNullOrEmpty()) {
            Log.e(TAG, "Child ID missing — retrying in 3s")
            handler.postDelayed({
                childId = loadChildId()
                if (!childId.isNullOrEmpty()) initializeLocation()
                else stopSelf()
            }, 3000)
            return START_STICKY
        }

        initializeLocation()
        return START_STICKY
    }


    override fun onDestroy() {
        try {
            fusedLocationClient?.removeLocationUpdates(locationCallback)
            handler.removeCallbacks(stationaryRunnable)
            pushHistoryBatch()

            // ❌ NEW: Mark child offline
            childId?.let { id ->
                childRef(id)
                    .child("online")
                    .setValue(false)
            }


        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove location updates", e)
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------
    // Initialize location updates
    // -----------------------------
    private fun initializeLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            30_000L
        )
            .setMinUpdateIntervalMillis(15_000L)
            .setWaitForAccurateLocation(true)
            .build()

        startLocationUpdates()
        handler.postDelayed(stationaryRunnable, HISTORY_INTERVAL)
    }

    //----------------has locations cordinates changed---------
    private fun hasLocationChanged(
        newLat: Double,
        newLng: Double
    ): Boolean {

        if (lastLat == null || lastLng == null) {
            return true
        }

        return lastLat != newLat || lastLng != newLng
    }

    //----helper to dend data to child sub node----------
    private fun childRef(id: String) =
        FirebaseDatabase.getInstance()
            .getReference("children")
            .child(id)



    private fun isPlusCode(value: String?): Boolean {
        return value?.matches(Regex("^[A-Z0-9+]{6,}.*")) == true
    }


    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Location Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Location tracking active")
            .setContentText("Child location is being monitored")
            .setSmallIcon(R.drawable.ic_location)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Location permission missing")
            stopSelf()
            return
        }

        fusedLocationClient?.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    // -----------------------------
    // Location callback
    // -----------------------------
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                if (location.accuracy <= MAX_ACCEPTABLE_ACCURACY) {
                    handleLocation(location)
                } else {
                    Log.d(TAG, "Ignored location due to poor accuracy: ${location.accuracy}")
                }
            }
        }
    }

    // -----------------------------
    // Handle location
    // -----------------------------
    private fun handleLocation(location: Location) {

        val id = childId ?: return
        val timestamp = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()

        // -----------------------------
        // Movement detection
        // -----------------------------
        val distanceMoved = lastHistoryLocation?.distanceTo(location) ?: Float.MAX_VALUE
        val isMoving = distanceMoved > 10f || location.speed > MIN_SPEED_MOVING

        // -----------------------------
        // Location change detection
        // -----------------------------
        val latChanged = lastLat?.let {
            kotlin.math.abs(it - location.latitude) > 0.0001
        } ?: true

        val lngChanged = lastLng?.let {
            kotlin.math.abs(it - location.longitude) > 0.0001
        } ?: true

        val locationChanged = latChanged || lngChanged

        // -----------------------------
        // BASE DATA
        // -----------------------------
        val baseData: MutableMap<String, Any> = mutableMapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "speed" to location.speed,
            "timestamp" to timestamp,
            "isMoving" to isMoving
        )

        // attach last known place (prevents blank UI)
        baseData.putAll(lastPlaceData)

        // -----------------------------
        // BATTERY
        // -----------------------------
        val batteryLevel = getBatteryLevel()

        val batteryChanged =
            lastBatteryLevel == -1 ||
                    kotlin.math.abs(lastBatteryLevel - batteryLevel) >= 3

        if (batteryChanged) lastBatteryLevel = batteryLevel

        val statusUpdate = mutableMapOf<String, Any>(
            "online" to true,
            "lastSeen" to System.currentTimeMillis(),
            "isMoving" to isMoving,
            "status" to if (isMoving) "Moving" else "Idle"
        )

        if (batteryChanged) {
            statusUpdate["battery"] = batteryLevel
        }

        FirebaseDatabase.getInstance()
            .getReference("children")
            .child(id)
            .updateChildren(statusUpdate)

        // -----------------------------
        // SAVE BASE DATA IMMEDIATELY
        // -----------------------------
        val latestRef = FirebaseDatabase.getInstance()
            .getReference("child_locations")
            .child(id)
            .child("latest")

        if (hasLocationChanged(
                location.latitude,
                location.longitude
            )
        ) {

            val previous = lastUploadedLocation

            val shouldUpload = if (previous == null) {

                true

            } else {

                previous.distanceTo(location) >= 15f
            }

            if (shouldUpload) {

                latestRef.setValue(baseData)

                lastUploadedLocation = location

                Log.d(
                    TAG,
                    "Uploaded new location"
                )

            } else {

                Log.d(
                    TAG,
                    "Skipped duplicate location"
                )
            }

            Log.d(
                TAG,
                "Location changed -> Firebase updated"
            )

        } else {

            Log.d(
                TAG,
                "Same coordinates -> skipped Firebase write"
            )
        }

// -----------------------------
// GEOFENCE CHECK
// -----------------------------
        geofenceManager.checkGeofences(

            id,

            location.latitude,

            location.longitude
        )


        // update lat/lng cache
        lastLat = location.latitude
        lastLng = location.longitude

        // -----------------------------
        // FETCH ADDRESS (ASYNC)
        // -----------------------------
        if (locationChanged) {
            fetchPlaceFromApi(location.latitude, location.longitude) { result ->

                Log.d("GEOCODE_API", "Result: $result")

                // fallback or new data
                if (result.isNotEmpty()) {

                    // Remove empty values (CRITICAL FIX)
                    val cleanResult = result.filterValues {
                        it.toString().isNotBlank()
                    }

                    if (cleanResult.isNotEmpty()) {
                        lastPlaceData = cleanResult
                    } else {
                        Log.e("GEOCODE_API", "All values empty, keeping old place")
                    }

                } else {
                    Log.e("GEOCODE_API", "Empty result, using cached place")
                }

                // ALWAYS merge (important fix)
                val updatedData = baseData.toMutableMap()
                updatedData.putAll(lastPlaceData)

                latestRef.setValue(updatedData)
            }
        }

        // -----------------------------
        // HISTORY (FIXED: includes place data)
        // -----------------------------
        val minDistance = if (isMoving) MIN_DISTANCE_METERS else 5f

        if (distanceMoved > minDistance || lastHistoryLocation == null) {

            val fullData = baseData.toMutableMap().apply {
                putAll(lastPlaceData)
                put("status", if (isMoving) "Moving" else "Idle")
            }

            historyBatch[timestamp] = fullData
            lastHistoryLocation = location

            locationUpdateListener?.onNewLocation(location)
        }

        // -----------------------------
        // PUSH BATCH
        // -----------------------------
        val timeSinceLastPush = System.currentTimeMillis() - lastBatchPushTime

        if (timeSinceLastPush > BATCH_PUSH_INTERVAL ||
            historyBatch.size >= MAX_BATCH_SIZE
        ) {
            pushHistoryBatch()
        }
        Log.d("LOCATION_DEBUG", "Location changed: $locationChanged")
    }

    // =====================================================
// CHECK GEOFENCES
// =====================================================
    private fun checkGeofences(
        childId: String,
        currentLat: Double,
        currentLng: Double
    ) {

        FirebaseDatabase.getInstance()
            .getReference("geofences")
            .child(childId)
            .get()

            .addOnSuccessListener { snapshot ->

                Log.d(TAG, "Geofence snapshot exists: ${snapshot.exists()}")

                snapshot.children.forEach { snap ->

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

                    Log.d(
                        TAG,
                        "Geofence: $name | inside=$inside"
                    )

                    val geofenceKey =
                        snap.key ?: name

                    val previousState =
                        geofenceStates[geofenceKey]

                    // ENTERED
                    if (inside && previousState != true) {

                        geofenceStates[geofenceKey] = true

                        sendGeofenceAlert(

                            childId,

                            name,

                            "exited",

                            currentLat,

                            currentLng
                        )

                        Log.d(
                            TAG,
                            "ENTERED $name"
                        )
                    }

                    // EXITED
                    else if (!inside && previousState != false) {

                        geofenceStates[geofenceKey] = false

                        updateGeofenceStatus(
                            childId,
                            name,
                            "outside"
                        )

                        Log.d(
                            TAG,
                            "EXITED $name"
                        )
                    }
                }
            }

            .addOnFailureListener {

                Log.e(
                    TAG,
                    "Failed to load geofences",
                    it
                )
            }
    }
    //==========distance function====
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

        val distance = results[0]

        // GPS tolerance buffer
        val effectiveRadius = radius + 15

        Log.d(
            TAG,
            "Distance: $distance | Radius: $radius | Effective: $effectiveRadius"
        )

        return distance <= effectiveRadius
    }
    // =====================================================
// UPDATE GEOFENCE STATUS
// =====================================================

    private fun updateGeofenceStatus(
        childId: String,
        geofenceName: String,
        status: String
    ) {

        val data = mapOf(

            "geofence" to geofenceName,

            "status" to status,

            "timestamp" to System.currentTimeMillis()
        )

        FirebaseDatabase.getInstance()
            .getReference("geofence_status")
            .child(childId)
            .child(geofenceName)

            .setValue(data)

            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "Geofence status updated: $geofenceName -> $status"
                )
            }

            .addOnFailureListener {

                Log.e(
                    TAG,
                    "Failed to update geofence status",
                    it
                )
            }
    }


    //==========send geofence alerts======
    private fun sendGeofenceAlert(
        childId: String,
        geofenceName: String,
        event: String,
        latitude: Double,
        longitude: Double
    ) {

        val timestamp =
            System.currentTimeMillis()

        val data = mapOf(

            "geofenceName" to geofenceName,

            "event" to event,

            "timestamp" to timestamp,

            "latitude" to latitude,

            "longitude" to longitude
        )

        FirebaseDatabase.getInstance()
            .getReference("geofence_alerts")
            .child(childId)
            .child(timestamp.toString())

            .setValue(data)

            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "Geofence alert sent: $event"
                )
            }

            .addOnFailureListener {

                Log.e(
                    TAG,
                    "Failed to send geofence alert",
                    it
                )
            }
    }
    // -----------------------------
// Save stationary points
// -----------------------------
    private fun saveStationaryPoint() {
        val location = lastHistoryLocation ?: return

        val timestamp = System.currentTimeMillis()

        val data = mapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "speed" to 0,
            "timestamp" to timestamp,
            "isMoving" to false,
            "status" to "Idle"
        )

        historyBatch[timestamp] = data
    }

    //--------get battery percentage----------
    private fun getBatteryLevel(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

        val current = if (level >= 0 && scale > 0) {
            (level * 100 / scale)
        } else {
            lastBatteryLevel
        }

        return current
    }

    // -----------------------------
    // Push batch to Firebase
    // -----------------------------
    private fun pushHistoryBatch() {
        val id = childId ?: return
        if (historyBatch.isEmpty()) return

        val db = FirebaseDatabase.getInstance()
            .getReference("location_history")
            .child(id)

        val now = System.currentTimeMillis()
        val retentionCutoff = now - HISTORY_RETENTION_MS

        // Save batch
        historyBatch.toSortedMap().forEach { (timestamp, data) ->
            val date = getDateFromTimestamp(timestamp)
            db.child(date).child(timestamp.toString()).setValue(data)
        }

        // Delete points older than 24h
        db.get().addOnSuccessListener { snapshot ->
            snapshot.children.forEach { dateNode ->
                dateNode.children.forEach { tsNode ->
                    val ts = tsNode.key?.toLongOrNull()
                    if (ts != null && ts < retentionCutoff) {
                        tsNode.ref.removeValue()
                        Log.d(TAG, "Deleted old history point: $ts")
                    }
                }
            }
        }

        Log.d(TAG, "Pushed ${historyBatch.size} history points")
        historyBatch.clear()
        lastBatchPushTime = System.currentTimeMillis()
    }

    //-------get geocoding---------
    private fun fetchPlaceFromApi(
        lat: Double,
        lng: Double,
        callback: (Map<String, Any>) -> Unit
    ) {
        val url =
            "https://maps.googleapis.com/maps/api/geocode/json?latlng=$lat,$lng&key=$GEOCODING_API_KEY"

        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000

                val response = connection.inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(response)

                val status = json.optString("status")

                if (status != "OK") {
                    callback(emptyMap())
                    return@Thread
                }

                val results = json.optJSONArray("results")
                if (results == null || results.length() == 0) {
                    callback(emptyMap())
                    return@Thread
                }

                val components = results.getJSONObject(0)
                    .optJSONArray("address_components")

                if (components == null) {
                    callback(emptyMap())
                    return@Thread
                }

                var street = ""
                var county = ""
                var city = ""
                var estate = ""
                var country = ""

                for (i in 0 until components.length()) {
                    val item = components.optJSONObject(i) ?: continue
                    val types = item.optJSONArray("types") ?: continue
                    val name = item.optString("long_name", "")

                    for (j in 0 until types.length()) {
                        when (types.optString(j)) {
                            "route" -> street = name
                            "sublocality_level_1",
                            "sublocality" -> estate = name
                            "administrative_area_level_2" -> county = name
                            "locality" -> city = name
                            "country" -> country = name
                        }
                    }
                }

                val resultMap = mapOf(
                    "street" to street,
                    "estate" to estate,
                    "county" to county,
                    "city" to city,
                    "country" to country
                )

                // IMPORTANT: return safely (main thread if needed)
                Handler(Looper.getMainLooper()).post {
                    callback(resultMap)
                }

            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    callback(emptyMap())
                }
            }
        }.start()
    }

    // -----------------------------
    // Helpers
    // -----------------------------
    private fun getDateFromTimestamp(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }

    private fun loadChildId(): String? =
        getSharedPreferences("child_prefs", Context.MODE_PRIVATE).getString("child_id", null)
}
