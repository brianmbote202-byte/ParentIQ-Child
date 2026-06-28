package com.parentalcontrol.childapp.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.telephony.TelephonyManager
import android.os.Build

object SecurityUtils {

    private const val PREF_NAME = "device_security"
    private const val KEY_ORIGINAL_SIM = "original_sim"
    private const val KEY_DEVICE_ID = "device_id"

    // --------------------------------
    // SIM FINGERPRINT (STRONG METHOD)
    // --------------------------------
    private fun getSimFingerprint(context: Context): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            val operator = tm.simOperator ?: "unknown_operator"
            val country = tm.simCountryIso ?: "unknown_country"
            val network = tm.networkOperator ?: "unknown_network"

            val manufacturer = Build.MANUFACTURER ?: "unknown"
            val model = Build.MODEL ?: "unknown"

            "$operator-$country-$network-$manufacturer-$model"
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    // --------------------------------
    // PUBLIC GET SIM IDENTIFIER
    // --------------------------------
    fun getSimIdentifier(context: Context): String {
        // Fully public wrapper to avoid breaking existing code
        return getSimFingerprint(context)
    }

    // --------------------------------
    // SAVE ORIGINAL SIM (FIRST RUN)
    // --------------------------------
    fun saveOriginalSim(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (!prefs.contains(KEY_ORIGINAL_SIM)) {
            val fingerprint = getSimFingerprint(context)
            prefs.edit()
                .putString(KEY_ORIGINAL_SIM, fingerprint)
                .apply()
        }
    }

    // --------------------------------
    // CHECK SIM SWAP
    // --------------------------------
    fun checkSimSwap(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val originalSim = prefs.getString(KEY_ORIGINAL_SIM, null)
        val currentSim = getSimFingerprint(context)

        return originalSim != null && originalSim != currentSim
    }

    // --------------------------------
    // SAVE ORIGINAL DEVICE ID
    // --------------------------------
    fun saveDeviceId(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        if (!prefs.contains(KEY_DEVICE_ID)) {
            val deviceId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID
            )

            prefs.edit()
                .putString(KEY_DEVICE_ID, deviceId)
                .apply()
        }
    }

    // --------------------------------
    // DETECT DEVICE RESET
    // --------------------------------
    fun isDeviceReset(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

        val savedId = prefs.getString(KEY_DEVICE_ID, null)
        val currentId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )

        return savedId != null && savedId != currentId
    }

    fun isAutoStartActuallyEnabled(context: Context): Boolean {

        val prefs = context.getSharedPreferences(
            "setup_prefs",
            Context.MODE_PRIVATE
        )

        val done = prefs.getBoolean("auto_start_done", false)

        val attempted = prefs.getBoolean("auto_start_attempted", false)

        return when {
            Build.MANUFACTURER.lowercase().contains("tecno") -> done
            Build.MANUFACTURER.lowercase().contains("infinix") -> done
            Build.MANUFACTURER.lowercase().contains("samsung") -> attempted || done
            else -> done
        }
    }

    fun openAutoStartSettings(context: Context) {

        try {

            val intent = Intent()

            intent.component = ComponentName(
                "com.transsion.phonemaster",
                "com.cyin.himgr.autostart.AutoStartActivity"
            )

            context.startActivity(intent)

        } catch (e: Exception) {

            context.startActivity(
                Intent(Settings.ACTION_SETTINGS)
            )
        }
    }
}