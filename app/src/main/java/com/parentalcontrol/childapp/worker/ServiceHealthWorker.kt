package com.parentalcontrol.childapp.worker

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.parentalcontrol.childapp.service.MonitoringService
import com.parentalcontrol.childapp.service.ScreenTimeService

class ServiceHealthWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    companion object {
        private const val TAG = "ServiceHealthWorker"
    }

    override fun doWork(): Result {

        Log.e(TAG, "==================================================")
        Log.e(TAG, "🔥 SERVICE HEALTH CHECK STARTED")

        return try {

            // =================================================
            // MONITORING SERVICE
            // =================================================

            val monitoringRunning =
                isServiceRunning(
                    MonitoringService::class.java
                )

            if (monitoringRunning) {

                Log.e(
                    TAG,
                    "✅ MonitoringService is running"
                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ MonitoringService is NOT running"
                )

                Log.e(
                    TAG,
                    "ℹ️ Recovery will be handled by the proper recovery path"
                )
            }

            // =================================================
            // SCREEN TIME SERVICE
            // =================================================

            val screenTimeRunning =
                isServiceRunning(
                    ScreenTimeService::class.java
                )

            if (screenTimeRunning) {

                Log.e(
                    TAG,
                    "✅ ScreenTimeService is running"
                )

            } else {

                Log.e(
                    TAG,
                    "⚠️ ScreenTimeService is NOT running"
                )

                Log.e(
                    TAG,
                    "ℹ️ Recovery will be handled by the proper recovery path"
                )
            }

            // =================================================
            // VPN
            // =================================================
            //
            // DO NOT use DnsVpnService.isRunning here.
            //
            // A static Boolean is lost when the Android process
            // is killed/recreated.
            //
            // VPN recovery needs to be handled by the VPN's
            // own lifecycle and configuration.
            //
            // =================================================

            Log.e(
                TAG,
                "ℹ️ VPN health check deferred to VPN-specific recovery"
            )

            // =================================================
            // COMPLETE
            // =================================================

            Log.e(
                TAG,
                "✅ SERVICE HEALTH CHECK COMPLETE"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ SERVICE HEALTH CHECK FAILED",
                e
            )

            Log.e(
                TAG,
                "=================================================="
            )

            Result.retry()
        }
    }

    // =========================================================
    // CHECK SERVICE
    // =========================================================

    private fun isServiceRunning(
        serviceClass: Class<*>
    ): Boolean {

        return try {

            val manager =
                applicationContext.getSystemService(
                    Context.ACTIVITY_SERVICE
                ) as ActivityManager

            @Suppress("DEPRECATION")
            manager.getRunningServices(Int.MAX_VALUE)
                .any { serviceInfo ->

                    serviceInfo.service.className ==
                            serviceClass.name
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed checking ${serviceClass.simpleName}",
                e
            )

            false
        }
    }
}