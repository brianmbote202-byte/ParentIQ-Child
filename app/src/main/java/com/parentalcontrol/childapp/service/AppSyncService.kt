package com.parentalcontrol.childapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import com.parentalcontrol.childapp.model.AppInfo
import java.io.ByteArrayOutputStream
import com.parentalcontrol.childapp.utils.AppIconUtils
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors


class AppSyncService : Service() {

    private val TAG = "AppSyncService"
    private val CHANNEL_ID = "app_sync_channel"


    private val BACKEND_URL =
        "https://parentiq-backend.onrender.com"

    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "🚀 AppSyncService CREATED")

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "▶️ AppSyncService STARTED")

        startForeground(1001, buildNotification())

        syncAppsToFirebase()

        return START_NOT_STICKY
    }

    // ------------------------------------------------
    // 🔥 MAIN SYNC LOGIC
    // ------------------------------------------------
    private fun syncAppsToFirebase() {

        val childId = getChildId()

        Log.d(TAG, "👶 CHILD ID = $childId")

        if (childId.isBlank() || childId == "unknown_child") {
            Log.e(TAG, "❌ INVALID CHILD ID")
            return
        }

        val pm = packageManager

        // ----------------------------
        // 1. GET LAUNCHER APPS (VISIBLE)
        // ----------------------------
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val launcherApps = pm.queryIntentActivities(intent, 0)

        val launcherPackages = launcherApps.map {
            it.activityInfo.packageName
        }.toSet()

        Log.d(TAG, "📱 Launcher apps = ${launcherPackages.size}")

        // ----------------------------
        // 2. GET ALL INSTALLED APPS
        // ----------------------------
        val installedApps = pm.getInstalledApplications(0)

        Log.d(TAG, "📦 Installed apps = ${installedApps.size}")

        val dbRef = FirebaseDatabase.getInstance()
            .getReference("installed_apps")
            .child(childId)

        // ------------------------------------------------
        // STORE EVERY APP FOR USAGE TRACKING
        // ------------------------------------------------
        //val trackedPackages = mutableListOf<String>()

        /*installedApps.forEach { app ->

            try {

                val packageName = app.packageName

                // Skip Parent App
                if (packageName == applicationContext.packageName)
                    return@forEach

                val isSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val isUpdatedSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                // Ignore pure system apps
                if (isSystemApp && !isUpdatedSystemApp)
                    return@forEach

                // -----------------------------------------
                // SAVE PACKAGE FOR USAGE LOGGER
                // -----------------------------------------
                trackedPackages.add(packageName)

                val appName =
                    pm.getApplicationLabel(app).toString()

                val iconDrawable =
                    pm.getApplicationIcon(app)

                val iconBase64 =
                    AppIconUtils.drawableToBase64(iconDrawable)

                val hidden =
                    !launcherPackages.contains(packageName)

                val appInfo = AppInfo(
                    appName = appName,
                    packageName = packageName,
                    iconBase64 = iconBase64,
                    hidden = hidden
                )

                dbRef.child(packageName.replace(".", "_"))
                    .setValue(appInfo)
                    .addOnSuccessListener {
                        Log.d(
                            TAG,
                            "✅ SYNC SUCCESS: $packageName hidden=$hidden"
                        )
                    }
                    .addOnFailureListener { e ->
                        Log.e(
                            TAG,
                            "❌ SYNC FAILED: $packageName",
                            e
                        )
                    }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ ERROR PROCESSING APP",
                    e
                )
            }
        }*/

        val trackedPackages = mutableListOf<String>()
        val updates = hashMapOf<String, Any>()

        var pendingCategories = 0

        installedApps.forEach { app ->

            try {

                val packageName = app.packageName

                if (
                    packageName.equals(
                        applicationContext.packageName,
                        ignoreCase = true
                    )
                ) {
                    return@forEach
                }

                val isSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val isUpdatedSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                val isLauncherApp =
                    launcherPackages.contains(packageName)

                if (
                    isSystemApp &&
                    !isUpdatedSystemApp &&
                    !isLauncherApp
                ) {

                    Log.d(
                        TAG,
                        "⏭️ Skipping pure hidden system app: $packageName"
                    )

                    return@forEach
                }

                trackedPackages.add(packageName)

                pendingCategories++

                getAppCategoryFromFirebase(
                    packageName = packageName
                ) { category ->

                    val appInfo = AppInfo(
                        appName = pm.getApplicationLabel(app).toString(),
                        packageName = packageName,
                        category = category,
                        iconBase64 = AppIconUtils.drawableToBase64(
                            pm.getApplicationIcon(app)
                        ),
                        hidden = !isLauncherApp
                    )

                    updates[packageName.replace(".", "_")] = appInfo

                    Log.d(
                        TAG,
                        "📱 APP CLASSIFIED: ${appInfo.appName} → ${appInfo.category}"
                    )

                    pendingCategories--

                    /*
                     * Upload only after every app has
                     * received a category.
                     */
                    if (pendingCategories == 0) {

                        dbRef.updateChildren(updates)

                            .addOnSuccessListener {

                                Log.d(
                                    TAG,
                                    "✅ Uploaded ${updates.size} apps with Firebase categories."
                                )

                                saveTrackedApps(trackedPackages)

                                startUsageWorker(trackedPackages)

                                stopSelf()
                            }

                            .addOnFailureListener { error ->

                                Log.e(
                                    TAG,
                                    "❌ Upload failed",
                                    error
                                )
                            }
                    }
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Failed to process ${app.packageName}",
                    e
                )
            }
        }




    }

    //SAVE TRACKED APPS
    private fun saveTrackedApps(apps: List<String>) {

        val prefs = getSharedPreferences(
            "usage_tracker",
            Context.MODE_PRIVATE
        )

        prefs.edit()
            .putStringSet(
                "tracked_apps",
                apps.toSet()
            )
            .apply()

        Log.d(
            TAG,
            "Saved ${apps.size} tracked apps."
        )
    }




    private fun getAppCategoryFromFirebase(
        packageName: String,
        onResult: (String) -> Unit
    ) {

        val packageKey =
            packageName.replace(".", "_")

        val categoryRef =
            FirebaseDatabase.getInstance()
                .getReference("app_categories")
                .child(packageKey)

        categoryRef
            .get()
            .addOnSuccessListener { snapshot ->

                if (snapshot.exists()) {

                    val category =
                        snapshot
                            .child("category")
                            .getValue(String::class.java)
                            ?.trim()
                            ?.lowercase()
                            ?.takeIf { it.isNotBlank() }
                            ?: "other"

                    Log.d(
                        TAG,
                        "🏷️ Existing category: $packageName → $category"
                    )

                    /*
                     * The app already exists in Firebase.
                     *
                     * If its category is still pending, immediately
                     * ask the backend to classify it.
                     */
                    if (category == "pending") {

                        Log.d(
                            TAG,
                            "⏳ APP IS PENDING — REQUESTING BACKEND: $packageName"
                        )

                        requestBackendClassification(
                            packageKey = packageKey
                        ) { classifiedCategory ->

                            if (
                                classifiedCategory != null &&
                                classifiedCategory != "pending"
                            ) {

                                Log.d(
                                    TAG,
                                    "✅ APP CLASSIFIED BY BACKEND: $packageName → $classifiedCategory"
                                )

                                onResult(classifiedCategory)

                            } else {

                                /*
                                 * Backend failed or returned pending.
                                 *
                                 * Keep the app pending instead of
                                 * silently converting it to "other".
                                 */
                                Log.d(
                                    TAG,
                                    "⏳ APP REMAINS PENDING: $packageName"
                                )

                                onResult("pending")
                            }
                        }

                    } else {

                        /*
                         * The app already has a real category.
                         *
                         * No backend request is necessary.
                         */
                        onResult(category)
                    }

                } else {

                    /*
                     * App does not exist in app_categories.
                     *
                     * Register it as pending first.
                     */
                    val pm = packageManager

                    val applicationInfo =
                        try {
                            pm.getApplicationInfo(
                                packageName,
                                0
                            )
                        } catch (e: Exception) {
                            null
                        }

                    val appName =
                        applicationInfo
                            ?.let {
                                pm.getApplicationLabel(it).toString()
                            }
                            ?: packageName

                    val pendingData =
                        hashMapOf<String, Any>(
                            "appName" to appName,
                            "packageName" to packageName,
                            "category" to "pending",
                            "updatedAt" to System.currentTimeMillis()
                        )

                    categoryRef
                        .setValue(pendingData)
                        .addOnSuccessListener {

                            Log.d(
                                TAG,
                                "🆕 UNKNOWN APP REGISTERED: $appName ($packageName)"
                            )

                            /*
                             * Immediately ask the backend to classify
                             * the newly discovered application.
                             */
                            requestBackendClassification(
                                packageKey = packageKey
                            ) { classifiedCategory ->

                                if (
                                    classifiedCategory != null &&
                                    classifiedCategory != "pending"
                                ) {

                                    Log.d(
                                        TAG,
                                        "✅ APP CLASSIFIED BY BACKEND: $appName → $classifiedCategory"
                                    )

                                    onResult(classifiedCategory)

                                } else {

                                    /*
                                     * Keep the app pending if the backend
                                     * could not classify it.
                                     */
                                    Log.d(
                                        TAG,
                                        "⏳ APP REMAINS PENDING: $appName"
                                    )

                                    onResult("pending")
                                }
                            }
                        }
                        .addOnFailureListener { error ->

                            Log.e(
                                TAG,
                                "❌ Failed to register unknown app: $packageName",
                                error
                            )

                            onResult("other")
                        }
                }
            }
            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ Failed to get category for $packageName",
                    error
                )

                onResult("other")
            }
    }




    private fun requestBackendClassification(
        packageKey: String,
        onResult: (String?) -> Unit
    ) {

        Executors.newSingleThreadExecutor().execute {

            var connection: HttpURLConnection? = null

            try {

                Log.d(
                    TAG,
                    "🌐 REQUESTING BACKEND CLASSIFICATION: $packageKey"
                )

                val url = URL(
                    "$BACKEND_URL/app-classification/classify-pending"
                )

                connection =
                    url.openConnection() as HttpURLConnection

                connection.requestMethod = "POST"
                connection.connectTimeout = 15000
                connection.readTimeout = 30000
                connection.doOutput = true

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.setRequestProperty(
                    "Accept",
                    "application/json"
                )

                val requestBody =
                    JSONObject()
                        .put("packageKey", packageKey)
                        .toString()

                connection.outputStream.use { output ->

                    output.write(
                        requestBody.toByteArray(
                            Charsets.UTF_8
                        )
                    )

                    output.flush()
                }

                val responseCode =
                    connection.responseCode

                Log.d(
                    TAG,
                    "🌐 BACKEND RESPONSE CODE: $responseCode"
                )

                val responseStream =
                    if (responseCode in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }

                val response =
                    responseStream
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: ""

                Log.d(
                    TAG,
                    "🌐 BACKEND RESPONSE: $response"
                )

                if (responseCode !in 200..299) {

                    Log.e(
                        TAG,
                        "❌ Backend classification failed: HTTP $responseCode"
                    )

                    onResult(null)
                    return@execute
                }

                val json =
                    JSONObject(response)

                val success =
                    json.optBoolean(
                        "success",
                        false
                    )

                if (!success) {

                    Log.e(
                        TAG,
                        "❌ Backend returned classification failure"
                    )

                    onResult(null)
                    return@execute
                }

                val category =
                    json.optString(
                        "category",
                        ""
                    )
                        .trim()
                        .lowercase()

                if (category.isBlank()) {

                    Log.e(
                        TAG,
                        "❌ Backend returned empty category"
                    )

                    onResult(null)
                    return@execute
                }

                Log.d(
                    TAG,
                    "🤖 BACKEND CLASSIFICATION RESULT: $packageKey → $category"
                )

                onResult(category)

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ BACKEND CLASSIFICATION REQUEST FAILED",
                    e
                )

                onResult(null)

            } finally {

                connection?.disconnect()
            }
        }
    }





    private fun startUsageWorker(installedPackages: List<String>) {

        val childId = getChildId()

        val data = workDataOf(
            "childId" to childId,
            "trackedApps" to installedPackages.toTypedArray()
        )

        val workRequest =
            PeriodicWorkRequestBuilder<UsageLoggerWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setInputData(data)
                .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "UsageLoggerWorker",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )

        Log.d(TAG, "Usage worker scheduled for ${installedPackages.size} apps")
    }

    // ------------------------------------------------
    // 🔥 SAFE DRAWABLE → BITMAP
    // ------------------------------------------------


    // ------------------------------------------------
    // 🔥 ICON COMPRESSION
    // ------------------------------------------------


    // ------------------------------------------------
    // 🔥 CHILD ID
    // ------------------------------------------------
    private fun getChildId(): String {

        val prefs =
            getSharedPreferences(
                "child_prefs",
                Context.MODE_PRIVATE
            )

        return prefs.getString(
            "child_id",
            "unknown_child"
        ) ?: "unknown_child"
    }

    // ------------------------------------------------
    // 🔥 NOTIFICATION CHANNEL
    // ------------------------------------------------
    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "App Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "Keeps parental control sync active"

            val manager =
                getSystemService(NotificationManager::class.java)

            manager.createNotificationChannel(channel)

            Log.d(TAG, "🔔 Notification channel created")
        }
    }

    // ------------------------------------------------
    // 🔥 FOREGROUND NOTIFICATION
    // ------------------------------------------------
    private fun buildNotification(): Notification {

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle("Parental Control Running")
            .setContentText("Syncing installed apps...")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }


    override fun onDestroy() {
        super.onDestroy()

        Log.d(TAG, "🛑 AppSyncService DESTROYED")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}