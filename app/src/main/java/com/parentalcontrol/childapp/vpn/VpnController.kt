package com.parentalcontrol.childapp.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object VpnController {

    private const val TAG = "VpnController"

    /**
     * Starts the VPN when the application has permission
     * to establish the VPN tunnel.
     *
     * This method is intended for a legitimate user/app
     * initiated start.
     *
     * It does NOT implement an automatic retry loop.
     * Recovery is handled separately by the VPN lifecycle.
     */
    fun startVpn(context: Context) {

        val appContext =
            context.applicationContext

        Log.e(
            TAG,
            "🔥 startVpn() called"
        )

        try {

            val prefs =
                appContext.getSharedPreferences(
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
                    "🚫 VPN disabled in child settings"
                )

                return
            }

            // =================================================
            // VPN PERMISSION
            // =================================================

            val prepareIntent =
                VpnService.prepare(appContext)

            if (prepareIntent != null) {

                Log.e(
                    TAG,
                    "⚠️ VPN permission is not available"
                )

                Log.e(
                    TAG,
                    "ℹ️ User authorization is required"
                )

                return
            }

            // =================================================
            // START VPN
            // =================================================

            val intent =
                Intent(
                    appContext,
                    DnsVpnService::class.java
                )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                ContextCompat.startForegroundService(
                    appContext,
                    intent
                )

            } else {

                appContext.startService(
                    intent
                )
            }

            Log.e(
                TAG,
                "✅ VPN start request sent"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN start request failed",
                e
            )
        }
    }

    /**
     * Explicitly stops the VPN.
     *
     * This should only be called when the application/user
     * intentionally disables VPN protection.
     */
    fun stopVpn(context: Context) {

        val appContext =
            context.applicationContext

        try {

            appContext.stopService(
                Intent(
                    appContext,
                    DnsVpnService::class.java
                )
            )

            Log.e(
                TAG,
                "🛑 VPN stop requested"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN stop failed",
                e
            )
        }
    }
}