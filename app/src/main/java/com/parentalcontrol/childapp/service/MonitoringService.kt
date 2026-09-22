
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

    /*
     * IMPORTANT:
     *
     * Do not permanently trust an in-memory childId.
     *
     * The canonical child ID is stored in:
     *
     * child_prefs / child_id
     *
     * The monitoring loop reads that ID whenever telemetry is pushed.
     */

    private var childId: String? = null

    private val executor =
        Executors.newSingleThreadScheduledExecutor()

    private var smsObserver: SmsObserver? = null

    private lateinit var usageLogger: UsageLogger

    // Firebase references
    private val dbStatus =
        FirebaseDatabase
            .getInstance()
            .getReference("child_status")

    override fun onCreate() {
        super.onCreate()

        Log.d(
            TAG,
            "========================================"
        )

        Log.d(
            TAG,
            "🚀 MonitoringService onCreate()"
        )

        startForegroundNotification()

        /*
         * Load the current child ID.
         */
        childId = getCurrentChildId()

        if (childId.isNullOrBlank()) {

            Log.e(
                TAG,
                "❌ MonitoringService cannot start"
            )

            Log.e(
                TAG,
                "❌ No child_id found in child_prefs"
            )

            stopSelf()
            return
        }

        Log.d(
            TAG,
            "✅ MonitoringService childId=$childId"
        )

        /*
         * UsageLogger still receives the ID at startup.
         *
         * This is separate from Firebase status telemetry,
         * which always re-reads the current child ID.
         */
        usageLogger =
            UsageLogger(
                this,
                childId!!
            )

        try {

            usageLogger.start()

            Log.d(
                TAG,
                "✅ UsageLogger started for childId=$childId"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ UsageLogger failed",
                e
            )
        }

        /*
         * Security initialization
         */
        SecurityUtils.saveOriginalSim(this)
        SecurityUtils.saveDeviceId(this)

        /*
         * SMS observer
         */
        smsObserver = SmsObserver(this)

        try {

            smsObserver?.start()

            Log.d(
                TAG,
                "✅ SmsObserver started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ SmsObserver failed to start",
                e
            )
        }

        /*
         * Start the 30-second monitoring loop.
         */
        startMonitoringLoop()

        /*
         * Start app synchronization.
         */
        startAppSyncService()

        Log.d(
            TAG,
            "========================================"
        )
    }

    /**
     * Gets the CURRENT child ID from SharedPreferences.
     *
     * This is the canonical source of the ChildApp's identity.
     *
     * Firebase telemetry must use this ID:
     *
     * child_status/{childId}
     */
    private fun getCurrentChildId(): String? {

        val prefs =
            getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            )

        val id =
            prefs.getString(
                "child_id",
                null
            )

        if (id.isNullOrBlank()) {

            Log.e(
                TAG,
                "❌ child_prefs/child_id is missing"
            )

            return null
        }

        Log.d(
            TAG,
            "🔑 Current child ID = $id"
        )

        return id
    }

    private fun startForegroundNotification() {

        try {

            val notification =
                NotificationHelper.build(this)

            startForeground(
                2,
                notification
            )

            Log.d(
                TAG,
                "✅ Foreground notification started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to start foreground notification",
                e
            )
        }
    }

    private fun startMonitoringLoop() {

        Log.d(
            TAG,
            "⏱ Starting monitoring loop: every 30 seconds"
        )

        executor.scheduleWithFixedDelay({

            try {

                /*
                 * Always use the current child ID.
                 */
                val currentChildId =
                    getCurrentChildId()

                if (currentChildId.isNullOrBlank()) {

                    Log.e(
                        TAG,
                        "❌ Monitoring skipped: no child ID"
                    )

                    return@scheduleWithFixedDelay
                }

                /*
                 * Keep the in-memory value synchronized.
                 */
                childId = currentChildId

                Log.d(
                    TAG,
                    "========================================"
                )

                Log.d(
                    TAG,
                    "🔄 Monitoring cycle"
                )

                Log.d(
                    TAG,
                    "childId=$currentChildId"
                )

                pushDeviceStatusToFirebase(
                    currentChildId
                )

                trackAppUsage()

                enforceScreenTimeRules()

                detectBlockedWebsiteAccess()

                detectBlockedApps()

                checkDeviceSecurity(
                    currentChildId
                )

                Log.d(
                    TAG,
                    "========================================"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Error in monitoring loop",
                    e
                )
            }

        }, 0, 30, TimeUnit.SECONDS)
    }

    // ============================================================
    // DEVICE STATUS
    // ============================================================

    private fun pushDeviceStatusToFirebase(
        currentChildId: String
    ) {

        val batteryLevel =
            getBatteryPercentage()

        val chargingStatus =
            getChargingStatus()

        val internetStatus =
            if (isInternetAvailable()) {
                "On"
            } else {
                "Off"
            }

        val lastSeen =
            System.currentTimeMillis()

        val statusData =
            mapOf(
                "battery" to batteryLevel,
                "charging" to chargingStatus,
                "internet" to internetStatus,
                "status" to "safe",
                "lastSeen" to lastSeen
            )

        Log.d(
            TAG,
            """
            ========================================
            📡 WRITING CHILD STATUS

            childId    = $currentChildId
            battery    = $batteryLevel
            charging   = $chargingStatus
            internet   = $internetStatus
            lastSeen   = $lastSeen

            Firebase path:
            child_status/$currentChildId

            Data:
            $statusData

            ========================================
            """.trimIndent()
        )

        dbStatus
            .child(currentChildId)
            .updateChildren(statusData)
            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "========================================"
                )

                Log.d(
                    TAG,
                    "✅ CHILD STATUS UPDATED SUCCESSFULLY"
                )

                Log.d(
                    TAG,
                    "Firebase path:"
                )

                Log.d(
                    TAG,
                    "child_status/$currentChildId"
                )

                Log.d(
                    TAG,
                    "========================================"
                )
            }
            .addOnFailureListener { e ->

                Log.e(
                    TAG,
                    "========================================"
                )

                Log.e(
                    TAG,
                    "❌ CHILD STATUS WRITE FAILED"
                )

                Log.e(
                    TAG,
                    "Firebase path:"
                )

                Log.e(
                    TAG,
                    "child_status/$currentChildId"
                )

                Log.e(
                    TAG,
                    "Error message: ${e.message}"
                )

                Log.e(
                    TAG,
                    "Database error:",
                    e
                )

                Log.e(
                    TAG,
                    "========================================"
                )
            }
    }

    // ============================================================
    // BATTERY
    // ============================================================

    private fun getBatteryPercentage(): Int {

        return try {

            val bm =
                getSystemService(
                    BATTERY_SERVICE
                ) as BatteryManager

            val percentage =
                bm.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY
                )

            Log.d(
                TAG,
                "🔋 Battery level=$percentage%"
            )

            if (percentage in 0..100) {

                percentage

            } else {

                -1
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to read battery level",
                e
            )

            -1
        }
    }

    // ============================================================
    // CHARGING
    // ============================================================

    private fun getChargingStatus(): String {

        return try {

            val batteryIntent =
                registerReceiver(
                    null,
                    IntentFilter(
                        Intent.ACTION_BATTERY_CHANGED
                    )
                )

            when (
                batteryIntent?.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED,
                    -1
                )
            ) {

                BatteryManager.BATTERY_PLUGGED_AC,
                BatteryManager.BATTERY_PLUGGED_USB,
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> {

                    Log.d(
                        TAG,
                        "🔌 Device is charging"
                    )

                    "Yes"
                }

                else -> {

                    Log.d(
                        TAG,
                        "🔋 Device is not charging"
                    )

                    "No"
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to determine charging status",
                e
            )

            "No"
        }
    }

    // ============================================================
    // INTERNET
    // ============================================================

    private fun isInternetAvailable(): Boolean {

        return try {

            val cm =
                getSystemService(
                    CONNECTIVITY_SERVICE
                ) as ConnectivityManager

            val network =
                cm.activeNetwork
                    ?: return false

            val capabilities =
                cm.getNetworkCapabilities(
                    network
                )
                    ?: return false

            val available =
                capabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )

            Log.d(
                TAG,
                "🌐 Internet available=$available"
            )

            available

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to check internet",
                e
            )

            false
        }
    }

    // ============================================================
    // MONITORING TASKS
    // ============================================================

    private fun trackAppUsage() {

        Log.d(
            TAG,
            "📱 Tracking app usage..."
        )
    }

    private fun enforceScreenTimeRules() {

        Log.d(
            TAG,
            "⏰ Enforcing screen time rules..."
        )
    }

    private fun detectBlockedWebsiteAccess() {

        Log.d(
            TAG,
            "🌐 Detecting blocked websites..."
        )
    }

    private fun detectBlockedApps() {

        Log.d(
            TAG,
            "🚫 Detecting blocked apps..."
        )
    }

    // ============================================================
    // DEVICE SECURITY
    // ============================================================

    private fun checkDeviceSecurity(
        currentChildId: String
    ) {

        try {

            if (
                SecurityUtils.checkSimSwap(this)
            ) {

                sendSecurityAlert(
                    currentChildId,
                    "SIM_CHANGED"
                )
            }

            if (
                SecurityUtils.isDeviceReset(this)
            ) {

                sendSecurityAlert(
                    currentChildId,
                    "DEVICE_RESET"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Device security check failed",
                e
            )
        }
    }

    private fun sendSecurityAlert(
        currentChildId: String,
        eventType: String
    ) {

        try {

            val intent =
                Intent(
                    this,
                    MessageService::class.java
                ).apply {

                    putExtra(
                        "childId",
                        currentChildId
                    )

                    putExtra(
                        "type",
                        "SECURITY"
                    )

                    putExtra(
                        "event",
                        eventType
                    )

                    putExtra(
                        "timestamp",
                        System.currentTimeMillis()
                    )
                }

            ContextCompat.startForegroundService(
                this,
                intent
            )

            Log.d(
                TAG,
                "🚨 Security alert sent"
            )

            Log.d(
                TAG,
                "childId=$currentChildId"
            )

            Log.d(
                TAG,
                "event=$eventType"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to send security alert: $eventType",
                e
            )
        }
    }

    // ============================================================
    // APP SYNC
    // ============================================================

    private fun startAppSyncService() {

        try {

            Log.d(
                TAG,
                "🔄 Starting AppSyncService"
            )

            ContextCompat.startForegroundService(
                this,
                Intent(
                    this,
                    AppSyncService::class.java
                )
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to start AppSyncService",
                e
            )
        }
    }

    // ============================================================
    // SERVICE DESTROY
    // ============================================================

    override fun onDestroy() {

        Log.d(
            TAG,
            "🛑 MonitoringService onDestroy()"
        )

        try {

            executor.shutdownNow()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error shutting down executor",
                e
            )
        }

        try {

            smsObserver?.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping SmsObserver",
                e
            )
        }

        try {

            if (::usageLogger.isInitialized) {
                usageLogger.stop()
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Error stopping UsageLogger",
                e
            )
        }

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}

