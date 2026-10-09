package com.parentalcontrol.childapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.Calendar
import java.util.Locale
import java.util.Timer
import java.util.TimerTask


class OverlayLockService : Service() {

    private var overlayView: View? = null

    private var approvalListener: ValueEventListener? = null

    // Firebase:
    // rules/{childId}/endScreenTime
    //
    // Stored as seconds since midnight.
    //
    // Example:
    // 41700 = 11:35 PM
    private var firebaseEndScreenTimeSeconds: Long? = null

    private var countdownTimer: Timer? = null

    companion object {
        var isRunning = false
    }

    @SuppressLint("InflateParams", "DiscouragedApi")
    override fun onCreate() {
        super.onCreate()

        Log.d(
            "OVERLAY_SERVICE",
            "🚀 OverlayLockService onCreate()"
        )

        startAsForeground()

        isRunning = true

        // =====================================
        // LOAD FIREBASE SCREEN-TIME RULE
        // =====================================

        listenForEndScreenTime()

        // =====================================
        // WINDOW MANAGER
        // =====================================

        val windowManager =
            getSystemService(WINDOW_SERVICE)
                    as WindowManager

        // =====================================
        // INFLATE OVERLAY
        // =====================================

        overlayView = LayoutInflater.from(this)
            .inflate(
                R.layout.overlay_screen_time,
                null
            )

        // =====================================
        // FIND VIEWS
        // =====================================

        val btnRequestMoreTime =
            overlayView!!.findViewById<Button>(
                R.id.btnRequestMoreTime
            )

        val countdown =
            overlayView!!.findViewById<TextView>(
                R.id.tvCountdown
            )

        val unlockTimeText =
            overlayView!!.findViewById<TextView>(
                R.id.tvUnlockTime
            )

        val timeRemainingText =
            overlayView!!.findViewById<TextView>(
                R.id.tvTimeRemaining
            )

        // =====================================
        // REQUEST MORE TIME BUTTON
        // =====================================

        btnRequestMoreTime.setOnClickListener {

            Log.d(
                "REQUEST_TIME",
                "🔥 Overlay button clicked"
            )

            requestExtraTime(15)
        }

        // =====================================
        // LIVE CLOCK + COUNTDOWN
        // =====================================

        countdownTimer =
            Timer("ScreenTimeCountdown")

        countdownTimer?.scheduleAtFixedRate(
            object : TimerTask() {

                override fun run() {

                    countdown.post {

                        updateTimeDisplay(
                            countdown = countdown,
                            unlockTimeText = unlockTimeText,
                            timeRemainingText = timeRemainingText
                        )
                    }
                }
            },
            0,
            1000
        )

        // =====================================
        // OVERLAY PARAMS
        // =====================================

        val params = WindowManager.LayoutParams(

            WindowManager.LayoutParams.MATCH_PARENT,

            WindowManager.LayoutParams.MATCH_PARENT,

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,

            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,

            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP

        // =====================================
        // SHOW OVERLAY
        // =====================================

        windowManager.addView(
            overlayView,
            params
        )

        // =====================================
        // LISTEN FOR PARENT APPROVAL
        // =====================================

        listenForApprovals()

        Log.d(
            "OVERLAY_SERVICE",
            "✅ Overlay shown"
        )
    }

    // =========================================================
    // FIREBASE END SCREEN TIME
    // =========================================================

    private fun listenForEndScreenTime() {

        val prefs = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        )

        val childId = prefs.getString(
            "child_id",
            null
        )

        if (childId.isNullOrBlank()) {
            Log.e(
                "SCREEN_TIME",
                "❌ Cannot load endScreenTime - child ID missing"
            )
            return
        }

        val endScreenTimeRef = FirebaseDatabase.getInstance()
            .getReference("rules")
            .child(childId)
            .child("endScreenTime")

        Log.d(
            "SCREEN_TIME",
            "📡 Listening to: rules/$childId/endScreenTime"
        )

        endScreenTimeRef.addValueEventListener(
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {

                    val endSeconds =
                        snapshot.getValue(Long::class.java)

                    if (endSeconds == null) {

                        Log.e(
                            "SCREEN_TIME",
                            "❌ endScreenTime does not exist"
                        )

                        firebaseEndScreenTimeSeconds = null

                        return
                    }

                    firebaseEndScreenTimeSeconds = endSeconds

                    val formattedTime =
                        formatFirebaseTime(endSeconds)

                    Log.d(
                        "SCREEN_TIME",
                        "✅ Firebase endScreenTime = $endSeconds"
                    )

                    Log.d(
                        "SCREEN_TIME",
                        "🕐 Unlocks At = $formattedTime"
                    )
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {

                    Log.e(
                        "SCREEN_TIME",
                        "❌ Failed reading endScreenTime",
                        error.toException()
                    )
                }
            }
        )
    }

