package com.parentalcontrol.childapp.vpn

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
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.R

class VpnAutoRestartService : Service() {

    companion object {

        private const val TAG = "VPN_LOOP"

        private const val CHANNEL_ID =
            "vpn_watchdog_channel"

        private const val NOTIFICATION_ID = 2001
    }

    private val handler =
        Handler(Looper.getMainLooper())

    // =====================================================
    // WATCHDOG LOOP
    // =====================================================

    private val vpnWatchdog =
        object : Runnable {

            override fun run() {

                try {

                    val prefs =
                        getSharedPreferences(
                            "child_prefs",
                            MODE_PRIVATE
                        )

                    val allowed =
                        prefs.getBoolean(
                            "vpn_allowed",
                            true
                        )

                    // =================================================
                    // PARENT DISABLED VPN
                    // =================================================

                    if (!allowed) {

                        Log.e(
                            TAG,
                            "VPN disabled by parent"
                        )

                        stopSelf()

                        return
                    }

                    // =================================================
                    // HEARTBEAT CHECK
                    // =================================================

                    val now =
                        System.currentTimeMillis()

                    val dead =
                        now - DnsVpnService.lastHeartbeat > 15000L

                    // =================================================
                    // VPN DEAD
                    // =================================================

                    if (
                        !DnsVpnService.isRunning ||
                        dead
                    ) {

                        Log.e(
                            TAG,
                            "VPN DEAD → restarting"
                        )

                        try {

                            VpnController.startVpn(
                                applicationContext
                            )

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "Restart failed",
                                e
                            )
                        }

                    } else {

                        Log.e(
                            TAG,
                            "VPN healthy"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Watchdog crash",
                        e
                    )
                }

                // =================================================
                // ALWAYS RESCHEDULE
                // =================================================

                handler.postDelayed(
                    this,
                    10000
                )
            }
        }

    // =====================================================
    // CREATE
    // =====================================================

    override fun onCreate() {
        super.onCreate()

        startForegroundSafe()

        Log.e(TAG, "WATCHDOG CREATED")
    }

    // =====================================================
    // START
    // =====================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(TAG, "WATCHDOG STARTED")

        handler.removeCallbacks(vpnWatchdog)

        handler.post(vpnWatchdog)

        return START_STICKY
    }

    // =====================================================
    // FOREGROUND
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
                    "VPN Watchdog",
                    NotificationManager.IMPORTANCE_MIN
                )

            manager.createNotificationChannel(
                channel
            )
        }

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle(
                    "VPN Protection Active"
                )
                .setContentText(
                    "Keeping protection alive"
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

        handler.removeCallbacksAndMessages(null)

        Log.e(TAG, "WATCHDOG DESTROYED")

        // AUTO RESTART WATCHDOG
        try {

            val intent =
                Intent(
                    applicationContext,
                    VpnAutoRestartService::class.java
                )

            ContextCompat.startForegroundService(
                applicationContext,
                intent
            )

        } catch (_: Exception) {
        }

        super.onDestroy()
    }

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}