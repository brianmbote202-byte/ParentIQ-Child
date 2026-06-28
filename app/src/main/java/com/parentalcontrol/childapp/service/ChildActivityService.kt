package com.parentalcontrol.childapp.service

import com.parentalcontrol.childapp.service.ScreenTimeOverlayActivity
import android.app.*
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.CallLog
import android.provider.Telephony
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.MainActivity
import kotlinx.coroutines.*

class ChildActivityService : Service() {

    private var childId: String = "unknown_child"
    private var usageStartTime = System.currentTimeMillis()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        childId = intent?.getStringExtra("childId") ?: "unknown_child"
        startLoggingLoop()
        return START_STICKY
    }

    /** Persistent notification for foreground service */
    private fun startForegroundServiceNotification() {
        val channelId = "child_activity_service"
        val channelName = "Child Activity Service"
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Child Activity Monitoring")
            .setContentText("Monitoring device activity")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()

        startForeground(2, notification)
    }

    /** Coroutine loop: logs activity every minute */
    private fun startLoggingLoop() {
        scope.launch {
            while (isActive) {
                logCallHistory()
                logSmsHistory()
                logWebsiteActivity()
                updateScreenUsage()
                delay(60_000) // every 1 minute
            }
        }
    }

    /** Logs last 24h call history */
    private fun logCallHistory() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val uri: Uri = CallLog.Calls.CONTENT_URI
        val cursor: Cursor? = contentResolver.query(
            uri, null,
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(cutoff.toString()),
            CallLog.Calls.DATE + " DESC"
        )

        cursor?.use {
            val firebaseRef = FirebaseDatabase.getInstance().getReference("activity_logs/$childId")
            while (it.moveToNext()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                val type = it.getInt(it.getColumnIndexOrThrow(CallLog.Calls.TYPE))
                val timestamp = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DATE))
                val duration = it.getLong(it.getColumnIndexOrThrow(CallLog.Calls.DURATION))
                val callType = when (type) {
                    CallLog.Calls.INCOMING_TYPE -> "Incoming Call"
                    CallLog.Calls.OUTGOING_TYPE -> "Outgoing Call"
                    CallLog.Calls.MISSED_TYPE -> "Missed Call"
                    else -> "Other"
                }
                val data = mapOf(
                    "name" to number,
                    "type" to callType,
                    "timestamp" to timestamp,
                    "duration" to duration
                )
                firebaseRef.child(timestamp.toString()).setValue(data)
            }
        }
    }

    /** Logs last 24h SMS history */
    private fun logSmsHistory() {
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000
        val uri: Uri = Telephony.Sms.CONTENT_URI
        val cursor: Cursor? = contentResolver.query(
            uri, null,
            "${Telephony.Sms.DATE} >= ?",
            arrayOf(cutoff.toString()),
            Telephony.Sms.DATE + " DESC"
        )

        cursor?.use {
            val firebaseRef = FirebaseDatabase.getInstance().getReference("activity_logs/$childId")
            while (it.moveToNext()) {
                val address = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                val body = it.getString(it.getColumnIndexOrThrow(Telephony.Sms.BODY))
                val type = it.getInt(it.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                val timestamp = it.getLong(it.getColumnIndexOrThrow(Telephony.Sms.DATE))

                val smsType = when (type) {
                    Telephony.Sms.MESSAGE_TYPE_INBOX -> "Incoming SMS"
                    Telephony.Sms.MESSAGE_TYPE_SENT -> "Sent SMS"
                    else -> "Other SMS"
                }

                val data = mapOf(
                    "name" to address,
                    "type" to smsType,
                    "body" to body,
                    "timestamp" to timestamp
                )
                firebaseRef.child(timestamp.toString()).setValue(data)
            }
        }
    }

    /** Placeholder for website activity logging & block detection */
    private fun logWebsiteActivity() {
        val firebaseRef = FirebaseDatabase.getInstance().getReference("activity_logs/$childId")
        val timestamp = System.currentTimeMillis()

        // Example: Replace with actual browser/WebView tracking
        val openedUrl = "https://example.com"

        val data = mapOf(
            "name" to "Browser",
            "type" to "website",
            "url" to openedUrl,
            "timestamp" to timestamp
        )
        firebaseRef.child(timestamp.toString()).setValue(data)
        Log.d("ChildActivityService", "Logged website: $openedUrl")

        // Check blocked websites via Firebase
        checkBlockedWebsites(openedUrl)
    }

    /** Check blocked websites and send alert if needed */
    private fun checkBlockedWebsites(url: String) {
        val rulesRef = FirebaseDatabase.getInstance().getReference("rules").child(childId)
        rulesRef.child("blockedWebsites").addListenerForSingleValueEvent(object : com.google.firebase.database.ValueEventListener {
            override fun onDataChange(snapshot: com.google.firebase.database.DataSnapshot) {
                val blocked = snapshot.children.mapNotNull { it.getValue(String::class.java) }
                if (blocked.any { url.contains(it, ignoreCase = true) }) {
                    sendAlertToFirebase(url)
                    launchLockOverlay()
                }
            }
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {}
        })
    }

    private fun launchLockOverlay() {
        val intent = Intent(this, ScreenTimeOverlayActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun sendAlertToFirebase(url: String) {
        val ref = FirebaseDatabase.getInstance().getReference("alerts").push()
        val alert = mapOf(
            "childId" to childId,
            "type" to "blocked_website",
            "url" to url,
            "timestamp" to System.currentTimeMillis()
        )
        ref.setValue(alert)
    }

    /** Update daily screen usage */
    private fun updateScreenUsage() {
        val prefs = getSharedPreferences("screen_usage", Context.MODE_PRIVATE)
        val used = prefs.getLong("used_today", 0L)
        val now = System.currentTimeMillis()
        prefs.edit().putLong("used_today", used + (now - usageStartTime)).apply()
        usageStartTime = now
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel() // Cancel coroutines
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