    // =========================================================
    // UPDATE CURRENT TIME / UNLOCK TIME / REMAINING TIME
    // =========================================================

    private fun updateTimeDisplay(
        countdown: TextView,
        unlockTimeText: TextView,
        timeRemainingText: TextView
    ) {

        val now =
            Calendar.getInstance()

        // =====================================
        // CURRENT TIME
        // =====================================

        val currentTime =
            android.text.format.DateFormat.format(
                "hh:mm:ss a",
                now
            ).toString()

        countdown.text =
            "Current Time • $currentTime"

        // =====================================
        // GET FIREBASE END TIME
        // =====================================

        val endSeconds =
            firebaseEndScreenTimeSeconds

        if (endSeconds == null) {

            unlockTimeText.text =
                "Unlocks At • Loading..."

            timeRemainingText.text =
                "Time Remaining • --h --m --s"

            return
        }

        // =====================================
        // UNLOCK TIME
        // =====================================

        val unlockFormatted =
            formatFirebaseTime(
                endSeconds
            )

        unlockTimeText.text =
            "Unlocks At • $unlockFormatted"

        // =====================================
        // CURRENT TIME IN SECONDS
        // =====================================

        val currentHour =
            now.get(Calendar.HOUR_OF_DAY)

        val currentMinute =
            now.get(Calendar.MINUTE)

        val currentSecond =
            now.get(Calendar.SECOND)

        val currentSeconds =
            currentHour * 3600L +
                    currentMinute * 60L +
                    currentSecond

        // =====================================
        // FIREBASE END TIME IN SECONDS
        // =====================================

        val unlockSeconds =
            endSeconds % (24L * 60L * 60L)

        // =====================================
        // CALCULATE REMAINING
        // =====================================

        var remaining =
            unlockSeconds - currentSeconds

        /*
         * If the configured unlock time has already
         * passed today, use the next occurrence.
         */
        if (remaining < 0) {

            remaining +=
                24L * 60L * 60L
        }

        // =====================================
        // FORMAT REMAINING
        // =====================================

        val remHours =
            remaining / 3600

        val remMinutes =
            (remaining % 3600) / 60

        val remSeconds =
            remaining % 60

        timeRemainingText.text =
            String.format(
                Locale.getDefault(),
                "Time Remaining • %02dh %02dm %02ds",
                remHours,
                remMinutes,
                remSeconds
            )
    }

    // =========================================================
    // CONVERT FIREBASE SECONDS → 12-HOUR TIME
    // =========================================================

    private fun formatFirebaseTime(
        secondsSinceMidnight: Long
    ): String {

        val secondsInDay =
            24L * 60L * 60L

        val totalSeconds =
            secondsSinceMidnight % secondsInDay

        val hour =
            (totalSeconds / 3600).toInt()

        val minute =
            ((totalSeconds % 3600) / 60).toInt()

        val calendar =
            Calendar.getInstance()

        calendar.set(
            Calendar.HOUR_OF_DAY,
            hour
        )

        calendar.set(
            Calendar.MINUTE,
            minute
        )

        calendar.set(
            Calendar.SECOND,
            0
        )

        calendar.set(
            Calendar.MILLISECOND,
            0
        )

        return android.text.format.DateFormat.format(
            "h:mm a",
            calendar
        ).toString()
    }

    // =========================================================
    // FIREBASE REQUEST
    // =========================================================

    private fun requestExtraTime(
        minutes: Int
    ) {

        val prefs = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        )

        val childId =
            prefs.getString(
                "child_id",
                "unknown_child"
            ) ?: "unknown_child"

        Log.d(
            "REQUEST_TIME",
            "👶 childId = $childId"
        )

