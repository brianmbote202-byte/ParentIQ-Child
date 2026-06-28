package com.parentalcontrol.childapp

import android.annotation.SuppressLint
import android.app.NotificationChannel
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
import com.google.firebase.database.FirebaseDatabase
import java.util.*
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import android.app.NotificationManager


class OverlayLockService : Service() {

    private var overlayView: View? = null

    private var approvalListener: ValueEventListener? = null

    companion object {
        var isRunning = false
    }

    @SuppressLint("InflateParams", "DiscouragedApi")
    override fun onCreate() {
        super.onCreate()

        startAsForeground()

        isRunning = true



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
        // CLOSE BUTTON
        // =====================================


        // =====================================
        // EXIT BUTTON
        // =====================================



        // =====================================
        // LIVE COUNTDOWN
        // =====================================

        Timer().scheduleAtFixedRate(
            object : TimerTask() {

                override fun run() {

                    countdown.post {

                        val cal = Calendar.getInstance()

                        val hour =
                            cal.get(Calendar.HOUR_OF_DAY)

                        val minute =
                            cal.get(Calendar.MINUTE)

                        val second =
                            cal.get(Calendar.SECOND)

                        // CURRENT TIME
                        val currentCalendar =
                            Calendar.getInstance()

                        val currentTime =
                            android.text.format.DateFormat.format(
                                "hh:mm:ss a",
                                currentCalendar
                            ).toString()
                        countdown.text =
                            "Current Time • $currentTime"

                        // =====================================
                        // GET END SCREEN TIME
                        // =====================================

                        val prefs = getSharedPreferences(
                            "screen_time_prefs",
                            MODE_PRIVATE
                        )

                        val unlockHour =
                            prefs.getInt("end_hour", 20)

                        val unlockMinute =
                            prefs.getInt("end_minute", 0)

                        // =====================================
                        // UNLOCK TIME TEXT
                        // =====================================

                        val unlockCalendar =
                            Calendar.getInstance()

                        unlockCalendar.set(
                            Calendar.HOUR_OF_DAY,
                            unlockHour
                        )

                        unlockCalendar.set(
                            Calendar.MINUTE,
                            unlockMinute
                        )

                        val unlockFormatted =
                            android.text.format.DateFormat.format(
                                "hh:mm a",
                                unlockCalendar
                            ).toString()

                        unlockTimeText.text =
                            "Unlocks At • $unlockFormatted"

                        // =====================================
                        // REMAINING TIME
                        // =====================================

                        val currentSeconds =
                            hour * 3600 +
                                    minute * 60 +
                                    second

                        val unlockSeconds =
                            unlockHour * 3600 +
                                    unlockMinute * 60

                        var remaining =
                            unlockSeconds - currentSeconds

                        if (remaining < 0) {
                            remaining = 0
                        }

                        val remHours =
                            remaining / 3600

                        val remMinutes =
                            (remaining % 3600) / 60

                        val remSeconds =
                            remaining % 60

                        timeRemainingText.text =
                            String.format(
                                "Time Remaining • %02dh %02dm %02ds",
                                remHours,
                                remMinutes,
                                remSeconds
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
        listenForApprovals()

        Log.d(
            "OVERLAY_SERVICE",
            "✅ Overlay shown"
        )
    }

    // =====================================
    // FIREBASE REQUEST
    // =====================================
    private fun requestExtraTime(
        minutes: Int
    ) {

        val prefs = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        )

        val childId = prefs.getString(
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

        val requestsRef = FirebaseDatabase.getInstance()
            .reference
            .child("requests")
            .child(childId)

        // =====================================
        // CHECK FOR EXISTING PENDING REQUEST
        // =====================================

        requestsRef.get()

            .addOnSuccessListener { snapshot ->

                var hasPendingRequest = false

                for (snap in snapshot.children) {

                    val type = snap.child("type")
                        .getValue(String::class.java)

                    val status = snap.child("status")
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
                // BLOCK DUPLICATE REQUESTS
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
                // CREATE NEW REQUEST
                // =====================================

                val requestId = requestsRef
                    .push()
                    .key ?: return@addOnSuccessListener

                val requestData = mapOf(

                    "requestId" to requestId,

                    "type" to "extra_time",

                    "minutes" to minutes,

                    "status" to "pending",

                    "timestamp" to System.currentTimeMillis()
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

                        // OPTIONAL:
                        // disable button after request
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

    private fun listenForApprovals() {

        val prefs = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        )

        val childId = prefs.getString(
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

        FirebaseDatabase.getInstance()
            .reference
            .child("requests")
            .child(childId)

            .addValueEventListener(object : ValueEventListener {

                override fun onDataChange(snapshot: DataSnapshot) {

                    Log.d(
                        "APPROVAL_LISTENER",
                        "🔥 onDataChange triggered"
                    )

                    for (snap in snapshot.children) {

                        val status = snap.child("status")
                            .getValue(String::class.java)
                            ?: continue

                        Log.d(
                            "APPROVAL_LISTENER",
                            "📥 status = $status"
                        )

                        if (status == "approved") {

                            val minutes = snap.child("minutes")
                                .getValue(Int::class.java)
                                ?: 15

                            Log.d(
                                "APPROVAL_LISTENER",
                                "✅ APPROVED FOR $minutes MINUTES"
                            )

                            grantExtraTime(minutes)

                            // =====================================
                            // MARK REQUEST AS USED
                            // =====================================

                            snap.ref.child("status")
                                .setValue("used")

                            Toast.makeText(
                                this@OverlayLockService,
                                "$minutes extra minutes granted",
                                Toast.LENGTH_LONG
                            ).show()

                            stopOverlay()

                            break
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {

                    Log.e(
                        "APPROVAL_LISTENER",
                        "❌ Firebase failed",
                        error.toException()
                    )
                }
            })
    }

    //===========extra time granted=======
    /*private fun grantExtraTime(minutes: Int) {

        val prefs = getSharedPreferences(
            "screen_time_prefs",
            MODE_PRIVATE
        )

        val extraUntil =
            System.currentTimeMillis() +
                    (minutes * 60 * 1000)

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
    }*/
    private fun grantExtraTime(minutes: Int) {

        val prefs = getSharedPreferences(
            "screen_time_prefs",
            MODE_PRIVATE
        )

        val now =
            System.currentTimeMillis()

        val extraUntil =
            now + (minutes * 60 * 1000)

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

    private fun startAsForeground() {

        val channelId = "overlay_lock_channel"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "Screen Lock",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Screen Lock Active")
            .setContentText("Monitoring screen time...")
            .setOngoing(true)
            .build()

        startForeground(1002, notification)
    }
    //stop lock overlay
    private fun stopOverlay() {

        try {

            if (overlayView != null) {

                val windowManager =
                    getSystemService(WINDOW_SERVICE)
                            as WindowManager

                windowManager.removeView(overlayView)

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


    override fun onDestroy() {
        super.onDestroy()

        isRunning = false

        if (overlayView != null) {

            val windowManager =
                getSystemService(WINDOW_SERVICE)
                        as WindowManager

            windowManager.removeView(
                overlayView
            )
        }
    }

    //listen for approvals


    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}