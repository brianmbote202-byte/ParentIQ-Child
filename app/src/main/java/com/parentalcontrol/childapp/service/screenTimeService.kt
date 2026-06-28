package com.parentalcontrol.childapp.service

import com.parentalcontrol.childapp.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.*
import com.parentalcontrol.childapp.OverlayLockService
import com.parentalcontrol.childapp.RuleMonitor
import java.text.SimpleDateFormat
import java.util.*

class ScreenTimeService : Service() {

    companion object {
        private const val TAG = "SCREEN_TIME_SERVICE"
        private const val CHANNEL_ID = "screen_time_channel"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning = false


    }

    // =====================================================
    // HANDLER
    // =====================================================

    private val handler = Handler(Looper.getMainLooper())

    // =====================================================
    // RULES
    // =====================================================

    private var childId: String = ""

    private var startScreenTime: Long = 0L
    private var endScreenTime: Long = 0L

    private var rulesLoaded = false



    // =====================================================
    // ALERTS
    // =====================================================

    private var screenTimeAlertSent = false


    private var isForegroundStarted = false
    private var rulesListener: ValueEventListener? = null

    // =====================================================
    // HELPERS
    // =====================================================

    private lateinit var ruleMonitor: RuleMonitor

    private val db =
        FirebaseDatabase.getInstance().reference

    // =====================================================
    // SERVICE CREATED
    // =====================================================

    override fun onCreate() {
        super.onCreate()
        Log.e(TAG, "🔥 ScreenTimeService CREATED")



        //isRunning = true

        ruleMonitor = RuleMonitor(this)


        Log.e(TAG, "✅ Foreground started")
    }

    // =====================================================
    // START COMMAND
    // =====================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {

            Log.e(TAG, "🚀 START COMMAND")

            // 🔥 START FOREGROUND IMMEDIATELY
            if (!isForegroundStarted) {

                startForegroundSafe()

                isForegroundStarted = true
            }

            // already running
            if (isRunning) {
                return START_STICKY
            }