        if (childId == "unknown_child") {

            Toast.makeText(
                this,
                "Child ID missing",
                Toast.LENGTH_SHORT
            ).show()

            return
        }

        val requestsRef =
            FirebaseDatabase.getInstance()
                .reference
                .child("requests")
                .child(childId)

        // =====================================
        // CHECK EXISTING PENDING REQUEST
        // =====================================

        requestsRef.get()

            .addOnSuccessListener { snapshot ->

                var hasPendingRequest = false

                for (snap in snapshot.children) {

                    val type =
                        snap.child("type")
                            .getValue(String::class.java)

                    val status =
                        snap.child("status")
                            .getValue(String::class.java)

                    if (
                        type == "extra_time" &&
                        status == "pending"
                    ) {

                        hasPendingRequest = true

                        break
                    }
                }

                // =====================================
                // BLOCK DUPLICATE REQUEST
                // =====================================

                if (hasPendingRequest) {

                    Toast.makeText(
                        this,
                        "You already have a pending request",
                        Toast.LENGTH_SHORT
                    ).show()

                    Log.d(
                        "REQUEST_TIME",
                        "⚠️ Pending request already exists"
                    )

                    return@addOnSuccessListener
                }

                // =====================================
                // CREATE REQUEST
                // =====================================

                val requestId =
                    requestsRef
                        .push()
                        .key
                        ?: return@addOnSuccessListener

                val requestData =
                    mapOf(

                        "requestId" to requestId,

                        "type" to "extra_time",

                        "minutes" to minutes,

                        "status" to "pending",

                        "timestamp" to
                                System.currentTimeMillis()
                    )

                requestsRef
                    .child(requestId)
                    .setValue(requestData)

                    .addOnSuccessListener {

                        Log.d(
                            "REQUEST_TIME",
                            "✅ REQUEST SENT"
                        )

                        Toast.makeText(
                            this,
                            "Request sent to parent",
                            Toast.LENGTH_SHORT
                        ).show()

                        // Disable until parent responds.
                        overlayView
                            ?.findViewById<Button>(
                                R.id.btnRequestMoreTime
                            )
                            ?.isEnabled = false
                    }

                    .addOnFailureListener {

                        Log.e(
                            "REQUEST_TIME",
                            "❌ FIREBASE FAILED",
                            it
                        )

                        Toast.makeText(
                            this,
                            "Failed to send request",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
            }

            .addOnFailureListener {

                Log.e(
                    "REQUEST_TIME",
                    "❌ Failed checking requests",
                    it
                )
            }
    }

    // =========================================================
    // LISTEN FOR PARENT APPROVAL
    // =========================================================

    private fun listenForApprovals() {

        val prefs =
            getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            )

        val childId =
            prefs.getString(
                "child_id",
                "unknown_child"
            ) ?: "unknown_child"

        if (childId == "unknown_child") {

            Log.e(
                "APPROVAL_LISTENER",
                "❌ Invalid child ID"
            )

            return
        }

        Log.d(
            "APPROVAL_LISTENER",
            "👶 Listening for childId = $childId"
        )

        approvalListener =
            object : ValueEventListener {

                override fun onDataChange(
                    snapshot: DataSnapshot
                ) {

                    Log.d(
                        "APPROVAL_LISTENER",
                        "🔥 onDataChange triggered"
                    )

                    for (snap in snapshot.children) {

                        val status =
                            snap.child("status")
                                .getValue(String::class.java)
                                ?: continue

                        Log.d(
                            "APPROVAL_LISTENER",
                            "📥 status = $status"
                        )

                        if (status == "approved") {

                            val minutes =
                                snap.child("minutes")
                                    .getValue(Int::class.java)
                                    ?: 15

                            Log.d(
                                "APPROVAL_LISTENER",
                                "✅ APPROVED FOR $minutes MINUTES"
                            )

                            // =====================================
                            // GRANT EXTRA TIME
                            // =====================================

                            grantExtraTime(minutes)

                            // =====================================
                            // MARK REQUEST AS USED
                            // =====================================

                            snap.ref
                                .child("status")
                                .setValue("used")

                            Toast.makeText(
                                this@OverlayLockService,
                                "$minutes extra minutes granted",
                                Toast.LENGTH_LONG
                            ).show()

                            // =====================================
                            // REMOVE LOCK
                            // =====================================

                            stopOverlay()

                            break
                        }
                    }
                }

                override fun onCancelled(
                    error: DatabaseError
                ) {

                    Log.e(
                        "APPROVAL_LISTENER",
                        "❌ Firebase failed",
                        error.toException()
                    )
                }
            }

        FirebaseDatabase.getInstance()
            .reference
            .child("requests")
            .child(childId)
            .addValueEventListener(
                approvalListener!!
            )
    }

