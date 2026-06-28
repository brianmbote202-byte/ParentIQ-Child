package com.parentalcontrol.childapp.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.parentalcontrol.childapp.vpn.VpnController

class BootVpnWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "BootVpnWorker"
    }

    override fun doWork(): Result {

        Log.e(TAG, "🚀 BootVpnWorker executing")

        return try {

            // ✅ ALWAYS use controller (IMPORTANT FIX)
            VpnController.startVpn(applicationContext)

            Log.e(TAG, "✅ VPN requested via VpnController")

            Result.success()

        } catch (e: Exception) {

            Log.e(TAG, "❌ VPN start failed", e)

            Result.retry()
        }
    }
}