package com.parentalcontrol.childapp.onboarding.utils

import android.Manifest
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat

object OnboardingUtils {

    fun isDeviceAdminEnabled(context: Context, admin: ComponentName): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm.isAdminActive(admin)
    }

    fun isVpnGranted(context: Context): Boolean {
        return VpnService.prepare(context) == null
    }

    fun isAccessibilityEnabled(context: Context, serviceId: String): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(":").any { it.equals(serviceId, true) }
    }

    fun isRestrictedSettingsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            val appOps = context.getSystemService(AppOpsManager::class.java)
            appOps.unsafeCheckOpNoThrow(
                "android:access_restricted_settings",
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            true
        }
    }

    fun isLocationGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isSamsung(): Boolean = Build.MANUFACTURER.equals("samsung", true)
}
