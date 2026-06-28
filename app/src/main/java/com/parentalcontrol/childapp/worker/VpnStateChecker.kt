package com.parentalcontrol.childapp.worker

import android.content.Context
import android.app.ActivityManager

object VpnStateChecker {

    fun isVpnRunning(context: Context): Boolean {

        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (service.service.className.contains("DnsVpnService")) {
                return true
            }
        }

        return false
    }
}