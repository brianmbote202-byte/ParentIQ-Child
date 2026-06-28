package com.parentalcontrol.childapp.service

import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.receiver.MessageService
import com.parentalcontrol.childapp.receiver.SmsObserver
import com.parentalcontrol.childapp.utils.SecurityUtils
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MonitoringService : Service() {

    private val TAG = "MonitoringService"
    private lateinit var childId: String
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var smsObserver: SmsObserver? = null
    private lateinit var usageLogger: UsageLogger

    // Firebase references
    private val dbStatus = FirebaseDatabase.getInstance().getReference("child_status")

    override fun onCreate() {
        super.onCreate()

        startForegroundNotification()

        childId = loadChildId()

        usageLogger = UsageLogger(this, childId)

        try {
            usageLogger.start()
        } catch (e: Exception) {
            Log.e(TAG, "UsageLogger failed", e)
        }

        SecurityUtils.saveOriginalSim(this)
        SecurityUtils.saveDeviceId(this)

        smsObserver = SmsObserver(this)
        smsObserver?.start()

        startMonitoringLoop()
        startAppSyncService()
    }

    private fun loadChildId(): String {
        val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)
        return prefs.getString("child_id", "unknown_child")!!
    }

    private fun startForegroundNotification() {
        val notification = NotificationHelper.build(this)
        startForeground(2, notification)
    }

    private fun startMonitoringLoop() {
        executor.scheduleWithFixedDelay({
            try {
                pushDeviceStatusToFirebase()
                trackAppUsage()
                enforceScreenTimeRules()
                detectBlockedWebsiteAccess()
                detectBlockedApps()
                checkDeviceSecurity()
            } catch (e: Exception) {
                Log.e(TAG, "Error in monitoring loop", e)
            }
        }, 0, 30, TimeUnit.SECONDS)
    }

    // ------------------ Device Status ------------------
    private fun pushDeviceStatusToFirebase() {
        val batteryLevel = getBatteryPercentage()
        val chargingStatus = getChargingStatus()
        val internetStatus = if (isInternetAvailable()) "On" else "Off"

        val statusData = mapOf(
            "battery" to batteryLevel,
            "charging" to chargingStatus,
            "internet" to internetStatus,
            "status" to "safe", // or "alert"
            "lastSeen" to System.currentTimeMillis()
        )

        Log.d(TAG, "Pushing device status to Firebase: $statusData")

        dbStatus.child(childId).updateChildren(statusData)
            .addOnSuccessListener {
                Log.d(TAG, "✅ Device status updated successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "❌ Failed to update device status: ${e.message}")
            }
    }

    private fun getBatteryPercentage(): Int {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val percentage = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        Log.d(TAG, "Battery level=$percentage%")
        return if (percentage in 0..100) percentage else -1
    }

    private fun getChargingStatus(): String {
        val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC,
            BatteryManager.BATTERY_PLUGGED_USB,
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> {
                Log.d(TAG, "Device is charging")
                "Yes"
            }
            else -> {
                Log.d(TAG, "Device is not charging")
                "No"
            }
        }
    }

    private fun isInternetAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        val available = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Log.d(TAG, "Internet available=$available")
        return available
    }

    // ------------------ Monitoring Tasks ------------------
    private fun trackAppUsage() {
        Log.d(TAG, "Tracking app usage...")
    }

    private fun enforceScreenTimeRules() {
        Log.d(TAG, "Enforcing screen time rules...")
    }

    private fun detectBlockedWebsiteAccess() {
        Log.d(TAG, "Detecting blocked websites...")
    }

    private fun detectBlockedApps() {
        Log.d(TAG, "Detecting blocked apps...")
    }

    private fun checkDeviceSecurity() {
        if (SecurityUtils.checkSimSwap(this)) sendSecurityAlert("SIM_CHANGED")
        if (SecurityUtils.isDeviceReset(this)) sendSecurityAlert("DEVICE_RESET")
    }

    private fun sendSecurityAlert(eventType: String) {
        try {
            val intent = Intent(this, MessageService::class.java).apply {
                putExtra("childId", childId)
                putExtra("type", "SECURITY")
                putExtra("event", eventType)
                putExtra("timestamp", System.currentTimeMillis())
            }
            ContextCompat.startForegroundService(this, intent)
            Log.d(TAG, "Security alert sent: $eventType")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send security alert: $eventType", e)
        }
    }

    //----start appsyncservice-----------
    private fun startAppSyncService() {
        ContextCompat.startForegroundService(
            this,
            Intent(this, AppSyncService::class.java)
        )
    }

    override fun onDestroy() {
        executor.shutdownNow()
        smsObserver?.stop()

        usageLogger.stop()


        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}