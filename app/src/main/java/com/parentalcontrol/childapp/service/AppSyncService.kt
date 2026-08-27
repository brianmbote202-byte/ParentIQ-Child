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


class AppSyncService : Service() {

    private val TAG = "AppSyncService"
    private val CHANNEL_ID = "app_sync_channel"

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

        installedApps.forEach { app ->

            try {

                val packageName = app.packageName
                if (
                    packageName.equals(
                        "com.google.android.apps.searchlite",
                        ignoreCase = true
                    )
                ) {

                    Log.e(
                        TAG,
                        """
        ==========================================
        🔥 GOOGLE GO DETECTED
        ==========================================
        App Name     = ${
                            pm.getApplicationLabel(app)
                        }
        Package      = [$packageName]
        Firebase Key = [${
                            packageName.replace(".", "_")
                        }]
        System App   = ${
                            (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                        }
        Updated Sys  = ${
                            (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        }
        Launcher App = ${
                            launcherPackages.contains(packageName)
                        }
        ==========================================
        """.trimIndent()
                    )
                }

                if (packageName == applicationContext.packageName)
                    return@forEach

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
                        "⏭️ Skipping pure hidden system app"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "📱 Launcher = $isLauncherApp"
                    )

                    return@forEach
                }

                trackedPackages.add(packageName)

                val appInfo = AppInfo(
                    appName = pm.getApplicationLabel(app).toString(),
                    packageName = packageName,
                    iconBase64 = AppIconUtils.drawableToBase64(
                        pm.getApplicationIcon(app)
                    ),
                    hidden = !launcherPackages.contains(packageName)
                )

                updates[packageName.replace(".", "_")] = appInfo

            } catch (e: Exception) {

                Log.e(TAG, "Failed to process ${app.packageName}", e)
            }
        }

        dbRef.updateChildren(updates)

            .addOnSuccessListener {

                Log.d(TAG, "✅ Uploaded ${updates.size} apps.")

                saveTrackedApps(trackedPackages)

                startUsageWorker(trackedPackages)

                stopSelf()
            }

            .addOnFailureListener {

                Log.e(TAG, "Upload failed", it)
            }

        // ------------------------------------------------
        // SAVE TRACKED APPS LOCALLY
        // ------------------------------------------------
        saveTrackedApps(trackedPackages)
        startUsageWorker(trackedPackages)

        Log.d(
            TAG,
            "🏁 SYNC COMPLETED. Tracked ${trackedPackages.size} apps."
        )
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