package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.parentalcontrol.childapp.worker.BootRecoveryWorker

class BootReceiver : BroadcastReceiver() {

    companion object {

        private const val TAG =
            "BootReceiver"

        private const val PREFS_NAME =
            "child_prefs"

        private const val SCREEN_CAPTURE_GRANTED =
            "screen_capture_granted"

        private const val BOOT_RECOVERY_WORK =
            "child_protection_boot_recovery"

    }

    // =========================================================
    // RECEIVE BOOT / PACKAGE EVENTS
    // =========================================================

    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥🔥🔥 BOOT RECEIVER FIRED 🔥🔥🔥"
        )

        Log.e(
            TAG,
            "🔥 Intent = $intent"
        )

        val action =
            intent?.action

        Log.e(
            TAG,
            "🔥 Action = $action"
        )

        Log.e(
            TAG,
            "🔥 Package = ${context.packageName}"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        // =====================================================
        // SAFETY CHECK
        // =====================================================

        if (action.isNullOrBlank()) {

            Log.e(
                TAG,
                "❌ Boot intent has no action"
            )

            Log.e(
                TAG,
                "🏁 BootReceiver finished"
            )

            return
        }

        // =====================================================
        // HANDLE RECOVERY EVENTS
        // =====================================================

        when (action) {

            // =================================================
            // LOCKED BOOT
            // =================================================
            //
            // The user may still be locked.
            //
            // child_prefs is credential-protected, so we do not
            // attempt the complete protection recovery here.
            //
            // Normal recovery will happen through
            // BOOT_COMPLETED after the user is unlocked.
            //
            // =================================================

            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {

                Log.e(
                    TAG,
                    "🔐 LOCKED_BOOT_COMPLETED RECEIVED"
                )

                Log.e(
                    TAG,
                    "🔐 Device may still be locked"
                )

                Log.e(
                    TAG,
                    "⏸️ Full protection recovery deferred"
                )

                Log.e(
                    TAG,
                    "➡️ Waiting for BOOT_COMPLETED"
                )

                Log.e(
                    TAG,
                    "=================================================="
                )

                return
            }

            // =================================================
            // NORMAL BOOT
            // =================================================

            Intent.ACTION_BOOT_COMPLETED -> {

                Log.e(
                    TAG,
                    "🔓 BOOT_COMPLETED RECEIVED"
                )

                Log.e(
                    TAG,
                    "✅ Device boot completed"
                )

                Log.e(
                    TAG,
                    "🚀 Scheduling protection recovery"
                )
            }

            // =================================================
            // PACKAGE UPDATED / REPLACED
            // =================================================

            Intent.ACTION_MY_PACKAGE_REPLACED -> {

                Log.e(
                    TAG,
                    "📦 MY_PACKAGE_REPLACED RECEIVED"
                )

                Log.e(
                    TAG,
                    "🚀 Scheduling protection recovery after update"
                )
            }

// =================================================
// TEMPORARY TEST ACTION
// =================================================

            "com.parentalcontrol.childapp.TEST_BOOT" -> {

                Log.e(
                    TAG,
                    "🧪 TEST_BOOT RECEIVED"
                )

                Log.e(
                    TAG,
                    "🧪 Treating TEST_BOOT as a normal boot"
                )

                Log.e(
                    TAG,
                    "🚀 Scheduling protection recovery"
                )
            }
            // =================================================
            // UNSUPPORTED ACTION
            // =================================================

            else -> {

                Log.e(
                    TAG,
                    "⛔ Ignoring unsupported action: $action"
                )

                Log.e(
                    TAG,
                    "=================================================="
                )

                return
            }
        }

        // =====================================================
        // APPLICATION CONTEXT
        // =====================================================

        val appContext =
            context.applicationContext

        // =====================================================
        // RESET MEDIA PROJECTION STATE
        // =====================================================
        //
        // MediaProjection authorization should not be treated
        // as permanently valid after a reboot.
        //
        // This is intentionally performed only for:
        //
        //     BOOT_COMPLETED
        //     MY_PACKAGE_REPLACED
        //
        // It is NOT performed during LOCKED_BOOT_COMPLETED.
        //
        // =====================================================

        resetScreenCaptureState(
            appContext
        )

        // =====================================================
        // CHECK CHILD CONFIGURATION
        // =====================================================

        val prefs =
            appContext.getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val childId =
            prefs
                .getString(
                    "child_id",
                    null
                )
                ?.trim()

        if (childId.isNullOrBlank()) {

            Log.e(
                TAG,
                "⚠️ No childId stored"
            )

            Log.e(
                TAG,
                "⏸️ Protection recovery will not be scheduled"
            )

            Log.e(
                TAG,
                "ℹ️ Child setup/pairing is not complete"
            )

            Log.e(
                TAG,
                "🏁 BootReceiver finished"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            return
        }

        Log.e(
            TAG,
            "👶 Child ID found"
        )

        Log.e(
            TAG,
            "👶 childId=$childId"
        )

        // =====================================================
        // CHECK ONBOARDING STATE
        // =====================================================

        val onboardingDone =
            prefs.getBoolean(
                "onboarding_done",
                false
            )

        if (!onboardingDone) {

            Log.e(
                TAG,
                "⚠️ onboarding_done=false"
            )

            Log.e(
                TAG,
                "⏸️ Full protection recovery deferred"
            )

            Log.e(
                TAG,
                "ℹ️ Child setup is not complete"
            )

            Log.e(
                TAG,
                "🏁 BootReceiver finished"
            )

            Log.e(
                TAG,
                "=================================================="
            )

            return
        }

        Log.e(
            TAG,
            "✅ Child onboarding is complete"
        )

        // =====================================================
        // SCHEDULE PROTECTION RECOVERY
        // =====================================================
        //
        // IMPORTANT:
        //
        // Do NOT call:
        //
        //     startForegroundService(...)
        //
        // from this receiver for the dataSync services.
        //
        // Android 15+ explicitly restricts dataSync FGS startup
        // from BOOT_COMPLETED.
        //
        // The worker is responsible for performing the
        // Android-compatible recovery sequence.
        //
        // =====================================================

        scheduleBootRecovery(
            appContext,
            action
        )

        // =====================================================
        // COMPLETE
        // =====================================================

        Log.e(
            TAG,
            "🏁 BootReceiver finished"
        )

        Log.e(
            TAG,
            "=================================================="
        )
    }

    // =========================================================
    // RESET SCREEN CAPTURE STATE
    // =========================================================

    private fun resetScreenCaptureState(
        context: Context
    ) {

        try {

            Log.e(
                TAG,
                "🔄 Resetting screen capture state"
            )

            val prefs =
                context.getSharedPreferences(
                    PREFS_NAME,
                    Context.MODE_PRIVATE
                )

            prefs
                .edit()
                .putBoolean(
                    SCREEN_CAPTURE_GRANTED,
                    false
                )
                .apply()

            Log.e(
                TAG,
                "✅ Screen capture state reset"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "⚠️ Failed to reset screen capture state",
                e
            )
        }
    }

    // =========================================================
    // SCHEDULE BOOT RECOVERY
    // =========================================================

    private fun scheduleBootRecovery(
        context: Context,
        action: String
    ) {

        try {

            Log.e(TAG, "==================================================")
            Log.e(TAG, "🚀 SCHEDULING BOOT RECOVERY")
            Log.e(TAG, "🚀 Trigger = $action")

            val recoveryRequest =
                OneTimeWorkRequestBuilder<BootRecoveryWorker>()
                    .addTag("boot_recovery")
                    .build()

            WorkManager
                .getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    BOOT_RECOVERY_WORK,
                    ExistingWorkPolicy.KEEP,
                    recoveryRequest
                )

            Log.e(TAG, "✅ Boot recovery work scheduled")
            Log.e(TAG, "🧠 Work name = $BOOT_RECOVERY_WORK")
            Log.e(TAG, "==================================================")

        } catch (e: Exception) {

            Log.e(TAG, "==================================================")
            Log.e(TAG, "❌ FAILED TO SCHEDULE BOOT RECOVERY")
            Log.e(TAG, "❌ Exception type = ${e::class.java.name}")
            Log.e(TAG, "❌ Exception message = ${e.message}")
            Log.e(TAG, "==================================================")
            Log.e(TAG, "❌ FULL BOOT RECOVERY SCHEDULING FAILURE", e)
        }
    }
}