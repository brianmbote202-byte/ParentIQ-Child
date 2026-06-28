package com.parentalcontrol.childapp.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

object VpnController {

    private const val TAG = "VpnController"

    private var lastStart = 0L
    private var retryCount = 0

    fun startVpn(context: Context) {

        val now = System.currentTimeMillis()

        // 🔥 anti-spam guard
        if (now - lastStart < 5000) {
            Log.e(TAG, "⛔ VPN start throttled")
            return
        }

        Log.e(TAG, "🔥 startVpn() called")

        lastStart = now

        val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

        if (!prefs.getBoolean("vpn_allowed", true)) {
            Log.e(TAG, "❌ VPN disabled in prefs")
            return
        }

        try {

            // 🔥 CRITICAL FIX: VPN permission check
            val prepareIntent = VpnService.prepare(context)

            if (prepareIntent != null) {
                Log.e(TAG, "❌ VPN permission NOT granted (user must approve once)")
                return
            }

            Log.e(TAG, "🚀 Starting VPN service")

            val intent = Intent(context, DnsVpnService::class.java)
            ContextCompat.startForegroundService(context, intent)

            retryCount = 0

        } catch (e: Exception) {

            Log.e(TAG, "❌ VPN start failed", e)

            retryCount++

            if (retryCount <= 2) {

                Handler(Looper.getMainLooper()).postDelayed({
                    startVpn(context)
                }, 5000 * retryCount.toLong())
            }
        }
    }

    fun stopVpn(context: Context) {

        try {
            context.stopService(Intent(context, DnsVpnService::class.java))
            Log.e(TAG, "🛑 VPN stopped")
        } catch (e: Exception) {
            Log.e(TAG, "❌ VPN stop failed", e)
        }
    }
}