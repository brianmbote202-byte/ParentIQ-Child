package com.parentalcontrol.childapp

import android.app.Application
import android.util.Log
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.worker.ServiceHealthWorker
import java.util.concurrent.TimeUnit
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat

class ChildApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "ChildApp"
    }

    private val urlReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            val url = intent?.getStringExtra("url")
            val pkg = intent?.getStringExtra("package")

            Log.e("URL_GLOBAL", "🔥 RECEIVED URL")
            Log.e("URL_GLOBAL", "URL = $url")
            Log.e("URL_GLOBAL", "PKG = $pkg")
        }
    }



    override fun onCreate() {
        super.onCreate()

        Log.e(TAG, "🔥 APP PROCESS CREATED")

        registerUrlReceiver()

        initFirebase()
        startHealthWorker()
    }

    // =====================================================
    // WORKMANAGER CONFIG (REQUIRED FIX)
    // =====================================================

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(Log.INFO)
            .build()

    // =====================================================
    // FIREBASE INIT
    // =====================================================

    private fun initFirebase() {
        try {
            FirebaseDatabase.getInstance().setPersistenceEnabled(true)
            Log.e(TAG, "✅ Firebase persistence enabled")
        } catch (e: Exception) {
            Log.e(TAG, "⚠ Firebase already initialized", e)
        }
    }

    //-----------------url detection---------
    private fun registerUrlReceiver() {

        val filter = IntentFilter("com.parentalcontrol.childapp.URL_DETECTED")

        ContextCompat.registerReceiver(
            this,   // IMPORTANT: Application context (this is correct here)
            urlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        Log.e(TAG, "✅ URL Receiver registered")
    }

    // =====================================================
    // WORKMANAGER WATCHDOG
    // =====================================================

    private fun startHealthWorker() {
        try {
            Log.e(TAG, "🚀 Scheduling ServiceHealthWorker")

            val request = PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                15,
                TimeUnit.MINUTES
            )
                .addTag("service_health")
                .build()

            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "service_health_worker",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.e(TAG, "✅ ServiceHealthWorker scheduled")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed scheduling worker", e)
        }
    }
}