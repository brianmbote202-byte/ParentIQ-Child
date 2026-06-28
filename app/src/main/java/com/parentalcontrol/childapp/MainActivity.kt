package com.parentalcontrol.childapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.onboarding.OnboardingActivityV5
import com.parentalcontrol.childapp.service.ChildLocationService
import com.parentalcontrol.childapp.vpn.DnsVpnService
import com.parentalcontrol.childapp.receiver.MessageService
import com.parentalcontrol.childapp.utils.UsageHelper
import android.net.VpnService
import android.media.projection.MediaProjectionManager
import com.parentalcontrol.childapp.service.ScreenCaptureService
import android.content.Context
import android.content.IntentFilter
import android.telephony.TelephonyManager
import com.parentalcontrol.childapp.receiver.CallReceiver
import com.parentalcontrol.childapp.service.BrowsingTracker
import android.net.Uri
import androidx.work.workDataOf
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.parentalcontrol.childapp.receiver.DailyReportWorker
import com.parentalcontrol.childapp.service.ScreenCaptureHolder
import com.parentalcontrol.childapp.service.UsageLoggerWorker
import java.util.concurrent.TimeUnit
import com.parentalcontrol.childapp.service.ScreenTimeService
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.LinearLayoutManager
import com.parentalcontrol.childapp.model.Feature
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.widget.Button
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.parentalcontrol.childapp.service.ChildAppControlService
import com.parentalcontrol.childapp.service.OverlayManager
import com.parentalcontrol.childapp.service.OverlayType
import com.parentalcontrol.childapp.utils.SecurityUtils
import com.parentalcontrol.childapp.utils.SecurityUtils.isAutoStartActuallyEnabled


class MainActivity : AppCompatActivity() {

    private lateinit var instructionBanner: TextView


    private val prefs by lazy { getSharedPreferences("child_prefs", MODE_PRIVATE) }
    private var browsingTracker: BrowsingTracker? = null

    private lateinit var vpnLauncher: ActivityResultLauncher<Intent>
    private var childId: String? = null

    // ---------- Screen Capture Projection ----------
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private lateinit var screenCaptureLauncher: ActivityResultLauncher<Intent>
    private lateinit var callReceiver: CallReceiver



