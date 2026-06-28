package com.parentalcontrol.childapp.setup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

object BatteryOptimizationHelper {

    fun requestDisableBatteryOptimization(
        context: Context
    ) {

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return
        }

        val powerManager =
            context.getSystemService(Context.POWER_SERVICE)
                    as PowerManager

        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {

            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            )

            intent.data =
                Uri.parse("package:${context.packageName}")

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            context.startActivity(intent)
        }
    }
}