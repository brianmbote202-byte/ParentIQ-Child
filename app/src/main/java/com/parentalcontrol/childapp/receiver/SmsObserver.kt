package com.parentalcontrol.childapp.receiver

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import java.util.LinkedHashSet

class SmsObserver(private val context: Context) :
    ContentObserver(Handler(Looper.getMainLooper())) {

    private val TAG = "SmsObserver"

    // Keep a small history to avoid duplicates when processing batches
    private val processedKeys = LinkedHashSet<String>()
    private val MAX_CACHE = 50

    fun start() {
        context.contentResolver.registerContentObserver(
            Uri.parse("content://sms/inbox"),
            true,
            this
        )
        context.contentResolver.registerContentObserver(
            Uri.parse("content://sms/sent"),
            true,
            this
        )
        Log.d(TAG, "SmsObserver registered for inbox + sent")
    }

    fun stop() {
        context.contentResolver.unregisterContentObserver(this)
        Log.d(TAG, "SmsObserver unregistered")
    }

    override fun onChange(selfChange: Boolean) {
        super.onChange(selfChange)
        processLatestSmsBatch()
    }

    private fun processLatestSmsBatch() {
        try {
            val cursor: Cursor? = context.contentResolver.query(
                Uri.parse("content://sms"),
                arrayOf(
                    Telephony.Sms._ID,
                    Telephony.Sms.ADDRESS,
                    Telephony.Sms.BODY,
                    Telephony.Sms.DATE,
                    Telephony.Sms.TYPE
                ),
                null,
                null,
                Telephony.Sms.DATE + " DESC" // ❗ no LIMIT 1
            )

            cursor?.use { c ->
                if (!c.moveToFirst()) return

                var processedCount = 0
                val MAX_BATCH = 5 // process only the newest 5 rows per change

                do {
                    val type = c.getInt(c.getColumnIndexOrThrow(Telephony.Sms.TYPE))
                    val address = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS))
                        ?: "unknown_number"
                    val body = c.getString(c.getColumnIndexOrThrow(Telephony.Sms.BODY))
                        ?: "(No Content)"
                    val timestamp = c.getLong(c.getColumnIndexOrThrow(Telephony.Sms.DATE))

                    val direction = when (type) {
                        Telephony.Sms.MESSAGE_TYPE_INBOX -> "INCOMING"
                        Telephony.Sms.MESSAGE_TYPE_SENT -> "OUTGOING"
                        else -> "UNKNOWN"
                    }

                    // Strong dedupe key
                    val key = "$address-$timestamp-$direction-$type-$body"

                    if (processedKeys.contains(key)) {
                        // already handled
                    } else {
                        processedKeys.add(key)
                        trimCacheIfNeeded()

                        Log.d(TAG, "$direction SMS → $address : $body")

                        // fetch childId
                        val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
                        val childId = prefs.getString("child_id", "")
                        if (childId.isNullOrEmpty()) {
                            Log.e(TAG, "Child ID missing — cannot send SMS to MessageService")
                            return
                        }

                        val fromRaw: String
                        val fromDigits: String
                        val toRaw: String
                        val toDigits: String

                        if (direction == "INCOMING") {
                            fromRaw = address
                            fromDigits = address.replace("[^\\d+]".toRegex(), "")
                                .ifEmpty { "unknown_number" }
                            toRaw = "DEVICE"
                            toDigits = "DEVICE"
                        } else {
                            fromRaw = "DEVICE"
                            fromDigits = "DEVICE"
                            toRaw = address
                            toDigits = address.replace("[^\\d+]".toRegex(), "")
                                .ifEmpty { "unknown_number" }
                        }

                        val serviceIntent = Intent(context, MessageService::class.java).apply {
                            putExtra("type", "SMS")
                            putExtra("childId", childId)
                            putExtra("fromRaw", fromRaw)
                            putExtra("from", fromDigits)
                            putExtra("toRaw", toRaw)
                            putExtra("to", toDigits)
                            putExtra("content", body)
                            putExtra("timestamp", timestamp)
                            putExtra("direction", direction)
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            ContextCompat.startForegroundService(context, serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }

                        processedCount++
                    }

                } while (c.moveToNext() && processedCount < MAX_BATCH)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to read SMS", e)
        }
    }

    private fun trimCacheIfNeeded() {
        if (processedKeys.size > MAX_CACHE) {
            val iterator = processedKeys.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }
}