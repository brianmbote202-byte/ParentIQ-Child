package com.parentalcontrol.childapp.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class BootRecoveryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(
    appContext,
    workerParams
) {

    companion object {

        private const val TAG =
            "BootRecoveryWorker"

        private const val PREFS_NAME =
            "child_prefs"

        private const val CHILD_ID_KEY =
            "child_id"

        private const val ONBOARDING_DONE_KEY =
            "onboarding_done"
    }

    // =========================================================
    // WORK EXECUTION
    // =========================================================

    override suspend fun doWork(): Result {

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥 BOOT RECOVERY WORKER STARTED"
        )

        Log.e(
            TAG,
            "🔥 Worker attempt = $runAttemptCount"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        return try {

            // =================================================
            // READ CHILD PREFERENCES
            // =================================================

            val prefs =
                applicationContext.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )

            val childId =
                prefs
                    .getString(
                        CHILD_ID_KEY,
                        null
                    )
                    ?.trim()

            // =================================================
            // CHILD ID VALIDATION
            // =================================================

            if (childId.isNullOrBlank()) {

                Log.e(
                    TAG,
                    "⚠️ No childId found"
                )

                Log.e(
                    TAG,
                    "⏸️ Child has not been paired"
                )

                Log.e(
                    TAG,
                    "🏁 Nothing to recover"
                )

                return Result.success()
            }

            Log.e(
                TAG,
                "👶 Child ID found"
            )

            Log.e(
                TAG,
                "👶 childId=$childId"
            )

            // =================================================
            // ONBOARDING VALIDATION
            // =================================================

            val onboardingDone =
                prefs.getBoolean(
                    ONBOARDING_DONE_KEY,
                    false
                )

            if (!onboardingDone) {

                Log.e(
                    TAG,
                    "⚠️ onboarding_done=false"
                )

                Log.e(
                    TAG,
                    "⏸️ Protection recovery skipped"
                )

                return Result.success()
            }

            Log.e(
                TAG,
                "✅ Child onboarding confirmed"
            )

            // =================================================
            // RECOVERY START
            // =================================================

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "🛡️ CHILD PROTECTION RECOVERY"
            )

            Log.e(
                TAG,
                "🛡️ childId=$childId"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            // =================================================
            // IMPORTANT ANDROID 15+ RULE
            // =================================================
            //
            // DO NOT start the following services here:
            //
            // ChildAppControlService
            // ScreenTimeService
            // MonitoringService
            // AppSyncService
            //
            // while they are declared as dataSync foreground
            // services.
            //
            // Android 15+ does not allow dataSync foreground
            // services to be launched from BOOT_COMPLETED.
            //
            // These services must be migrated to appropriate
            // recovery mechanisms individually.
            //
            // =================================================

            Log.e(
                TAG,
                "⏭️ Continuous dataSync services NOT started"
            )

            // =================================================
            // LOCATION
            // =================================================
            //
            // ChildLocationService is different because it is
            // declared as a location foreground service.
            //
            // It can potentially be recovered after reboot when
            // the required background-location permission and
            // other runtime prerequisites are satisfied.
            //
            // We intentionally handle this separately rather
            // than treating it like dataSync.
            //
            // =================================================

            Log.e(
                TAG,
                "📍 Location recovery deferred to dedicated location recovery path"
            )

            // =================================================
            // VPN
            // =================================================
            //
            // DnsVpnService is also not treated as a normal
            // dataSync service.
            //
            // VPN persistence should use Android's VPN lifecycle
            // / Always-on VPN mechanisms where configured.
            //
            // Do not blindly launch the VPN from this worker.
            //
            // =================================================

            Log.e(
                TAG,
                "🔐 VPN recovery requires VPN-specific handling"
            )

            // =================================================
            // ACCESSIBILITY
            // =================================================
            //
            // BrowserAccessibilityService is controlled by the
            // Android Accessibility framework.
            //
            // We do not manually start it here.
            //
            // =================================================

            Log.e(
                TAG,
                "♿ Accessibility service left to Android"
            )

            // =================================================
            // SCREEN CAPTURE
            // =================================================
            //
            // MediaProjection cannot simply be restored as if
            // it were an ordinary persistent background service.
            //
            // BootReceiver already resets the stale state.
            //
            // =================================================

            Log.e(
                TAG,
                "📷 Screen capture recovery deferred"
            )

            // =================================================
// APP SYNCHRONIZATION
// =================================================
//
// App synchronization is finite work, so it is handled
// by WorkManager rather than launching AppSyncService as
// a dataSync foreground service from boot.
//
// =================================================

            Log.e(
                TAG,
                "📦 Scheduling installed-app synchronization"
            )

            val appSyncRequest =
                OneTimeWorkRequestBuilder<AppSyncWorker>()
                    .addTag("app_sync_after_boot")
                    .build()

            WorkManager
                .getInstance(applicationContext)
                .enqueueUniqueWork(
                    "AppSyncAfterBoot",
                    ExistingWorkPolicy.KEEP,
                    appSyncRequest
                )

            Log.e(
                TAG,
                "✅ AppSyncWorker scheduled"
            )
            Result.success()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ BOOT RECOVERY WORKER FAILED"
            )

            Log.e(
                TAG,
                "❌ Exception type = ${e::class.java.name}"
            )

            Log.e(
                TAG,
                "❌ Exception message = ${e.message}"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ FULL BOOT RECOVERY FAILURE",
                e
            )

            Result.retry()
        }
    }
}