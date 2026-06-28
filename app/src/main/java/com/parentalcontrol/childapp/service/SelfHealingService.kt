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
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.accessibility.BrowserAccessibilityService
import com.parentalcontrol.childapp.setup.AccessibilityUtils
import com.parentalcontrol.childapp.vpn.VpnController

class SelfHealingService : Service() {

    companion object {
        private const val TAG = "SelfHealingService"

        private const val CHANNEL_ID =
            "self_heal_channel"

        private const val NOTIFICATION_ID = 2001
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private val interval = 10_000L

    private val checkRunnable =
        object : Runnable {

            override fun run() {

                try {

                    val enabled =
                        AccessibilityUtils.isAccessibilityEnabled(
                            applicationContext
                        )

                    Log.e(
                        TAG,
                        "👁 Accessibility = $enabled"
                    )

                    if (!enabled) {
                        triggerRecovery()
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Self-heal check failed",
                        e
                    )
                }

                if (!com.parentalcontrol.childapp.vpn.DnsVpnService.isRunning) {

                    Log.e(TAG, "⚠ VPN OFF → restarting")

                    try {

                        VpnController.startVpn(applicationContext)

                    } catch (e: Exception) {

                        Log.e(TAG, "❌ VPN restart failed", e)
                    }
                }

                handler.postDelayed(
                    this,
                    interval
                )
            }
        }

    // =====================================================
    // CREATE
    // =====================================================

    override fun onCreate() {
        super.onCreate()

        Log.e(TAG, "🔥 SelfHealingService CREATED")

        startForegroundSafe()

        handler.post(checkRunnable)
    }

    // =====================================================
    // START COMMAND
    // =====================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(TAG, "🚀 SelfHealingService STARTED")

        return START_STICKY
    }

    // =====================================================
    // RECOVERY LOGIC
    // =====================================================

    private fun triggerRecovery() {

        Log.e(
            TAG,
            "⚠ Accessibility OFF → recovering system"
        )

        // Restart VPN safely
        try {

            VpnController.startVpn(
                applicationContext
            )

            Log.e(
                TAG,
                "✅ VPN restart requested"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN restart failed",
                e
            )
        }

        // Open accessibility settings
        try {

            val intent =
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            startActivity(intent)

            Log.e(
                TAG,
                "⚙ Opened accessibility settings"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed opening accessibility settings",
                e
            )
        }

        try {

            val intent = Intent(
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            )

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            startActivity(intent)

        } catch (_: Exception) {
        }
    }

    // =====================================================
    // FOREGROUND NOTIFICATION
    // =====================================================

    private fun startForegroundSafe() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Self Healing Service",
                    NotificationManager.IMPORTANCE_LOW
                )

            channel.description =
                "Keeps monitoring services alive"

            manager.createNotificationChannel(channel)
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle(
                    "Protection Active"
                )
                .setContentText(
                    "Monitoring system integrity"
                )
                .setOngoing(true)
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {
        super.onDestroy()

        Log.e(TAG, "💀 SelfHealingService DESTROYED")

        handler.removeCallbacksAndMessages(null)
    }

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}