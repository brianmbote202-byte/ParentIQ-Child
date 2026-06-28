package com.parentalcontrol.childapp.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
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
import com.parentalcontrol.childapp.service.*
import com.parentalcontrol.childapp.vpn.DnsVpnService
import com.parentalcontrol.childapp.service.ScreenCaptureService
import com.parentalcontrol.childapp.service.ScreenTimeService
import com.parentalcontrol.childapp.worker.VpnHealthWorker
import java.util.concurrent.TimeUnit

class SystemBootOrchestrator : Service() {

    companion object {
        private const val TAG = "SystemBootOrchestrator"
        private const val CHANNEL_ID = "system_boot_channel"
        private const val NOTIF_ID = 9090
    }

    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    override fun onCreate() {
        super.onCreate()

        startForegroundSafe()
        startVpnWatchdog()
        Log.d(TAG, "🚀 Boot Orchestrator created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (started) {
            Log.d(TAG, "⚠ Already started, skipping duplicate boot")
            return START_STICKY
        }

        started = true
        Log.d(TAG, "🔥 STARTING SELF-HEALING SYSTEM")

        runRecoverySequence()

        return START_STICKY
    }

    // ============================
    // SELF-HEALING SEQUENCE
    // ============================
    private fun runRecoverySequence() {

        startGuardian()

        handler.postDelayed({
            startScreenTime()
        }, 1000)

        handler.postDelayed({
            startLocation()
        }, 2000)

        handler.postDelayed({
            startAppSync()
        }, 3000)

        handler.postDelayed({
            startVpnSafely()
        }, 4500)

        handler.postDelayed({
            startMonitoringWatchdog()
        }, 6000)

        handler.postDelayed({
            Log.d(TAG, "✅ SYSTEM RECOVERY COMPLETE")
            started = false
            stopSelf()
        }, 9000)
    }

    // ============================
    // CORE SERVICES
    // ============================

    private fun startGuardian() {
        startSafe(GuardianService::class.java, "GuardianService")
    }

    private fun startScreenTime() {
        startSafe(ScreenTimeService::class.java, "ScreenTimeService")
    }

    private fun startLocation() {
        startSafe(ChildLocationService::class.java, "ChildLocationService")
    }

    private fun startAppSync() {
        startSafe(AppSyncService::class.java, "AppSyncService")
    }

    // ============================
    // VPN (SPECIAL HANDLING)
    // ============================
    private fun startVpnSafely() {

        val context = this

        val prefs =
            getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            )

        try {

            val vpnAllowed =
                prefs.getBoolean(
                    "vpn_allowed",
                    true
                )

            if (!vpnAllowed) {

                Log.e(TAG, "🚫 VPN disabled")
                return
            }

            // VPN permission missing
            val prepareIntent =
                android.net.VpnService.prepare(context)

            if (prepareIntent != null) {

                Log.e(TAG, "⚠ VPN permission not granted")
                return
            }

            // already running
            if (DnsVpnService.isRunning) {

                Log.e(TAG, "⚠ VPN already running")
                return
            }

            val intent =
                Intent(context, DnsVpnService::class.java)

            ContextCompat.startForegroundService(
                context,
                intent
            )

            Log.e(TAG, "🌐 VPN STARTED")

        } catch (e: Exception) {

            Log.e(TAG, "❌ VPN START FAILED", e)
        }
    }

    // ============================
    // WATCHDOG (SELF-HEAL LOOP)
    // ============================
    private fun startMonitoringWatchdog() {

        try {
            startSafe(MonitoringService::class.java, "MonitoringService")

            handler.postDelayed({
                startSafe(AppSyncService::class.java, "AppSyncService-HEAL")
                startSafe(ScreenTimeService::class.java, "ScreenTimeService-HEAL")
            }, 5000)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Watchdog failed", e)
        }
    }

    // ============================
    // SAFE START WRAPPER
    // ============================
    private fun startSafe(service: Class<*>, name: String) {

        try {
            val intent = Intent(this, service)
            ContextCompat.startForegroundService(this, intent)

            Log.d(TAG, "✅ Started $name")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Failed $name, retrying...", e)

            handler.postDelayed({
                startSafe(service, name)
            }, 4000)
        }
    }

    // ============================
    // FOREGROUND NOTIFICATION
    // ============================
    private fun startForegroundSafe() {

        val manager = getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "System Boot Orchestrator",
                NotificationManager.IMPORTANCE_LOW
            )

            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Protection System Active")
            .setContentText("Restoring child protection services")
            .setOngoing(true)
            .build()

        startForeground(NOTIF_ID, notification)
    }

    private fun startVpnWatchdog() {

        val request = PeriodicWorkRequestBuilder<VpnHealthWorker>(
            15, TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "vpn_watchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.e(TAG, "💀 Orchestrator destroyed")

        handler.removeCallbacksAndMessages(null)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}