    // =========================================================
    // GRANT EXTRA TIME
    // =========================================================

    private fun grantExtraTime(
        minutes: Int
    ) {

        val prefs =
            getSharedPreferences(
                "screen_time_prefs",
                MODE_PRIVATE
            )

        val now =
            System.currentTimeMillis()

        val extraUntil =
            now +
                    (minutes * 60 * 1000L)

        prefs.edit()
            .putLong(
                "extra_time_until",
                extraUntil
            )
            .apply()

        Log.d(
            "EXTRA_TIME",
            "✅ Extra time active until: $extraUntil"
        )
    }

    // =========================================================
    // FOREGROUND SERVICE
    // =========================================================

    private fun startAsForeground() {

        val channelId =
            "overlay_lock_channel"

        if (
            android.os.Build.VERSION.SDK_INT >=
            android.os.Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    channelId,
                    "Screen Lock",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }

        val notification =
            NotificationCompat.Builder(
                this,
                channelId
            )
                .setSmallIcon(
                    android.R.drawable.ic_lock_lock
                )
                .setContentTitle(
                    "Screen Lock Active"
                )
                .setContentText(
                    "Monitoring screen time..."
                )
                .setOngoing(true)
                .build()

        startForeground(
            1002,
            notification
        )
    }

    // =========================================================
    // STOP OVERLAY
    // =========================================================

    private fun stopOverlay() {

        try {

            countdownTimer?.cancel()
            countdownTimer = null

            if (approvalListener != null) {

                val prefs =
                    getSharedPreferences(
                        "child_prefs",
                        MODE_PRIVATE
                    )

                val childId =
                    prefs.getString(
                        "child_id",
                        null
                    )

                if (!childId.isNullOrBlank()) {

                    FirebaseDatabase.getInstance()
                        .reference
                        .child("requests")
                        .child(childId)
                        .removeEventListener(
                            approvalListener!!
                        )
                }

                approvalListener = null
            }

            if (overlayView != null) {

                val windowManager =
                    getSystemService(
                        WINDOW_SERVICE
                    ) as WindowManager

                windowManager.removeView(
                    overlayView
                )

                overlayView = null
            }

        } catch (e: Exception) {

            Log.e(
                "OVERLAY_SERVICE",
                "❌ Failed removing overlay",
                e
            )
        }

        isRunning = false

        stopSelf()

        Log.d(
            "OVERLAY_SERVICE",
            "✅ Overlay closed"
        )
    }

    // =========================================================
    // SERVICE DESTROYED
    // =========================================================

    override fun onDestroy() {

        Log.d(
            "OVERLAY_SERVICE",
            "🛑 OverlayLockService onDestroy()"
        )

        countdownTimer?.cancel()
        countdownTimer = null

        // Remove Firebase approval listener.
        try {

            if (approvalListener != null) {

                val prefs =
                    getSharedPreferences(
                        "child_prefs",
                        MODE_PRIVATE
                    )

                val childId =
                    prefs.getString(
                        "child_id",
                        null
                    )

                if (!childId.isNullOrBlank()) {

                    FirebaseDatabase.getInstance()
                        .reference
                        .child("requests")
                        .child(childId)
                        .removeEventListener(
                            approvalListener!!
                        )
                }

                approvalListener = null

            }

        } catch (e: Exception) {

            Log.e(
                "APPROVAL_LISTENER",
                "❌ Failed removing listener",
                e
            )
        }

        // Remove overlay.
        try {

            if (overlayView != null) {

                val windowManager =
                    getSystemService(
                        WINDOW_SERVICE
                    ) as WindowManager

                windowManager.removeView(
                    overlayView
                )

                overlayView = null
            }

        } catch (e: Exception) {

            Log.e(
                "OVERLAY_SERVICE",
                "❌ Failed removing overlay on destroy",
                e
            )
        }

        isRunning = false

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}