package com.parentalcontrol.childapp.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class VpnHealthWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "VpnHealthWorker"
        private const val PREFS_NAME = "child_prefs"
    }

    override fun doWork(): Result {

        Log.e(TAG, "==========================================")
        Log.e(TAG, "🌐 VPN HEALTH CHECK STARTED")

        return try {

            val prefs =
                applicationContext.getSharedPreferences(
                    PREFS_NAME,
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
                    "🚫 VPN disabled by child settings"
                )

                return Result.success()
            }

            val running =
                VpnStateChecker.isVpnRunning(
                    applicationContext
                )

            if (running) {

                Log.e(
                    TAG,
                    "✅ VPN is running"
                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ VPN is NOT running"
                )

                Log.e(
                    TAG,
                    "ℹ️ VPN start is NOT attempted from WorkManager"
                )

                Log.e(
                    TAG,
                    "ℹ️ Recovery must use the VPN-specific lifecycle"
                )
            }

            Log.e(TAG, "✅ VPN HEALTH CHECK COMPLETE")
            Log.e(TAG, "==========================================")

            Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN HEALTH CHECK FAILED",
                e
            )

            Result.retry()
        }
    }
}