package com.parentalcontrol.childapp.worker

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.parentalcontrol.childapp.service.ScreenTimeService
import com.parentalcontrol.childapp.service.MonitoringService
import com.parentalcontrol.childapp.vpn.DnsVpnService

class ServiceHealthWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
    }

    // =====================================================
    // WORK EXECUTION
    // =====================================================

    override fun doWork(): Result {

        Log.e(TAG, "🔥 HEALTH CHECK STARTED")

        try {

            // =====================================
            // CHECK MONITORING SERVICE
            // =====================================

            if (!isServiceRunning(MonitoringService::class.java)) {

                Log.e(
                    TAG,
                    "⚠ MonitoringService NOT running → restarting"
                )

                startServiceSafe(MonitoringService::class.java)

            } else {

                Log.e(
                    TAG,
                    "✅ MonitoringService already running"
                )
            }

            // =====================================
            // CHECK SCREEN TIME SERVICE
            // =====================================

            if (!isServiceRunning(ScreenTimeService::class.java)) {

                Log.e(
                    TAG,
                    "⚠ ScreenTimeService NOT running → restarting"
                )

                startServiceSafe(ScreenTimeService::class.java)

            } else {

                Log.e(
                    TAG,
                    "✅ ScreenTimeService already running"
                )
            }

            // =====================================
            // CHECK VPN SERVICE
            // =====================================

            if (!DnsVpnService.isRunning) {

                Log.e(
                    TAG,
                    "⚠ VPN NOT running → restarting"
                )

                startServiceSafe(DnsVpnService::class.java)

            } else {

                Log.e(
                    TAG,
                    "✅ VPN already running"
                )
            }

            Log.e(TAG, "✅ HEALTH CHECK COMPLETE")

            return Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ HEALTH CHECK FAILED",
                e
            )

            return Result.retry()
        }
    }

    // =====================================================
    // SAFE SERVICE STARTER
    // =====================================================

    private fun startServiceSafe(
        serviceClass: Class<*>
    ) {

        try {

            val intent =
                Intent(
                    applicationContext,
                    serviceClass
                )

            Log.e(
                TAG,
                "🚀 Starting ${serviceClass.simpleName}"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                ContextCompat.startForegroundService(
                    applicationContext,
                    intent
                )

            } else {

                applicationContext.startService(intent)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed starting ${serviceClass.simpleName}",
                e
            )
        }
    }

    // =====================================================
    // CHECK IF SERVICE RUNNING
    // =====================================================

    private fun isServiceRunning(
        serviceClass: Class<*>
    ): Boolean {

        return try {

            val manager =
                applicationContext.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager

            @Suppress("DEPRECATION")
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {

                if (serviceClass.name ==
                    service.service.className
                ) {

                    return true
                }
            }

            false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Service check failed",
                e
            )

            false
        }
    }
}