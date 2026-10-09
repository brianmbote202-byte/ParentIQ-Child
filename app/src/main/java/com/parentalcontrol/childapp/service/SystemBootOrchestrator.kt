package com.parentalcontrol.childapp.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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
import com.parentalcontrol.childapp.service.AppSyncService
import com.parentalcontrol.childapp.service.ChildAppControlService
import com.parentalcontrol.childapp.service.ChildLocationService
import com.parentalcontrol.childapp.vpn.DnsVpnService
import com.parentalcontrol.childapp.service.MonitoringService
import com.parentalcontrol.childapp.service.ScreenTimeService
import com.parentalcontrol.childapp.worker.VpnHealthWorker
import java.util.concurrent.TimeUnit

class SystemBootOrchestrator : Service() {

    companion object {

        private const val TAG =
            "SystemBootOrchestrator"

        private const val CHANNEL_ID =
            "system_boot_channel"

        private const val NOTIF_ID =
            9090
    }

    private val handler =
        Handler(Looper.getMainLooper())

    private var started =
        false

    // ========================================================
    // CREATE
    // ========================================================

    override fun onCreate() {

        super.onCreate()

        startForegroundSafe()

        startVpnWatchdog()

        Log.e(
            TAG,
            "🚀 BOOT ORCHESTRATOR CREATED"
        )
    }

    // ========================================================
    // START COMMAND
    // ========================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (started) {

            Log.d(
                TAG,
                "⚠️ Recovery already running"
            )

