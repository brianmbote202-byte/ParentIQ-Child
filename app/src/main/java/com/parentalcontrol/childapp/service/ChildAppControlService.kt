package com.parentalcontrol.childapp.service

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.parentalcontrol.childapp.model.AppRule
import java.util.Calendar

class ChildAppControlService : Service() {

    companion object {
        private const val TAG = "CHILD_SERVICE"

        private const val PREFS_NAME = "child_prefs"
        private const val CHILD_ID_KEY = "child_id"

        private const val CHANNEL_ID = "child_control_channel"
        private const val NOTIFICATION_ID = 1

        private const val ENFORCEMENT_INTERVAL = 15_000L

        fun start(context: Context) {

            try {

                val intent =
                    Intent(
                        context,
                        ChildAppControlService::class.java
                    )

                if (android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.O
                ) {

                    context.startForegroundService(intent)

                } else {

                    context.startService(intent)
                }

                Log.e(
                    TAG,
                    "✅ ChildAppControlService start requested"
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Failed to start ChildAppControlService",
                    e
                )
            }
        }
    }

    // ============================================================
    // APP RULES
    // ============================================================

    private val monitoredApps =
        mutableMapOf<String, AppRule>()

    private var blockSchedule: BlockSchedule? = null

    // ============================================================
    // FIREBASE
    // ============================================================

    private lateinit var db: DatabaseReference
    private lateinit var childId: String

    private var rulesListener: ValueEventListener? = null
    private var scheduleListener: ValueEventListener? = null

    // ============================================================
    // HANDLER
    // ============================================================

    private val handler =
        Handler(Looper.getMainLooper())

    // ============================================================
    // SERVICE CREATE
    // ============================================================

    override fun onCreate() {

        super.onCreate()

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥 CHILD APP CONTROL SERVICE CREATED"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        // --------------------------------------------------------
        // Foreground notification FIRST
        // --------------------------------------------------------

        try {

            startForegroundService()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to enter foreground",
                e
            )

            stopSelf()

            return
        }

        // --------------------------------------------------------
        // Firebase
        // --------------------------------------------------------

        db =
            FirebaseDatabase
                .getInstance()
                .reference

        // --------------------------------------------------------
        // Load child ID
        // --------------------------------------------------------

