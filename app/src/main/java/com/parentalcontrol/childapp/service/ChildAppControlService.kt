package com.parentalcontrol.childapp.service

import android.app.*
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.google.firebase.database.*
import com.parentalcontrol.childapp.model.AppRule
import android.app.AppOpsManager
import android.os.Process

class ChildAppControlService : Service() {

    // dynamic app rules from parent
    private val monitoredApps = mutableMapOf<String, AppRule>()
    private var blockSchedule: BlockSchedule? = null

    private lateinit var db: DatabaseReference
    private lateinit var childId: String
    private val handler = Handler(Looper.getMainLooper())

    private var rulesListener: ValueEventListener? = null
    private var scheduleListener: ValueEventListener? = null

    override fun onCreate() {
        Log.e("CHILD_SERVICE", "SERVICE STARTED")
        super.onCreate()

        db = FirebaseDatabase.getInstance().reference
        val prefs = getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        childId = prefs.getString("child_id", "child123") ?: "child123"

        startForegroundService()
        listenToParentRules()     // listen for per-app rules
        listenToBlockSchedule()   // listen for schedules
        startUsageTracking()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.e("CHILD_SERVICE", "onStartCommand triggered")

        return START_STICKY
    }

    override fun onDestroy() {

        // -----------------------------------
        // REMOVE FIREBASE LISTENER (IMPORTANT)
        // -----------------------------------
        rulesListener?.let {
            db.child("app_rules")
                .child(childId)
                .removeEventListener(it)
        }

        // OPTIONAL: if you also added schedule listener
        scheduleListener?.let {
            db.child("app_rules")
                .child(childId)
                .child("block_schedule")
                .removeEventListener(it)
        }

        // -----------------------------------
        // STOP HANDLER LOOP (VERY IMPORTANT)
        // -----------------------------------
        handler.removeCallbacksAndMessages(null)

        Log.e("ChildService", "Service destroyed and listeners removed")

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val restartIntent = Intent(applicationContext, ChildAppControlService::class.java)
        restartIntent.setPackage(packageName)
        startService(restartIntent)

        super.onTaskRemoved(rootIntent)
    }

    // -----------------------------
    // 1️⃣ Foreground service
    // -----------------------------
    private fun startForegroundService() {
        val channelId = "child_control_channel"
        val channel = NotificationChannel(
            channelId,
            "Child Control Service",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Parental Control Active")
            .setContentText("Monitoring app usage and notifications")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()

        startForeground(1, notification)
    }

    // -----------------------------
    // 2️⃣ Listen to parent app rules
    // -----------------------------

    private fun listenToParentRules() {

        val ref = db.child("app_rules").child(childId)

        // IMPORTANT: remove old listener first (prevents duplicates)
        rulesListener?.let {
            ref.removeEventListener(it)
        }

        rulesListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {

                monitoredApps.clear()

                if (!snapshot.exists()) {
                    Log.d("RULES_DEBUG", "No app rules found for childId=$childId")
                    return
                }

                for (appSnap in snapshot.children) {

                    val rule = appSnap.getValue(AppRule::class.java)

                    val rawKey = appSnap.key

                    if (rule == null || rawKey.isNullOrBlank()) {
                        continue
                    }

                    val packageName = rawKey.replace("_", ".")

                    monitoredApps[packageName] = rule

                    Log.d(
                        "RULES_DEBUG",
                        "Loaded rule -> $packageName | blocked=${rule.blocked} | limit=${rule.daily_limit}"
                    )
                }

                Log.d(
                    "ChildService",
                    "Rules updated successfully. Total apps=${monitoredApps.size}"
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("RULES_DEBUG", "Firebase cancelled: ${error.message}")
            }
        }

        ref.addValueEventListener(rulesListener!!)
    }

    // -----------------------------
    // 3️⃣ Listen to optional block schedule
    // -----------------------------
    private fun listenToBlockSchedule() {

        db.child("app_rules")
            .child(childId)
            .child("block_schedule")
            .addValueEventListener(object : ValueEventListener {

                override fun onDataChange(snapshot: DataSnapshot) {

                    val startHour =
                        snapshot.child("start_hour")
                            .getValue(Int::class.java)

                    val endHour =
                        snapshot.child("end_hour")
                            .getValue(Int::class.java)

                    if (startHour != null && endHour != null) {

                        blockSchedule = BlockSchedule(
                            startHour,
                            endHour
                        )

                        Log.d(
                            "ChildService",
                            "Updated block schedule: $blockSchedule"
                        )
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }

    // -----------------------------
    // 4️⃣ App Usage Tracking & Enforcement
    // -----------------------------
    private fun startUsageTracking() {
        if (!hasUsageAccess()) {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        handler.post(object : Runnable {
            override fun run() {
                enforceLimits()
                handler.postDelayed(this, 15000)
            }
        })
    }

    private fun enforceLimits() {
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        val inBlockedTime = blockSchedule?.let {
            if (it.startHour <= it.endHour) hour in it.startHour until it.endHour
            else hour >= it.startHour || hour < it.endHour
        } ?: false

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 24*60*60*1000L
        val statsList = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)

        statsList.forEach { stats ->

            val pkg = stats.packageName
            val rule = monitoredApps[pkg] ?: return@forEach

            val totalTime = stats.totalTimeInForeground
            val isOverLimit = rule.daily_limit > 0 && totalTime > rule.daily_limit

            val isBlockedTime = inBlockedTime

            val isNightBlocked = rule.block_after_9pm && hour >= 21

            val isHardBlocked = rule.blocked

            if (isHardBlocked) {
                blockApp(pkg)
                sendAppAlert(pkg, "Blocked by parent")
                return@forEach
            }

            if (isBlockedTime) {
                blockApp(pkg)
                sendAppAlert(pkg, "Blocked by schedule")
                return@forEach
            }

            if (isOverLimit && rule.block_after_limit) {
                blockApp(pkg)
                sendAppAlert(pkg, "Limit exceeded")
                return@forEach
            }

            if (isNightBlocked) {
                blockApp(pkg)
                sendAppAlert(pkg, "Blocked after 9 PM")
                return@forEach
            }
        }
    }

    private fun blockApp(packageName: String) {
        val intent = Intent(this, AppBlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }

        intent.putExtra("blockedApp", packageName)
        startActivity(intent)
    }
    private fun sendAppAlert(app: String, reason: String) {
        val alert = mapOf(
            "app" to app,
            "reason" to reason,
            "timestamp" to System.currentTimeMillis()
        )
        db.child("children").child(childId).child("app_alerts").push().setValue(alert)
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager

        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            packageName
        )

        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // -----------------------------
    // Data classes
    // -----------------------------
    data class AppRule(
        val allowed_from_hour: Int = 0,
        val allowed_from_minute: Int = 0,
        val allowed_to_hour: Int = 23,
        val allowed_to_minute: Int = 59,
        val block_after_9pm: Boolean = false,
        val block_after_limit: Boolean = false,
        val blocked: Boolean = false,
        val daily_limit: Long = 0L,
        val display_name: String = ""
    )

    data class BlockSchedule(
        val startHour: Int,
        val endHour: Int
    )
}