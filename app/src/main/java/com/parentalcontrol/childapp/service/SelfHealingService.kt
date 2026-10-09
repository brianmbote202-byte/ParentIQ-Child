package com.parentalcontrol.childapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.setup.AccessibilityUtils
import com.parentalcontrol.childapp.vpn.DnsVpnService

class SelfHealingService : Service() {

    companion object {

        private const val TAG =
            "SelfHealingService"

        private const val CHANNEL_ID =
            "self_heal_channel"

        private const val NOTIFICATION_ID =
            2001

        private const val CHECK_INTERVAL =
            10_000L
    }

    // ============================================================
    // HANDLER
    // ============================================================

    private val handler =
        Handler(Looper.getMainLooper())

    private var monitoringStarted = false

    // ============================================================
    // HEALTH CHECK
    // ============================================================

    private val checkRunnable =
        object : Runnable {

            override fun run() {

                if (!monitoringStarted) {
                    return
                }

                try {
                    checkAccessibility()
                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Accessibility health check failed",
                        e
                    )
                }

                try {
                    checkVpn()
                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ VPN health check failed",
                        e
                    )
                }

                handler.postDelayed(
                    this,
                    CHECK_INTERVAL
                )
            }
        }

    // ============================================================
    // CREATE
    // ============================================================

    override fun onCreate() {

        super.onCreate()

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥 SelfHealingService CREATED"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        startForegroundSafe()

        monitoringStarted = true

        handler.removeCallbacks(checkRunnable)

        handler.post(checkRunnable)
    }

    // ============================================================
    // START COMMAND
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(
            TAG,
            "🚀 SelfHealingService STARTED"
        )

        /*
         * START_STICKY allows Android to recreate this service
         * if its process is killed.
         *
         * Boot recovery itself is handled by:
         *
         * BootReceiver
         *      ↓
         * SystemBootOrchestrator
         */

        return START_STICKY
    }

    // ============================================================
    // ACCESSIBILITY HEALTH
    // ============================================================

    private fun checkAccessibility() {

        val enabled =
            try {

                AccessibilityUtils
                    .isAccessibilityEnabled(
                        applicationContext
                    )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Could not determine Accessibility state",
                    e
                )

                return
            }

        if (enabled) {

            Log.d(
                TAG,
                "👁 Accessibility = ENABLED"
            )

        } else {

            Log.e(
                TAG,
                "⚠️ Accessibility = DISABLED"
            )

            /*
             * IMPORTANT:
             *
             * We deliberately DO NOT open
             * ACTION_ACCESSIBILITY_SETTINGS here.
             *
             * Accessibility cannot safely be forced on by
             * starting BrowserAccessibilityService ourselves.
             *
             * If the user has enabled the service, Android's
             * Accessibility framework is responsible for
             * reconnecting BrowserAccessibilityService.
             */
        }
    }

    // ============================================================
    // VPN HEALTH
    // ============================================================

    private fun checkVpn() {

        val running =
            DnsVpnService.isRunning

        if (running) {

            Log.d(
                TAG,
                "🌐 VPN = RUNNING"
            )

        } else {

            Log.e(
                TAG,
                "⚠️ VPN = NOT RUNNING"
            )

            /*
             * Do NOT call VpnController.startVpn() here.
             *
             * SystemBootOrchestrator is now the central VPN
             * startup/recovery mechanism.
             *
             * Calling VpnController from here as well would
             * create competing VPN startup paths.
             */
        }
    }

    // ============================================================
    // FOREGROUND NOTIFICATION
    // ============================================================

    private fun startForegroundSafe() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Self Healing Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Monitors child protection services"

            manager.createNotificationChannel(
                channel
            )
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_vpn
                )
                .setContentTitle(
                    "Protection Active"
                )
                .setContentText(
                    "Monitoring protection services"
                )
                .setOngoing(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )

        Log.d(
            TAG,
            "✅ Self-healing foreground service active"
        )
    }

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        Log.e(
            TAG,
            "💀 SelfHealingService DESTROYED"
        )

        monitoringStarted = false

        handler.removeCallbacksAndMessages(
            null
        )

        super.onDestroy()
    }

    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}