        val prefs =
            getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE
            )

        val storedChildId =
            prefs.getString(
                CHILD_ID_KEY,
                null
            )
                ?.trim()

        if (storedChildId.isNullOrBlank()) {

            Log.e(
                TAG,
                "❌ child_id is missing"
            )

            Log.e(
                TAG,
                "❌ Refusing to use a fake/default child ID"
            )

            stopSelf()

            return
        }

        childId = storedChildId

        Log.e(
            TAG,
            "✅ childId loaded = [$childId]"
        )

        // --------------------------------------------------------
        // Firebase listeners
        // --------------------------------------------------------

        listenToParentRules()

        listenToBlockSchedule()

        // --------------------------------------------------------
        // Usage enforcement
        // --------------------------------------------------------

        startUsageTracking()

        Log.e(
            TAG,
            "✅ ChildAppControlService initialization complete"
        )
    }
    // ============================================================
    // SERVICE START
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🚀 ChildAppControlService onStartCommand"
        )

        Log.e(
            TAG,
            "🚀 startId=$startId"
        )

        Log.e(
            TAG,
            "🚀 childId=$childId"
        )

        Log.e(
            TAG,
            "🚀 flags=$flags"
        )

        Log.e(
            TAG,
            "=================================================="
        )

        /*
         * START_STICKY tells Android that this service represents
         * ongoing protection work and should be recreated after the
         * process/service is killed when the OS permits it.
         *
         * Reboot recovery itself is handled separately by the boot
         * recovery architecture.
         */

        return START_STICKY
    }

    // ============================================================
    // SERVICE DESTROY
    // ============================================================

    override fun onDestroy() {

        Log.e(
            TAG,
            "💀 ChildAppControlService destroying"
        )

        // --------------------------------------------------------
        // Remove app rules listener
        // --------------------------------------------------------

        try {

            rulesListener?.let { listener ->

                db.child("app_rules")
                    .child(childId)
                    .removeEventListener(listener)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed removing rules listener",
                e
            )
        }

        rulesListener = null

        // --------------------------------------------------------
        // Remove block schedule listener
        // --------------------------------------------------------

        try {

            scheduleListener?.let { listener ->

                db.child("app_rules")
                    .child(childId)
                    .child("block_schedule")
                    .removeEventListener(listener)
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed removing schedule listener",
                e
            )
        }

        scheduleListener = null

        // --------------------------------------------------------
        // Stop enforcement loop
        // --------------------------------------------------------

        handler.removeCallbacks(
            enforcementRunnable
        )

        monitoredApps.clear()
        blockSchedule = null

        Log.e(
            TAG,
            "✅ Child service destroyed and listeners removed"
        )

        super.onDestroy()
    }

    // ============================================================
    // TASK REMOVED
    // ============================================================

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        Log.e(
            TAG,
            "⚠️ App task removed."
        )

        Log.e(
            TAG,
            "⚠️ Foreground ChildAppControlService remains managed by Android."
        )

        /*
         * Do NOT call startService() here.
         *
         * Modern Android versions restrict background service starts,
         * and BootReceiver/SystemBootOrchestrator is responsible for
         * reboot recovery.
         */

        super.onTaskRemoved(rootIntent)
    }

    // ============================================================
    // FOREGROUND SERVICE
    // ============================================================

    private fun startForegroundService() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Child Control Service",
                NotificationManager.IMPORTANCE_LOW
            )

        channel.description =
            "Child app usage and parental control enforcement"

        manager.createNotificationChannel(channel)

        val notification =
            Notification.Builder(
                this,
                CHANNEL_ID
            )
                .setContentTitle(
                    "Parental Control Active"
                )
                .setContentText(
                    "Monitoring app usage and limits"
                )
                .setSmallIcon(
                    android.R.drawable.ic_lock_lock
                )
                .setOngoing(true)
                .setCategory(
                    Notification.CATEGORY_SERVICE
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )

        Log.e(
            TAG,
            "✅ Foreground notification started"
        )
    }

    // ============================================================
    // FIREBASE APP RULES
    // ============================================================

    private fun listenToParentRules() {

        val ref =
            db.child("app_rules")
                .child(childId)

        // --------------------------------------------------------
        // Remove previous listener
        // --------------------------------------------------------

        rulesListener?.let { listener ->
            ref.removeEventListener(listener)
        }

        // --------------------------------------------------------
        // Create listener
        // --------------------------------------------------------

        rulesListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {

                    monitoredApps.clear()

                    if (!snapshot.exists()) {

                        Log.d(
                            "RULES_DEBUG",
                            "No app rules found for childId=$childId"
                        )

                        return
                    }

                    for (
                    appSnap in snapshot.children
                    ) {

                        val rawKey =
                            appSnap.key

                        if (
                            rawKey.isNullOrBlank()
                        ) {
                            continue
                        }

                        /*
                         * Ignore the block_schedule node.
                         *
                         * It lives underneath app_rules but is not
                         * an AppRule.
                         */
                        if (
                            rawKey == "block_schedule"
                        ) {
                            continue
                        }

                        val rule =
                            try {

                                appSnap.getValue(
                                    AppRule::class.java
                                )

                            } catch (e: Exception) {

                                Log.e(
                                    "RULES_DEBUG",
                                    "Failed parsing rule: $rawKey",
                                    e
                                )

                                null
                            }

                        if (rule == null) {
                            continue
                        }

                        /*
                         * Parent app stores package names with dots
                         * converted to underscores.
                         *
                         * Example:
                         *
                         * com.android.chrome
                         *        ↓
                         * com_android_chrome
                         */

                        val packageName =
                            rawKey.replace(
                                "_",
                                "."
                            )

                        monitoredApps[
                            packageName
                        ] = rule

                        Log.d(
                            "RULES_DEBUG",
                            "Loaded rule -> " +
                                    "$packageName | " +
                                    "blocked=${rule.blocked} | " +
                                    "limit=${rule.daily_limit}"
                        )
                    }

                    Log.d(
                        TAG,
                        "Rules updated successfully. " +
                                "Total apps=${monitoredApps.size}"
                    )
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {

                    Log.e(
                        "RULES_DEBUG",
                        "Firebase cancelled: ${error.message}"
                    )
                }
            }

        // --------------------------------------------------------
        // Attach listener
        // --------------------------------------------------------

        ref.addValueEventListener(
            rulesListener!!
        )

        Log.d(
            TAG,
            "✅ App rules listener attached"
        )
    }

    // ============================================================
    // BLOCK SCHEDULE
    // ============================================================

    private fun listenToBlockSchedule() {

        val ref =
            db.child("app_rules")
                .child(childId)
                .child("block_schedule")

        // --------------------------------------------------------
        // Remove previous listener
        // --------------------------------------------------------

        scheduleListener?.let { listener ->
            ref.removeEventListener(listener)
        }

        // --------------------------------------------------------
        // Create listener
        // --------------------------------------------------------

        scheduleListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {

                    val startHour =
                        snapshot.child(
                            "start_hour"
                        )
                            .getValue(
                                Int::class.java
                            )

                    val endHour =
                        snapshot.child(
                            "end_hour"
                        )
                            .getValue(
                                Int::class.java
                            )

                    if (
                        startHour != null &&
                        endHour != null
                    ) {

                        blockSchedule =
                            BlockSchedule(
                                startHour = startHour,
                                endHour = endHour
                            )

                        Log.d(
                            TAG,
                            "Updated block schedule: " +
                                    "$blockSchedule"
                        )

                    } else {

                        blockSchedule = null

                        Log.d(
                            TAG,
                            "No block schedule configured"
                        )
                    }
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {

                    Log.e(
                        TAG,
                        "Block schedule listener cancelled: " +
                                error.message
                    )
                }
            }

        // --------------------------------------------------------
        // Attach listener
        // --------------------------------------------------------

        ref.addValueEventListener(
            scheduleListener!!
        )

        Log.d(
            TAG,
            "✅ Block schedule listener attached"
        )
    }

    // ============================================================
    // USAGE TRACKING
    // ============================================================

    private fun startUsageTracking() {

        if (!hasUsageAccess()) {

            Log.e(
                TAG,
                "⚠️ Usage Access is NOT granted"
            )

            Log.e(
                TAG,
                "⚠️ App limit enforcement cannot run"
            )

            return
        }

        Log.e(
            TAG,
            "✅ Usage Access confirmed"
        )

        // --------------------------------------------------------
        // Prevent duplicate enforcement loops
        // --------------------------------------------------------

        handler.removeCallbacks(
            enforcementRunnable
        )

        // --------------------------------------------------------
        // Start enforcement
        // --------------------------------------------------------

        handler.post(
            enforcementRunnable
        )

        Log.e(
            TAG,
            "✅ Usage enforcement loop started"
        )
    }

    private val enforcementRunnable =
        object : Runnable {

            override fun run() {

                try {

                    enforceLimits()

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ enforceLimits() failed",
                        e
                    )
                }

                handler.postDelayed(
                    this,
                    ENFORCEMENT_INTERVAL
                )
            }
        }

    // ============================================================
    // ENFORCEMENT
    // ============================================================

    private fun enforceLimits() {

        val now =
            Calendar.getInstance()

        val hour =
            now.get(
                Calendar.HOUR_OF_DAY
            )

        val minute =
            now.get(
                Calendar.MINUTE
            )

        // --------------------------------------------------------
        // Current foreground app
        // --------------------------------------------------------

        val currentApp =
            getForegroundApp()
                ?: return

        // --------------------------------------------------------
        // Find rule
        // --------------------------------------------------------

        val rule =
            monitoredApps[currentApp]
                ?: return

        // ========================================================
        // ALLOWED TIME WINDOW
        // ========================================================

        val currentMinutes =
            hour * 60 + minute

        val allowedFrom =
            rule.allowed_from_hour * 60 +
                    rule.allowed_from_minute

        val allowedTo =
            rule.allowed_to_hour * 60 +
                    rule.allowed_to_minute

        val outsideAllowedTime =
            if (allowedFrom <= allowedTo) {

                // Normal range
                // Example: 08:00 -> 17:00

                currentMinutes < allowedFrom ||
                        currentMinutes > allowedTo

            } else {

                // Overnight range
                // Example: 22:00 -> 06:00

                currentMinutes > allowedTo &&
                        currentMinutes < allowedFrom
            }

        // ========================================================
        // BLOCK SCHEDULE
        // ========================================================

        val inBlockedTime =
            blockSchedule?.let {

                if (
                    it.startHour <=
                    it.endHour
                ) {

                    hour in
                            it.startHour until
                            it.endHour

                } else {

                    hour >= it.startHour ||
                            hour < it.endHour
                }

            } ?: false

        // ========================================================
        // USAGE STATS
        // ========================================================

        val usm =
            getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val endTime =
            System.currentTimeMillis()

        val startTime =
            endTime -
                    24 * 60 * 60 * 1000L

        val stats =
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                startTime,
                endTime
            )

        val appStats =
            stats.firstOrNull {
                it.packageName ==
                        currentApp
            }
                ?: return

        val totalTime =
            appStats.totalTimeInForeground

        // ========================================================
        // DAILY LIMIT
        // ========================================================

        val isOverLimit =
            rule.daily_limit > 0 &&
                    totalTime > rule.daily_limit

        // ========================================================
        // PARENT BLOCK
        // ========================================================

        if (rule.blocked) {

            blockApp(
                currentApp,
                "PARENT_BLOCK"
            )

            sendAppAlert(
                currentApp,
                "Blocked by parent"
            )

            return
        }

        // ========================================================
        // SCHEDULE BLOCK
        // ========================================================

        if (inBlockedTime) {

            blockApp(
                currentApp,
                "SCHEDULE"
            )

            sendAppAlert(
                currentApp,
                "Blocked by schedule"
            )

            return
        }

        // ========================================================
        // ALLOWED TIME WINDOW
        // ========================================================

        if (outsideAllowedTime) {

            val unlockTime =
                Calendar.getInstance().apply {

                    set(
                        Calendar.HOUR_OF_DAY,
                        rule.allowed_from_hour
                    )

                    set(
                        Calendar.MINUTE,
                        rule.allowed_from_minute
                    )

                    set(
                        Calendar.SECOND,
                        0
                    )

                    set(
                        Calendar.MILLISECOND,
                        0
                    )

                    if (
                        timeInMillis <=
                        System.currentTimeMillis()
                    ) {

                        add(
                            Calendar.DAY_OF_YEAR,
                            1
                        )
                    }
                }

            blockApp(
                currentApp,
                "TIME_WINDOW",
                unlockTime.timeInMillis
            )

            sendAppAlert(
                currentApp,
                "Outside allowed time"
            )

            return
        }

        // ========================================================
        // DAILY LIMIT
        // ========================================================

        if (
            rule.daily_limit > 0 &&
            isOverLimit
        ) {

            val tomorrow =
                Calendar.getInstance().apply {

                    add(
                        Calendar.DAY_OF_YEAR,
                        1
                    )

                    set(
                        Calendar.HOUR_OF_DAY,
                        0
                    )

                    set(
                        Calendar.MINUTE,
                        0
                    )

                    set(
                        Calendar.SECOND,
                        0
                    )

                    set(
                        Calendar.MILLISECOND,
                        0
                    )
                }

            blockApp(
                currentApp,
                "LIMIT",
                tomorrow.timeInMillis
            )

            sendAppAlert(
                currentApp,
                "Daily limit reached"
            )

            return
        }

        // ========================================================
        // 9 PM BLOCK
        // ========================================================

        if (
            rule.block_after_9pm &&
            hour >= 21
        ) {

            val morning =
                Calendar.getInstance().apply {

                    add(
                        Calendar.DAY_OF_YEAR,
                        1
                    )

                    set(
                        Calendar.HOUR_OF_DAY,
                        6
                    )

                    set(
                        Calendar.MINUTE,
                        0
                    )

                    set(
                        Calendar.SECOND,
                        0
                    )

                    set(
                        Calendar.MILLISECOND,
                        0
                    )
                }

            blockApp(
                currentApp,
                "NIGHT",
                morning.timeInMillis
            )

            sendAppAlert(
                currentApp,
                "Blocked after 9 PM"
            )
        }
    }

    // ============================================================
    // BLOCK APP
    // ============================================================

    private fun blockApp(
        packageName: String,
        reason: String,
        endTime: Long = 0L
    ) {

        Log.e(
            "BLOCK_FLOW",
            "Launching overlay for " +
                    "$packageName reason=$reason"
        )

        val intent =
            Intent(
                this,
                AppBlockOverlayActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TASK
                )

                putExtra(
                    "blockedApp",
                    packageName
                )

                putExtra(
                    "reason",
                    reason
                )

                putExtra(
                    "endTime",
                    endTime
                )
            }

        try {

            startActivity(intent)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed launching block overlay",
                e
            )
        }
    }

    // ============================================================
    // SEND APP ALERT
    // ============================================================

    private fun sendAppAlert(
        app: String,
        reason: String
    ) {

        val alert =
            mapOf(
                "app" to app,
                "reason" to reason,
                "timestamp" to
                        System.currentTimeMillis()
            )

        db.child("children")
            .child(childId)
            .child("app_alerts")
            .push()
            .setValue(alert)
    }

    // ============================================================
    // USAGE ACCESS
    // ============================================================

    private fun hasUsageAccess(): Boolean {

        val appOps =
            getSystemService(
                Context.APP_OPS_SERVICE
            ) as AppOpsManager

        val mode =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )

        return mode ==
                AppOpsManager.MODE_ALLOWED
    }

    // ============================================================
    // FOREGROUND APP
    // ============================================================

    private fun getForegroundApp(): String? {

        val usm =
            getSystemService(
                Context.USAGE_STATS_SERVICE
            ) as UsageStatsManager

        val endTime =
            System.currentTimeMillis()

        val stats =
            usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                endTime - 60_000L,
                endTime
            )

        if (
            stats.isNullOrEmpty()
        ) {
            return null
        }

        return stats
            .maxByOrNull {
                it.lastTimeUsed
            }
            ?.packageName
    }

    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }

    // ============================================================
    // BLOCK SCHEDULE
    // ============================================================

    data class BlockSchedule(
        val startHour: Int,
        val endHour: Int
    )
}