            isRunning = true
            childId = getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            ).getString("child_id", "") ?: ""

            if (childId.isBlank()) {

                stopSelf()

                return START_NOT_STICKY
            }

            isRunning = true

            loadRules()

            startLoop()

        } catch (e: Exception) {

            Log.e(TAG, "❌ CRASH", e)

            stopSelf()
        }

        return START_STICKY
    }
    // =====================================================
    // FOREGROUND
    // =====================================================
    private fun startForegroundSafe() {

        try {

            val manager = getSystemService(NotificationManager::class.java)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Screen Time Service",
                    NotificationManager.IMPORTANCE_LOW
                )

                manager.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring screen time")
                .setSmallIcon(R.drawable.ic_lock_idle_alarm)
                .setOngoing(true)
                .build()

            startForeground(NOTIFICATION_ID, notification)

            Log.e(TAG, "✅ FOREGROUND OK")

        } catch (e: Exception) {

            Log.e(TAG, "❌ FOREGROUND FAILED", e)
            stopSelf()
        }
    }
    // =====================================================
    // LOAD FIREBASE RULES
    // =====================================================

    private fun loadRules() {

        Log.e(TAG, "📡 Loading Firebase rules")

        rulesListener = object : ValueEventListener {

            override fun onDataChange(snapshot: DataSnapshot) {

                Log.e(TAG, "📥 Firebase rules received")

                if (!snapshot.exists()) {
                    rulesLoaded = false
                    removeOverlay()
                    return
                }

                startScreenTime =
                    snapshot.child("startScreenTime")
                        .getValue(Long::class.java) ?: 0L

                endScreenTime =
                    snapshot.child("endScreenTime")
                        .getValue(Long::class.java) ?: 0L

                // =====================================
                // SAVE UNLOCK TIME LOCALLY
                // =====================================

                val endHour =
                    (endScreenTime / 3600).toInt()

                val endMinute =
                    ((endScreenTime % 3600) / 60).toInt()

                val prefs = getSharedPreferences(
                    "screen_time_prefs",
                    MODE_PRIVATE
                )

                prefs.edit()
                    .putInt("end_hour", endHour)
                    .putInt("end_minute", endMinute)
                    .apply()

                Log.e(
                    TAG,
                    "✅ Unlock time saved: $endHour:$endMinute"
                )

                if (startScreenTime == 0L && endScreenTime == 0L) {
                    rulesLoaded = false
                    removeOverlay()
                    return
                }

                rulesLoaded = true
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e(TAG, "❌ Firebase error: ${error.message}")
            }
        }

        db.child("rules")
            .child(childId)
            .addValueEventListener(rulesListener!!)
    }
    // =====================================================
    // LOOP
    // =====================================================

    private fun startLoop() {

        Log.e(TAG, "🔁 Starting loop")

        handler.removeCallbacksAndMessages(null)

        handler.post(
            object : Runnable {

                override fun run() {

                    Log.e(TAG, "🔄 Loop tick")

                    try {

                        checkScreenTime()

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "❌ checkScreenTime crash",
                            e
                        )
                    }

                    handler.postDelayed(
                        this,
                        30000
                    )
                }
            })
    }

    // =====================================================
    // CORE LOGIC
    // =====================================================

    private fun checkScreenTime() {

        Log.e(TAG, "🧠 checkScreenTime()")

        // =====================================================
        // RULES NOT READY
        // =====================================================

        if (!rulesLoaded) {

            Log.e(TAG, "⚠ Rules not loaded yet")

            return
        }

        val calendar =
            Calendar.getInstance()

        val currentSeconds =
            calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
                    calendar.get(Calendar.MINUTE) * 60 +
                    calendar.get(Calendar.SECOND)

        Log.e(
            TAG,
            "⏰ Current seconds = $currentSeconds"
        )

        val prefs =
            getSharedPreferences(
                "screen_time_prefs",
                MODE_PRIVATE
            )

        // =====================================================
        // EXTRA TIME
        // =====================================================

        val extraTimeUntil =
            prefs.getLong(
                "extra_time_until",
                0L
            )

        val nowMillis =
            System.currentTimeMillis()

        if (nowMillis < extraTimeUntil) {

            Log.e(TAG, "🟢 Extra time ACTIVE")

            removeOverlay()

            screenTimeAlertSent = false

            return
        }

        // =====================================================
        // DAILY RESET
        // =====================================================

        val today =
            SimpleDateFormat(
                "yyyyMMdd",
                Locale.getDefault()
            ).format(Date())

        val lastResetDay =
            prefs.getString(
                "last_reset_day",
                ""
            )

        if (today != lastResetDay) {

            prefs.edit()
                .putLong(
                    "extra_time_until",
                    0L
                )
                .putString(
                    "last_reset_day",
                    today
                )
                .apply()

            Log.e(TAG, "🔄 Daily reset complete")
        }

        // =====================================================
        // SCREEN TIME CHECK
        // =====================================================

        val allowed =
            currentSeconds in startScreenTime..endScreenTime

        Log.e(
            TAG,
            "📊 Allowed = $allowed"
        )

        if (!allowed) {

            Log.e(
                TAG,
                "🚨 SCREEN TIME LIMIT REACHED"
            )

            triggerLock()

            if (!screenTimeAlertSent) {

                Log.e(
                    TAG,
                    "📨 Sending parent alert"
                )

                ruleMonitor.sendAlert(
                    "Screen time limit reached",
                    "screen_time_limit"
                )

                screenTimeAlertSent = true
            }

        } else {

            Log.e(
                TAG,
                "✅ Screen time allowed"
            )

            removeOverlay()

            screenTimeAlertSent = false
        }
    }

    // =====================================================
    // REMOVE OVERLAY
    // =====================================================

    private fun removeOverlay() {

        if (!OverlayLockService.Companion.isRunning) {

            Log.e(
                TAG,
                "ℹ Overlay already removed"
            )

            return
        }

        Log.e(
            TAG,
            "🛑 Removing overlay"
        )

        stopService(
            Intent(
                this,
                OverlayLockService::class.java
            )
        )
    }

    // =====================================================
    // TRIGGER LOCK
    // =====================================================

    private fun triggerLock() {

        if (OverlayLockService.Companion.isRunning) {

            Log.e(
                TAG,
                "⚠ Overlay already running"
            )

            return
        }

        Log.e(
            TAG,
            "🚨 Starting OverlayLockService"
        )

        val intent =
            Intent(
                this,
                OverlayLockService::class.java
            )

        try {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(intent)

            } else {

                startService(intent)
            }

            Log.e(
                TAG,
                "✅ Overlay start requested"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed starting overlay",
                e
            )
        }
    }

    // =====================================================
    // TASK REMOVED
    // =====================================================

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        Log.e(
            TAG,
            "⚠ Task removed"
        )

        try {

            val restartIntent =
                Intent(
                    applicationContext,
                    ScreenTimeService::class.java
                )

            restartIntent.setPackage(packageName)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                startForegroundService(
                    restartIntent
                )

            } else {

                startService(
                    restartIntent
                )
            }

            Log.e(
                TAG,
                "✅ Restart requested"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Restart failed",
                e
            )
        }

        super.onTaskRemoved(rootIntent)
    }

    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {
        super.onDestroy()

        Log.e(TAG, "🔥 ScreenTimeService DESTROYED")

        isRunning = false

        handler.removeCallbacksAndMessages(null)
    }

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}