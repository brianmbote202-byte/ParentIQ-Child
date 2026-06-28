package com.parentalcontrol.childapp.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.service.ServiceRegistry

class SystemHealthManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

    fun validateAndRepair() {

        for (service in ServiceRegistry.CORE_SERVICES) {

            if (!isServiceRunning(service)) {
                restartService(service)
            }
        }

        checkAccessibility()
        checkVpnState()
        checkScreenCaptureState()
    }

    // ---------------- CHECK SERVICE ----------------
    private fun isServiceRunning(service: Class<*>): Boolean {

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        return manager.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == service.name
        }
    }

    // ---------------- RESTART SERVICE ----------------
    private fun restartService(service: Class<*>) {

        try {
            val intent = Intent(context, service)

            ContextCompat.startForegroundService(context, intent)

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ---------------- ACCESSIBILITY ----------------
    private fun checkAccessibility() {

        val enabled = android.provider.Settings.Secure.getString(
            context.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val active = enabled.contains("BrowserAccessibilityService")

        if (!active) {
            notify("Accessibility disabled")
        }
    }

    // ---------------- VPN ----------------
    private fun checkVpnState() {

        val vpnAllowed = prefs.getBoolean("vpn_allowed", true)

        if (!vpnAllowed) return

        // simple ping check (light validation)
        val running = com.parentalcontrol.childapp.vpn.DnsVpnService.isRunning
        if (!running) {
            restartService(com.parentalcontrol.childapp.vpn.DnsVpnService::class.java)
        }
    }

    // ---------------- SCREEN CAPTURE ----------------
    private fun checkScreenCaptureState() {

        val granted =
            prefs.getBoolean(
                "screen_capture_granted",
                false
            )

        if (!granted) {

            notify("Screen capture permission missing")

            return
        }

        val running =
            isServiceRunning(
                com.parentalcontrol.childapp.service.ScreenCaptureService::class.java
            )

        if (running) {
            return
        }

        // projection token lost after reboot/process death
        val resultCode =
            com.parentalcontrol.childapp.service.ScreenCaptureHolder.resultCode

        val data =
            com.parentalcontrol.childapp.service.ScreenCaptureHolder.data

        if (
            resultCode == null ||
            data == null
        ) {

            notify("Projection token unavailable")

            prefs.edit()
                .putBoolean(
                    "screen_capture_granted",
                    false
                )
                .apply()

            return
        }

        try {

            val intent =
                Intent(
                    context,
                    com.parentalcontrol.childapp.service.ScreenCaptureService::class.java
                ).apply {

                    putExtra(
                        "resultCode",
                        resultCode
                    )

                    putExtra(
                        "data",
                        data
                    )
                }

            ContextCompat.startForegroundService(
                context,
                intent
            )

            notify("ScreenCaptureService restarted")

        } catch (e: Exception) {

            notify("ScreenCapture restart failed")
            e.printStackTrace()
        }
    }
    private fun notify(msg: String) {
        android.util.Log.e("SYSTEM_HEALTH", msg)
    }
}