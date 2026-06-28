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

        return START_STICKY
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

        val launcherPackages =
            launcherApps.map {
                it.activityInfo.packageName
            }.toSet()

        Log.d(TAG, "📱 Launcher apps = ${launcherPackages.size}")

        // ----------------------------
        // 2. GET ALL INSTALLED APPS
        // ----------------------------
        val installedApps =
            pm.getInstalledApplications(0)

        Log.d(TAG, "📦 Installed apps = ${installedApps.size}")

        val dbRef = FirebaseDatabase.getInstance()
            .getReference("installed_apps")
            .child(childId)

        installedApps.forEach { app ->

            try {

                val packageName = app.packageName

                // Skip your own app
                if (packageName == applicationContext.packageName) return@forEach

                val appName = pm.getApplicationLabel(app).toString()

                val iconDrawable = pm.getApplicationIcon(app)
                val iconBase64 = AppIconUtils.drawableToBase64(iconDrawable)

                // ----------------------------
                // 🔥 REAL HIDDEN DETECTION
                // ----------------------------
                val hidden =
                    !launcherPackages.contains(packageName)

                // ----------------------------
                // SYSTEM APP FILTER (optional but recommended)
                // ----------------------------
                val isSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0

                val isUpdatedSystemApp =
                    (app.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

                if (isSystemApp && !isUpdatedSystemApp) {
                    return@forEach
                }

                // ----------------------------
                // FINAL MODEL
                // ----------------------------
                val appInfo = AppInfo(
                    appName = appName,
                    packageName = packageName,
                    iconBase64 = iconBase64,
                    hidden = hidden
                )

                dbRef.child(packageName.replace(".", "_"))
                    .setValue(appInfo)
                    .addOnSuccessListener {
                        Log.d(TAG, "✅ SYNC SUCCESS: $packageName hidden=$hidden")
                    }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "❌ SYNC FAILED: $packageName", e)
                    }

            } catch (e: Exception) {
                Log.e(TAG, "❌ ERROR PROCESSING APP", e)
            }
        }

        Log.d(TAG, "🏁 SYNC COMPLETED")
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