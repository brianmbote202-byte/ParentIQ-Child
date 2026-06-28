package com.parentalcontrol.childapp.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.parentalcontrol.childapp.vpn.VpnController

class VpnHealthWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {

        val prefs = applicationContext.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val vpnAllowed = prefs.getBoolean("vpn_allowed", true)

        if (!vpnAllowed) return Result.success()

        // 🔥 IMMORTAL CHECK
        if (!VpnStateChecker.isVpnRunning(applicationContext)) {
            VpnController.startVpn(applicationContext)
        }

        return Result.success()
    }
}