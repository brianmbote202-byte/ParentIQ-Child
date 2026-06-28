package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.vpn.DnsVpnService

class PowerStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "PowerStateReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {

        val action = intent?.action ?: return

        Log.e(TAG, "🔥 EVENT = $action")

        if (DnsVpnService.isRunning) {

            Log.e(TAG, "VPN already running")

            return
        }

        try {

            val vpnIntent =
                Intent(
                    context,
                    DnsVpnService::class.java
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                ContextCompat.startForegroundService(
                    context,
                    vpnIntent
                )

            } else {

                context.startService(vpnIntent)
            }

            Log.e(TAG, "✅ VPN restart requested")

        } catch (e: Exception) {

            Log.e(TAG, "❌ VPN restart failed", e)
        }
    }
}