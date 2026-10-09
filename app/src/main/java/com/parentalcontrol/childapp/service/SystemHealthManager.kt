package com.parentalcontrol.childapp.core

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import android.util.Log
import com.parentalcontrol.childapp.service.ServiceRegistry
import com.parentalcontrol.childapp.vpn.DnsVpnService

class SystemHealthManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "SYSTEM_HEALTH"
        private const val PREFS_NAME = "child_prefs"
    }

    private val prefs =
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun validateAndRepair() {

        Log.e(TAG, "==========================================")
        Log.e(TAG, "🛡️ SYSTEM HEALTH CHECK")

        checkCoreServices()
        checkAccessibility()
        checkVpnState()
        checkScreenCaptureState()

        Log.e(TAG, "✅ SYSTEM HEALTH CHECK COMPLETE")
        Log.e(TAG, "==========================================")
    }

    // ========================================================
    // CORE SERVICES
    // ========================================================

    private fun checkCoreServices() {

        for (service in ServiceRegistry.CORE_SERVICES) {

            try {

                if (isServiceRunning(service)) {

                    Log.d(
                        TAG,
                        "✅ ${service.simpleName} is running"
                    )

                } else {

                    Log.e(
                        TAG,
                        "⚠️ ${service.simpleName} is NOT running"
                    )

                    Log.e(
                        TAG,
                        "ℹ️ No automatic foreground-service restart from Guardian"
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Failed checking ${service.simpleName}",
                    e
                )
            }
        }
    }

    // ========================================================
    // SERVICE CHECK
    // ========================================================

    private fun isServiceRunning(
        service: Class<*>
    ): Boolean {

        return try {

            val manager =
                context.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager

            @Suppress("DEPRECATION")
            manager
                .getRunningServices(Int.MAX_VALUE)
                .any {
                    it.service.className == service.name
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Service check failed: ${service.simpleName}",
                e
            )

            false
        }
    }

    // ========================================================
    // ACCESSIBILITY
    // ========================================================

    private fun checkAccessibility() {

        try {

            val enabled =
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: ""

            val active =
                enabled.contains(
                    "BrowserAccessibilityService"
                )

            if (active) {

                Log.d(
                    TAG,
                    "✅ Accessibility service enabled"
                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ Accessibility service is NOT enabled"
                )

                Log.e(
                    TAG,
                    "ℹ️ Android manages AccessibilityService lifecycle"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Accessibility check failed",
                e
            )
        }
    }

    // ========================================================
    // VPN
    // ========================================================

    private fun checkVpnState() {

        try {

            val vpnAllowed =
                prefs.getBoolean(
                    "vpn_allowed",
                    true
                )

            if (!vpnAllowed) {

                Log.d(
                    TAG,
                    "🚫 VPN disabled by child settings"
                )

                return
            }

            if (DnsVpnService.isRunning) {

                Log.d(
                    TAG,
                    "✅ VPN reports running"
                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ VPN is NOT running"
                )

                Log.e(
                    TAG,
                    "ℹ️ VPN recovery handled separately"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN health check failed",
                e
            )
        }
    }

    // ========================================================
    // SCREEN CAPTURE
    // ========================================================

    private fun checkScreenCaptureState() {

        try {

            val granted =
                prefs.getBoolean(
                    "screen_capture_granted",
                    false
                )

            if (!granted) {

                Log.d(
                    TAG,
                    "ℹ️ Screen capture permission not granted"
                )

                return
            }

            val running =
                isServiceRunning(
                    com.parentalcontrol.childapp.service
                        .ScreenCaptureService::class.java
                )

            if (running) {

                Log.d(
                    TAG,
                    "✅ ScreenCaptureService is running"

                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ ScreenCaptureService is NOT running"
                )

                Log.e(
                    TAG,
                    "ℹ️ MediaProjection recovery requires valid user authorization"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Screen capture health check failed",
                e
            )
        }
    }
}