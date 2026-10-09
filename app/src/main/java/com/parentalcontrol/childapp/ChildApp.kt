package com.parentalcontrol.childapp

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.worker.ServiceHealthWorker
import com.parentalcontrol.childapp.worker.VpnHealthWorker
import java.util.concurrent.TimeUnit

class ChildApp : Application(), Configuration.Provider {

    companion object {
        private const val TAG = "ChildApp"
    }

    private val urlReceiver = object : BroadcastReceiver() {

        override fun onReceive(
            context: Context?,
            intent: Intent?
        ) {

            val url =
                intent?.getStringExtra("url")

            val pkg =
                intent?.getStringExtra("package")

            Log.e(
                "URL_GLOBAL",
                "🔥 RECEIVED URL"
            )

            Log.e(
                "URL_GLOBAL",
                "URL = $url"
            )

            Log.e(
                "URL_GLOBAL",
                "PKG = $pkg"
            )
        }
    }

    // =====================================================
    // APPLICATION CREATE
    // =====================================================

    override fun onCreate() {

        super.onCreate()

        Log.e(
            TAG,
            "🔥 APP PROCESS CREATED"
        )

        registerUrlReceiver()

        initFirebase()

        startHealthWorker()

        startVpnHealthWorker()
    }

    // =====================================================
    // WORKMANAGER CONFIG
    // =====================================================

    override val workManagerConfiguration: Configuration
        get() =
            Configuration.Builder()
                .setMinimumLoggingLevel(
                    Log.INFO
                )
                .build()

    // =====================================================
    // FIREBASE INIT
    // =====================================================

    private fun initFirebase() {

        try {

            FirebaseDatabase
                .getInstance()
                .setPersistenceEnabled(true)

            Log.e(
                TAG,
                "✅ Firebase persistence enabled"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "⚠️ Firebase already initialized",
                e
            )
        }
    }

    // =====================================================
    // URL RECEIVER
    // =====================================================

    private fun registerUrlReceiver() {

        try {

            val filter =
                IntentFilter(
                    "com.parentalcontrol.childapp.URL_DETECTED"
                )

            ContextCompat.registerReceiver(
                this,
                urlReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            Log.e(
                TAG,
                "✅ URL Receiver registered"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ URL Receiver registration failed",
                e
            )
        }
    }

    // =====================================================
    // SERVICE HEALTH WORKER
    // =====================================================

    private fun startHealthWorker() {

        try {

            Log.e(
                TAG,
                "🚀 Scheduling ServiceHealthWorker"
            )

            val request =
                PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                    15,
                    TimeUnit.MINUTES
                )
                    .addTag(
                        "service_health"
                    )
                    .build()

            WorkManager
                .getInstance(this)
                .enqueueUniquePeriodicWork(
                    "service_health_worker",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.e(
                TAG,
                "✅ ServiceHealthWorker scheduled"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed scheduling ServiceHealthWorker",
                e
            )
        }
    }

    // =====================================================
    // VPN HEALTH WORKER
    // =====================================================

    private fun startVpnHealthWorker() {

        try {

            Log.e(
                TAG,
                "🚀 Scheduling VpnHealthWorker"
            )

            val request =
                PeriodicWorkRequestBuilder<VpnHealthWorker>(
                    15,
                    TimeUnit.MINUTES
                )
                    .addTag(
                        "vpn_health"
                    )
                    .build()

            WorkManager
                .getInstance(this)
                .enqueueUniquePeriodicWork(
                    "vpn_health_worker",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.e(
                TAG,
                "✅ VpnHealthWorker scheduled"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed scheduling VpnHealthWorker",
                e
            )
        }
    }
}