package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.core.SystemBootOrchestrator
import com.parentalcontrol.childapp.service.SelfHealingService
import com.parentalcontrol.childapp.vpn.VpnController
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {

        val action = intent?.action ?: return

        if (
            action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {

            Log.e(TAG, "🔥 BOOT RECEIVED")

            val pendingResult = goAsync()

            val appContext =
                context.applicationContext

            val prefs =
                appContext.getSharedPreferences(
                    "child_prefs",
                    Context.MODE_PRIVATE
                )

            prefs.edit()
                .putBoolean(
                    "screen_capture_granted",
                    false
                )
                .apply()

            val executor =
                Executors.newSingleThreadScheduledExecutor()

            try {

                // START CORE SERVICE
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(
                        appContext,
                        SystemBootOrchestrator::class.java
                    )
                )

                // START SELF HEAL
                ContextCompat.startForegroundService(
                    appContext,
                    Intent(
                        appContext,
                        SelfHealingService::class.java
                    )
                )

                // DELAY VPN START
                executor.schedule({

                    try {

                        Log.e(TAG, "🌐 STARTING VPN")

                        VpnController.startVpn(
                            appContext
                        )

                        Log.e(TAG, "✅ VPN START SENT")

                    } catch (e: Exception) {

                        Log.e(TAG, "❌ VPN START FAILED", e)
                    }

                    pendingResult.finish()

                }, 20, TimeUnit.SECONDS)

            } catch (e: Exception) {

                Log.e(TAG, "❌ BOOT ERROR", e)

                pendingResult.finish()
            }
        }
    }
}