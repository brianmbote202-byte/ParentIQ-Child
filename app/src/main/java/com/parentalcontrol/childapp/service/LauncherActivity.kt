package com.parentalcontrol.childapp

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.service.AppSyncService
import com.parentalcontrol.childapp.service.MonitoringService
import androidx.work.*
import java.util.concurrent.TimeUnit
import com.parentalcontrol.childapp.worker.ServiceHealthWorker
import com.parentalcontrol.childapp.service.ChildAppControlService

class LauncherActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LauncherActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val firstRun = isFirstRun()

        Log.e(TAG, "firstRun = $firstRun")

        Log.e(TAG, "🔥 APP OPENED")

        val prefs = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        )

        Log.e(
            "PAIR_DEBUG",
            "ALL PREFS = ${prefs.all}"
        )




        val onboardingDone =
            prefs.getBoolean(
                "onboarding_done",
                false
            )

        val childId =
            prefs.getString(
                "child_id",
                null
            )

        Log.e(TAG, "📦 onboardingDone = $onboardingDone")
        Log.e(TAG, "👶 childId = $childId")
        Log.e("PAIR_DEBUG", "child_id = " + prefs.getString("child_id", "NULL"))

        // =====================================================
        // START SAFE SERVICES ONLY AFTER PAIRING
        // =====================================================

        if (!childId.isNullOrEmpty()) {

            Log.e(TAG, "✅ DEVICE PAIRED")

            // =====================================
            // START APPSYNC SERVICE
            // =====================================

            try {

                Log.e(TAG, "🚀 Starting AppSyncService")

                val appSyncIntent =
                    Intent(
                        this,
                        AppSyncService::class.java
                    )

                startServiceSafe(appSyncIntent)
                if (!childId.isNullOrEmpty()) {

                    Log.e(TAG, "✅ DEVICE PAIRED")

                    // ONLY UI + navigation logic
                    Log.e(TAG, "📱 Launcher is NOT starting services (Boot handles it)")
                }

                Log.e(TAG, "✅ AppSyncService started")

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ AppSyncService failed",
                    e
                )
            }

            // =====================================
            // START MONITORING SERVICE
            // =====================================

            try {

                Log.e(TAG, "🚀 Starting MonitoringService")

                val monitoringIntent =
                    Intent(
                        this,
                        MonitoringService::class.java
                    )

                startServiceSafe(monitoringIntent)

                Log.e(TAG, "✅ MonitoringService started")

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ MonitoringService failed",
                    e
                )
            }

            try {

                Log.e(TAG, "🚀 Starting ChildAppControlService")

                val controlIntent =
                    Intent(
                        this,
                        ChildAppControlService::class.java
                    )

                startServiceSafe(controlIntent)

                Log.e(TAG, "✅ ChildAppControlService started")

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ ChildAppControlService failed",
                    e
                )
            }

            // =====================================
            // IMPORTANT:
            // DO NOT START VPN HERE
            // BootReceiver manages VPN lifecycle
            // =====================================

            Log.e(
                TAG,
                "⚠ VPN startup skipped in LauncherActivity"
            )

        } else {

            Log.e(
                TAG,
                "⚠ DEVICE NOT PAIRED → SERVICES BLOCKED"
            )
        }

        // =====================================================
        // NAVIGATION
        // =====================================================

        val nextIntent = when {

            firstRun -> {
                Log.e(TAG, "🆕 FIRST RUN → SetupWizard")
                Intent(this, SetupWizardActivity::class.java)
            }

            !onboardingDone -> {
                Log.e(TAG, "➡ ONBOARDING NOT DONE → SetupWizard")
                Intent(this, SetupWizardActivity::class.java)
            }

            childId.isNullOrEmpty() -> {
                Log.e(TAG, "➡ NOT PAIR → PairChildActivity")
                Intent(this, PairChildActivity::class.java)
            }

            else -> {
                Log.e(TAG, "➡ MAIN APP → MainActivity")
                Intent(this, MainActivity::class.java)
            }
        }.apply {

            flags =
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        startActivity(nextIntent)
        checkAutoStartFlow()
        startHealthWorker()

        finish()
    }

    // =====================================================
    // SAFE FOREGROUND SERVICE STARTER
    // =====================================================

    private fun startServiceSafe(intent: Intent) {

        try {

            Log.e(
                TAG,
                "📡 Starting service: ${intent.component?.className}"
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                ContextCompat.startForegroundService(
                    this,
                    intent
                )

            } else {

                startService(intent)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed starting ${intent.component?.className}",
                e
            )
        }
    }

    //----------samsung check app installled again-----------
    private fun isFirstRun(): Boolean {
        val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)

        val firstRun = prefs.getBoolean("first_run", true)

        if (firstRun) {
            prefs.edit()
                .putBoolean("first_run", false)
                .apply()
        }

        return firstRun
    }

    private fun checkAutoStartFlow() {

        val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)

        val shown = prefs.getBoolean("autostart_shown", false)

    }

    private fun startHealthWorker() {

        val workRequest =
            PeriodicWorkRequestBuilder<ServiceHealthWorker>(
                15,
                TimeUnit.MINUTES
            ).build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "service_health_worker",
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
    }
}