            return START_STICKY
        }

        started = true

        Log.e(
            TAG,
            "🔥 STARTING FULL POST-BOOT RECOVERY"
        )

        runRecoverySequence()

        return START_STICKY
    }

    // ========================================================
    // FULL RECOVERY SEQUENCE
    // ========================================================

    private fun runRecoverySequence() {

        Log.e(
            TAG,
            "=========================================="
        )

        Log.e(
            TAG,
            "🚀 FULL CHILD PROTECTION RECOVERY"
        )

        Log.e(
            TAG,
            "=========================================="
        )

        // ----------------------------------------------------
        // 0 SECONDS
        // ----------------------------------------------------

        startGuardian()

        // ----------------------------------------------------
        // 1 SECOND
        // ----------------------------------------------------

        handler.postDelayed({

            startScreenTime()

        }, 1_000L)

        // ----------------------------------------------------
        // 2 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            startLocation()

        }, 2_000L)

        // ----------------------------------------------------
        // 3 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            startAppSync()

        }, 3_000L)

        // ----------------------------------------------------
        // 4 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            startChildAppControl()

        }, 4_000L)

        // ----------------------------------------------------
        // 5 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            startVpnSafely()

        }, 5_000L)

        // ----------------------------------------------------
        // 7 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            startMonitoring()

        }, 7_000L)

        // ----------------------------------------------------
        // 10 SECONDS
        // ----------------------------------------------------

        handler.postDelayed({

            Log.e(
                TAG,
                "=========================================="
            )

            Log.e(
                TAG,
                "✅ POST-BOOT RECOVERY COMPLETE"
            )

            Log.e(
                TAG,
                "=========================================="
            )

            started = false

            stopSelf()

        }, 10_000L)
    }

    // ========================================================
    // GUARDIAN
    // ========================================================

    private fun startGuardian() {

        startSafe(
            GuardianService::class.java,
            "GuardianService"
        )
    }

    // ========================================================
    // SCREEN TIME
    // ========================================================

    private fun startScreenTime() {

        startSafe(
            ScreenTimeService::class.java,
            "ScreenTimeService"
        )
    }

    // ========================================================
    // LOCATION
    // ========================================================

    private fun startLocation() {

        startSafe(
            ChildLocationService::class.java,
            "ChildLocationService"
        )
    }

    // ========================================================
    // APP SYNC
    // ========================================================

    private fun startAppSync() {

        startSafe(
            AppSyncService::class.java,
            "AppSyncService"
        )
    }

    // ========================================================
    // APP CONTROL
    // ========================================================

    private fun startChildAppControl() {

        startSafe(
            ChildAppControlService::class.java,
            "ChildAppControlService"
        )
    }

    // ========================================================
    // MONITORING
    // ========================================================

    private fun startMonitoring() {

        startSafe(
            MonitoringService::class.java,
            "MonitoringService"
        )
    }

    // ========================================================
    // VPN
    // ========================================================

    private fun startVpnSafely() {

        try {

            val prefs =
                getSharedPreferences(
                    "child_prefs",
                    Context.MODE_PRIVATE
                )

            val vpnAllowed =
                prefs.getBoolean(
                    "vpn_allowed",
                    true
                )

            if (!vpnAllowed) {

                Log.e(
                    TAG,
                    "🚫 VPN disabled by child settings"
                )

                return
            }

            // ------------------------------------------------
            // CHECK VPN PERMISSION
            // ------------------------------------------------

            val prepareIntent =
                VpnService.prepare(this)

            if (prepareIntent != null) {

                Log.e(
                    TAG,
                    "⚠️ VPN permission is not available"
                )

                return
            }

            // ------------------------------------------------
            // ALREADY RUNNING
            // ------------------------------------------------

            if (DnsVpnService.isRunning) {

                Log.e(
                    TAG,
                    "✅ VPN already running"
                )

                return
            }

            // ------------------------------------------------
            // START VPN
            // ------------------------------------------------

            val intent =
                Intent(
                    this,
                    DnsVpnService::class.java
                )

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(
                TAG,
                "🌐 VPN START REQUEST SENT"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN START FAILED",
                e
            )
        }
    }

    // ========================================================
    // SAFE SERVICE START
    // ========================================================

    private fun startSafe(
        service: Class<*>,
        name: String
    ) {

        try {

            val intent =
                Intent(
                    this,
                    service
                )

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.e(
                TAG,
                "✅ START REQUEST SENT: $name"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ FAILED TO START $name",
                e
            )

            // Retry once after 4 seconds.
            handler.postDelayed({

                try {

                    val retryIntent =
                        Intent(
                            this,
                            service
                        )

                    ContextCompat.startForegroundService(
                        this,
                        retryIntent
                    )

                    Log.e(
                        TAG,
                        "🔄 RETRY START SENT: $name"
                    )

                } catch (retryError: Exception) {

                    Log.e(
                        TAG,
                        "❌ RETRY FAILED: $name",
                        retryError
                    )
                }

            }, 4_000L)
        }
    }

    // ========================================================
    // FOREGROUND NOTIFICATION
    // ========================================================

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
                    "System Boot Orchestrator",
                    NotificationManager.IMPORTANCE_LOW
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
                .setSmallIcon(
                    R.drawable.ic_shield
                )
                .setContentTitle(
                    "Protection System Active"
                )
                .setContentText(
                    "Restoring child protection services"
                )
                .setOngoing(true)
                .build()

        startForeground(
            NOTIF_ID,
            notification
        )
    }

    // ========================================================
    // VPN WATCHDOG
    // ========================================================

    private fun startVpnWatchdog() {

        try {

            val request =
                PeriodicWorkRequestBuilder<VpnHealthWorker>(
                    15,
                    TimeUnit.MINUTES
                ).build()

            WorkManager
                .getInstance(this)
                .enqueueUniquePeriodicWork(
                    "vpn_watchdog",
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )

            Log.d(
                TAG,
                "✅ VPN watchdog scheduled"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to schedule VPN watchdog",
                e
            )
        }
    }

    // ========================================================
    // DESTROY
    // ========================================================

    override fun onDestroy() {

        Log.e(
            TAG,
            "💀 BOOT ORCHESTRATOR DESTROYED"
        )

        handler.removeCallbacksAndMessages(
            null
        )

        started = false

        super.onDestroy()
    }

    // ========================================================
    // BIND
    // ========================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}