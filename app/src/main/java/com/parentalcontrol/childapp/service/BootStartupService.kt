package com.parentalcontrol.childapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.vpn.VpnAutoRestartService
import com.parentalcontrol.childapp.vpn.VpnController
import com.parentalcontrol.childapp.worker.ServiceHealthWorker
import java.util.concurrent.TimeUnit

class BootStartupService : Service() {

    companion object {

        private const val TAG =
            "BootStartupService"

        private const val CHANNEL_ID =
            "boot_start_channel"

        private const val NOTIFICATION_ID = 2222
    }

    private val handler =
        Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()

        Log.e(TAG, "🔥 CREATED")

        startForegroundSafe()
        //startVpnWithCheck()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(TAG, "🚀 BOOT STARTUP INIT")

        runStartupSequence()

        return START_STICKY
    }

    // =====================================================
    // FULL SYSTEM RESTORE
    // =====================================================
    private fun runStartupSequence() {

        Log.e(TAG, "⚡ SMART BOOT START")

        startMonitoring()

        handler.postDelayed({ startAppSync() }, 1000)
        handler.postDelayed({ startScreenTime() }, 1500)
        handler.postDelayed({ startLocation() }, 2000)

        // 🚨 ONLY START VPN WATCHDOG (NOT VPN DIRECTLY)
        handler.postDelayed({
            startVpnWatchdog()
        }, 4000)

        handler.postDelayed({
            restartHealthWorker()
        }, 5000)

        handler.postDelayed({
            Log.e(TAG, "✅ BOOT RESTORE COMPLETE")
        }, 8000)
    }
    //==========start watch dog==============================
    private fun startVpnWatchdog() {

        try {

            val intent = Intent(this, VpnAutoRestartService::class.java)
            ContextCompat.startForegroundService(this, intent)

            Log.e(TAG, "🛡 VPN WATCHDOG STARTED")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Watchdog failed", e)
        }
    }

    // =====================================================
    // MONITORING
    // =====================================================

    private fun startMonitoring() {

        try {

            val intent =
                Intent(this, MonitoringService::class.java)

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(TAG, "✅ Monitoring restarted")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Monitoring failed", e)
        }
    }

    // =====================================================
    // APP SYNC
    // =====================================================

    private fun startAppSync() {

        try {

            val intent =
                Intent(this, AppSyncService::class.java)

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(TAG, "✅ AppSync restarted")

        } catch (e: Exception) {

            Log.e(TAG, "❌ AppSync failed", e)
        }
    }

    // =====================================================
    // SCREEN TIME
    // =====================================================

    private fun startScreenTime() {

        try {

            val intent =
                Intent(this, ScreenTimeService::class.java)

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(TAG, "✅ ScreenTime restarted")

        } catch (e: Exception) {

            Log.e(TAG, "❌ ScreenTime failed", e)
        }
    }

    // =====================================================
    // LOCATION
    // =====================================================

    private fun startLocation() {

        try {

            val intent =
                Intent(this, ChildLocationService::class.java)

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(TAG, "✅ Location restarted")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Location failed", e)
        }
    }

    // =====================================================
    // VPN
    // =====================================================
    private fun startVpnWithCheck() {

        try {

            val vpnAllowed = getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            ).getBoolean(
                "vpn_allowed",
                false
            )

            if (!vpnAllowed) {

                Log.e(TAG, "❌ VPN permission not granted")

                return
            }

            // IMPORTANT

            VpnController.startVpn(this)

            Log.e(TAG, "⚡ VPN START REQUESTED")

        } catch (e: Exception) {

            Log.e(TAG, "❌ VPN failed", e)
        }
    }

    // =====================================================
    // WORKER RECOVERY
    // =====================================================

    private fun restartHealthWorker() {

        try {

            val request =
                PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                    15,
                    TimeUnit.MINUTES
                ).build()

            WorkManager.getInstance(this)
                .enqueueUniquePeriodicWork(
                    "service_health_worker",
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request
                )

            Log.e(TAG, "✅ Health worker restored")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Worker restore failed", e)
        }
    }

    // =====================================================
    // FOREGROUND
    // =====================================================

    private fun startForegroundSafe() {

        val manager =
            getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Boot Startup",
                    NotificationManager.IMPORTANCE_LOW
                )

            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle("Protection Active")
                .setContentText("Restoring child protection")
                .setOngoing(true)
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.e(TAG, "💀 BootStartupService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}