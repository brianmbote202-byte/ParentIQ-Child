package com.parentalcontrol.childapp.receiver

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.Constants
import com.parentalcontrol.childapp.R

class MessageService : Service() {

    companion object {
        private const val TAG = "MessageService"
        private const val CHANNEL_ID = "message_service_channel"
        private const val NOTIF_ID = 101
    }

    private lateinit var database: FirebaseDatabase
    private var smsObserver: SmsObserver? = null
    private var childId: String = ""

    // Cache to merge multi-part incoming SMS by sender
    private val lastIncomingCache = mutableMapOf<String, Pair<Long, String>>() // sender -> (timestamp, content)

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        startForegroundSafely()
        Log.d(TAG, "MessageService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val prefs = getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
            val savedChildId = prefs.getString("child_id", "")
            val intentChildId = intent?.getStringExtra("childId")
            childId = intentChildId?.takeIf { it.isNotEmpty() } ?: savedChildId ?: ""

            if (childId.isEmpty()) {
                Log.e(TAG, "No childId — stopping service")
                stopSelf()
                return START_NOT_STICKY
            }

            // Start SMS observer if not already running
            if (smsObserver == null) {
                smsObserver = SmsObserver(this)
                smsObserver?.start()
                Log.d(TAG, "Outgoing SMS observer started")
            }

            // Handle incoming intent
            val type = intent?.getStringExtra("type") ?: "SMS"
            when (type) {
                "SMS" -> intent?.let { handleSms(it) }
                "SECURITY" -> intent?.let { handleSecurity(it) }
                else -> Log.d(TAG, "Unknown intent type: $type")
            }

        } catch (e: Exception) {
            Log.e(TAG, "onStartCommand error", e)
            stopSelf()
        }

        return START_STICKY
    }

    //----------refer to



    //--------helper save message dats to firebase into "messages" node-------
    private fun messagesRef(id: String) =
        FirebaseDatabase.getInstance()
            .getReference("messages")
            .child(id)

    /**
     * Normalize numbers for internal storage
     * Alphanumeric senders (MPESA, BANK, etc.) are kept as-is
     */
    private fun normalizeNumber(number: String?, fallback: String = "unknown_number"): String {
        if (number.isNullOrBlank()) return fallback
        val cleaned = number.trim()
        return if (cleaned.any { it.isLetter() }) cleaned.uppercase() else cleaned.replace("[^\\d+]".toRegex(), "").ifEmpty { fallback }
    }

    /**
     * Handle incoming/outgoing SMS and store in Firebase
     */
    private fun handleSms(intent: Intent) {
        val direction = intent.getStringExtra("direction") ?: "INCOMING"
        val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
        val content = intent.getStringExtra("content")?.takeIf { it.isNotBlank() } ?: "(No Content)"

        val sdfDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        val sdfMonth = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.getDefault())

        val date = sdfDate.format(timestamp)
        val month = sdfMonth.format(timestamp)
        val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)

        // Raw sender/receiver names
        val fromRaw = intent.getStringExtra("fromRaw")?.takeIf { it.isNotBlank() } ?: "UNKNOWN_NUMBER"
        val toRaw = intent.getStringExtra("toRaw")?.takeIf { it.isNotBlank() } ?: "DEVICE"

        // Normalized numbers for internal storage
        val fromDigits = normalizeNumber(intent.getStringExtra("from"), "UNKNOWN_NUMBER")
        val toDigits = normalizeNumber(intent.getStringExtra("to"), "DEVICE")

        // Conversation key based on raw sender/receiver
        //val conversationKey = if (direction == "INCOMING") fromRaw.uppercase() else toRaw.uppercase()
        val safeConversationKey =
            if (fromDigits != "UNKNOWN_NUMBER") fromDigits
            else toDigits




        // Merge multi-part SMS for incoming messages
        var finalContent = content
        if (direction == "INCOMING") {
            val cache = lastIncomingCache[safeConversationKey]
            if (cache != null && (timestamp - cache.first) <= 2000) { // 2-second window
                finalContent = cache.second + content
            }
            lastIncomingCache[safeConversationKey] = Pair(timestamp, finalContent)
        }

        val messageData = mapOf(
            "content" to finalContent,
            "direction" to direction,
            "timestamp" to timestamp,

            // IMPORTANT FOR FILTERING
            "date" to date,
            "month" to month,
            "year" to year
        )

        val conversationRef = messagesRef(childId)
            .child("conversations")
            .child(safeConversationKey)

        // Push the message
        conversationRef.child("messages").push().setValue(messageData)
            .addOnSuccessListener { Log.d(TAG, "Message saved for $safeConversationKey") }
            .addOnFailureListener { e -> Log.e(TAG, "Failed to save message", e) }

        // Update lastMessage
        val lastMessageData = mapOf(
            "content" to finalContent,
            "direction" to direction,
            "timestamp" to timestamp,

            // 🔥 ADD THESE
            "date" to date,
            "month" to month,
            "year" to year
        )
        conversationRef.child("lastMessage").setValue(lastMessageData)
    }

    /**
     * Handle security events
     */
    private fun handleSecurity(intent: Intent) {
        val event = intent.getStringExtra("event") ?: "UNKNOWN"
        val data = mapOf(
            "type" to "SECURITY",
            "event" to event,
            "timestamp" to System.currentTimeMillis()
        )

        messagesRef(childId)
            .child("security")
            .push().setValue(data)

    }

    /**
     * Start foreground service safely
     */
    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Messages Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setShowBadge(false)
                val manager = getSystemService(NotificationManager::class.java)
                manager?.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring SMS messages")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Foreground start failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smsObserver?.stop()
        Log.d(TAG, "MessageService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}