    //------------------auto start permission card-----------
    //private lateinit var cardAutoStartWarning: MaterialCardView
    private lateinit var btnEnableAutoStart: MaterialButton




    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)



        val intent = Intent(this, ChildAppControlService::class.java)


        Log.e("TEST", "APP MANUALLY OPENED")

        val childControlIntent =
            Intent(this, ChildAppControlService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(
                this,
                childControlIntent
            )
        } else {
            startService(childControlIntent)
        }

        Log.e("TEST", "ChildAppControlService started")
        if (!prefs.getBoolean("onboarding_done", false)) {
            startActivity(Intent(this, OnboardingActivityV5::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_main)

        val openDashboard =
            intent.getBooleanExtra("open_dashboard", false)

        val openMore =
            intent.getBooleanExtra("open_more", false)

        //=========screenService=========
        val serviceIntent = Intent(this, ScreenTimeService::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.startForegroundService(this, serviceIntent)
        } else {
            startService(serviceIntent)
        }

        //=========lock overlay========
        if (!Settings.canDrawOverlays(this)) {

            Toast.makeText(
                this,
                "Overlay permission missing",
                Toast.LENGTH_LONG
            ).show()

        }
        //schedule daily usage worker

        val workRequest = PeriodicWorkRequestBuilder<DailyReportWorker>(
            1, TimeUnit.DAYS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "daily_report_worker",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )



        val request = OneTimeWorkRequestBuilder<DailyReportWorker>().build()
        WorkManager.getInstance(this).enqueue(request)



        val ruleMonitor = RuleMonitor(this)

        // Create receiver
        callReceiver = CallReceiver()

        val filter = IntentFilter().apply {
            addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            addAction(Intent.ACTION_NEW_OUTGOING_CALL)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(
                callReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(callReceiver, filter)
        }

        instructionBanner = findViewById(R.id.instructionBanner)

        // initialize MediaProjectionManager
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        checkUsagePermission()

        // ===============================
        // NEW: SMS PERMISSION REQUEST
        // ===============================
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

            val smsPermissions = arrayOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS
            )

            val missingPermissions = smsPermissions.filter {
                ContextCompat.checkSelfPermission(
                    this,
                    it
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }


            if (missingPermissions.isNotEmpty()) {
                requestPermissions(missingPermissions.toTypedArray(), 1001)
            }
        }
        // ===============================

        screenCaptureLauncher =
            registerForActivityResult(
                ActivityResultContracts.StartActivityForResult()
            ) { result ->

                if (result.resultCode == Activity.RESULT_OK) {

                    val data = result.data

                    if (data != null) {

                        // SAVE TEMP TOKEN
                        ScreenCaptureHolder.resultCode =
                            result.resultCode

                        ScreenCaptureHolder.data =
                            data

                        // SAVE FLAG
                        prefs.edit()
                            .putBoolean(
                                "screen_capture_granted",
                                true
                            )
                            .apply()

                        startScreenCaptureService(
                            result.resultCode,
                            data
                        )
                    }

                } else {

                    prefs.edit()
                        .putBoolean(
                            "screen_capture_granted",
                            false
                        )
                        .apply()

                    Toast.makeText(
                        this,
                        "Screen capture permission denied",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

        vpnLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Handler(Looper.getMainLooper()).postDelayed({
                    startVpnServiceSafely()
                }, 700)
            }
        }

        childId = prefs.getString("child_id", null)

        if (childId.isNullOrEmpty()) {
            showInstruction("Device not paired yet")
            return
        }

        //--------start feature animations----------
        startFloatingAnimation(findViewById(R.id.cardTracking), 0)

        startFloatingAnimation(findViewById(R.id.cardProtection), 300)

        startFloatingAnimation(findViewById(R.id.cardInsights), 600)

        startFloatingAnimation(findViewById(R.id.cardAlerts), 900)

        startFloatingAnimation(findViewById(R.id.cardBlockSites), 1200)

        startFloatingAnimation(findViewById(R.id.cardVisitedSites), 1500)

        startFloatingAnimation(findViewById(R.id.cardControlScreenTime), 1800)

        startFloatingAnimation(findViewById(R.id.cardSafeZones), 2100)

        startFloatingAnimation(findViewById(R.id.cardPhoneCalls), 2400)

        startFloatingAnimation(findViewById(R.id.cardMessages), 2700)

        //------------------battery optimization----------
        val btnBattery =
            findViewById<Button>(R.id.btnDisableBatteryOptimization)

        if (isBatteryOptimizationDisabled()) {

            btnBattery.visibility = View.GONE

        } else {

            btnBattery.visibility = View.VISIBLE
        }

//------------auto start card permission------------


    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1001) {
            for (i in permissions.indices) {
                if (grantResults[i] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Log.d("SMS_PERMISSION", "${permissions[i]} granted")
                } else {
                    Log.e("SMS_PERMISSION", "${permissions[i]} denied")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (isAccessibilityEnabled()) {
            hideInstruction()
        } else {
            showInstruction("Enable accessibility for tracking")
        }

        // 🔥 SAFE START LOGIC (ONLY WHEN ACTIVITY IS FULLY READY)
        val childId = prefs.getString("child_id", null)

        if (childId.isNullOrEmpty()) {
            startActivity(Intent(this, PairChildActivity::class.java))
            finish()
            return
        }


        startTrackingServices(childId)
        //updateSecurityRecommendations()
    }
    override fun onDestroy() {
        browsingTracker?.destroy()
        browsingTracker = null
        // Unregister to prevent leaks
       // unregisterReceiver(callReceiver)

        try {
            unregisterReceiver(callReceiver)
        } catch (e: Exception) {
            Log.e("MAIN", "Receiver already unregistered", e)
        }

        //stopService(Intent(this, DnsVpnService::class.java))
        //stopService(Intent(this, ChildLocationService::class.java))
        //stopService(Intent(this, MessageService::class.java))

        super.onDestroy()
    }

    // ===========================
    // PERMISSIONS
    // ===========================

    private fun checkUsagePermission() {

        if (!UsageHelper.isUsageStatsGranted(this)) {

            Toast.makeText(
                this,
                "Usage Access is disabled",
                Toast.LENGTH_LONG
            ).show()

            return
        }

    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabled.split(":").any { service ->
            service.contains("BrowserAccessibilityService", ignoreCase = true)
        }
    }

    // ===========================
    // PAIRING
    // ===========================

    private fun verifyPairing(id: String) {

        FirebaseDatabase.getInstance()
            .getReference("children")
            .child(id)
            .child("paired")
            .get()
            .addOnSuccessListener { snapshot ->

                val paired = snapshot.getValue(Boolean::class.java) ?: false

                if (paired) {
                    startTrackingServices(id)
                } else {
                    showInstruction("Pairing not completed")
                    // ❌ DO NOT REDIRECT IMMEDIATELY
                }
            }
            .addOnFailureListener {
                showInstruction("Network error")
            }
    }



    // ===========================
    // SERVICES
    // ===========================

    private fun startTrackingServices(childId: String) {

        browsingTracker?.destroy()


        showInstruction("Tracking active ✅")

        startLocationService()

        val vpnAllowed =
            prefs.getBoolean("vpn_allowed", false)

        if (vpnAllowed && !DnsVpnService.isRunning) {
            startVpnServiceSafely()
        }
        //requestVpn()
        //startSmsCallService()
        //requestScreenCapture()
        val granted =
            prefs.getBoolean(
                "screen_capture_granted",
                false
            )

        if (!granted) {

            requestScreenCapture()

        } else {

            val resultCode =
                ScreenCaptureHolder.resultCode

            val data =
                ScreenCaptureHolder.data

            if (
                resultCode != null &&
                data != null &&
                !ScreenCaptureService.isRunning
            ) {

                startScreenCaptureService(
                    resultCode,
                    data
                )

            }  else {

            Log.d(
                "SCREEN_CAPTURE",
                "Projection token unavailable"
            )

            // Only ask again if activity is visible
            if (!isFinishing && !isDestroyed) {
                requestScreenCapture()
            }
        }
        }

        // NEW: Start periodic usage logging
        // -----------------------------
        scheduleUsageLoggingWorker(childId)

        Handler(Looper.getMainLooper()).postDelayed({
            hideInstruction()
        }, 2000)
    }

    //schedule app usage
    private fun scheduleUsageLoggingWorker(childId: String) {

        val data = workDataOf(
            "childId" to childId,
            "trackedApps" to arrayOf(
                "com.whatsapp",
                "com.instagram.android",
                "com.facebook.katana",
                "com.snapchat.android",
                "org.telegram.messenger",
                "com.twitter.android",
                "com.android.chrome",
                "com.google.android.youtube"
            )
        )

        val workRequest = PeriodicWorkRequestBuilder<UsageLoggerWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        )
            .setInputData(data)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "UsageLoggerWorker",
            ExistingPeriodicWorkPolicy.UPDATE,
            workRequest
        )

        Log.d("USAGE_WORKER", "Periodic usage logging scheduled")
    }






    private fun startLocationService() {
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, ChildLocationService::class.java)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Location service failed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestVpn() {

        val vpnAllowed =
            prefs.getBoolean("vpn_allowed", false)

        if (vpnAllowed) {

            startVpnServiceSafely()
            return
        }

        val intent = VpnService.prepare(this)

        if (intent != null) {

            vpnLauncher.launch(intent)

        } else {

            prefs.edit()
                .putBoolean("vpn_allowed", true)
                .apply()

            startVpnServiceSafely()
        }
    }

    private fun startVpnServiceSafely() {
        try {
            val intent = Intent(this, DnsVpnService::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

        } catch (e: Exception) {
            Log.e("VPN_DEBUG", "VPN failed", e)
        }
    }

    // ===========================
    // SCREEN CAPTURE (MediaProjection)
    // ===========================

    private fun requestScreenCapture() {
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        screenCaptureLauncher.launch(intent)
    }

    private fun startScreenCaptureService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    // ===========================
    // UI HELPERS
    // ===========================

    private fun showInstruction(msg: String) {
        instructionBanner.text = msg
        instructionBanner.visibility = View.VISIBLE
    }

    private fun hideInstruction() {
        instructionBanner.visibility = View.GONE
    }

    //---------------floating animation on features---------
    private fun startFloatingAnimation(
        view: View,
        delay: Long = 0
    ) {

        view.animate()
            .setStartDelay(delay)
            .setDuration(2500)
            .withStartAction {

                val floatAnim = ObjectAnimator.ofFloat(
                    view,
                    "translationY",
                    0f,
                    -12f,
                    0f
                )

                floatAnim.repeatCount = ValueAnimator.INFINITE
                floatAnim.duration = 3000
                floatAnim.start()

                val scaleX = ObjectAnimator.ofFloat(
                    view,
                    "scaleX",
                    1f,
                    1.03f,
                    1f
                )

                scaleX.repeatCount = ValueAnimator.INFINITE
                scaleX.duration = 3000
                scaleX.start()

                val scaleY = ObjectAnimator.ofFloat(
                    view,
                    "scaleY",
                    1f,
                    1.03f,
                    1f
                )

                scaleY.repeatCount = ValueAnimator.INFINITE
                scaleY.duration = 3000
                scaleY.start()
            }
    }

    //--------disable battery optimization----------
    private fun isBatteryOptimizationDisabled(): Boolean {

        val powerManager =
            getSystemService(POWER_SERVICE) as android.os.PowerManager

        return powerManager.isIgnoringBatteryOptimizations(packageName)
    }


}