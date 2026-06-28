package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.CallLog
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class CallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CALL_DEBUG"
        private var lastState = TelephonyManager.CALL_STATE_IDLE
        private var callStartTime: Long = 0L
        private var currentNumber: String = ""
        private var isOutgoing = false
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        val childId = prefs.getString("child_id", null) ?: return

        Log.d(TAG, "📞 Action received: ${intent.action}")

        if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
            handlePhoneStateChanged(context, intent, childId)
        }
    }

    private fun handlePhoneStateChanged(context: Context, intent: Intent, childId: String) {
        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        val state = when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> TelephonyManager.CALL_STATE_RINGING
            TelephonyManager.EXTRA_STATE_OFFHOOK -> TelephonyManager.CALL_STATE_OFFHOOK
            TelephonyManager.EXTRA_STATE_IDLE -> TelephonyManager.CALL_STATE_IDLE
            else -> return
        }

        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: ""

        Log.d(TAG, "📡 State=$state LastState=$lastState Incoming=$incomingNumber")

        when (state) {

            TelephonyManager.CALL_STATE_RINGING -> {
                currentNumber = incomingNumber.ifBlank { "UNKNOWN" }
                isOutgoing = false
                Log.d(TAG, "📲 Incoming ringing → $currentNumber")
            }

            TelephonyManager.CALL_STATE_OFFHOOK -> {
                // Outgoing if previous state was not ringing
                isOutgoing = lastState != TelephonyManager.CALL_STATE_RINGING

                if (isOutgoing && currentNumber.isBlank()) {
                    currentNumber = getLastCallNumber(context)
                }

                Log.d(TAG, if (isOutgoing) "📤 OUTGOING started → $currentNumber"
                else "📲 INCOMING answered → $currentNumber")

                callStartTime = System.currentTimeMillis()
            }

            TelephonyManager.CALL_STATE_IDLE -> {
                if (lastState == TelephonyManager.CALL_STATE_OFFHOOK) {
                    handleCallEnd(context, childId)
                }
                currentNumber = ""
                isOutgoing = false
            }
        }

        lastState = state
    }

    //determine direction using CallLog at the end of the call
    private fun getLastCallType(context: Context): Int {
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null,
                null,
                null,
                "${CallLog.Calls.DATE} DESC"
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val typeIndex = it.getColumnIndex(CallLog.Calls.TYPE)
                    if (typeIndex != -1) {
                        return it.getInt(typeIndex)
                    }
                }
            }

            CallLog.Calls.MISSED_TYPE
        } catch (e: Exception) {
            Log.e(TAG, "❌ CallLog TYPE error: ${e.message}")
            CallLog.Calls.MISSED_TYPE
        }
    }


    private fun handleCallEnd(context: Context, childId: String) {
        val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)

        // Get the child name, fallback to DEVICE_xxxx if missing
        val childName = prefs.getString("child_name", null)
            ?: "DEVICE_${prefs.getString("child_id", "XXXX")?.takeLast(4)}"

        // Determine the call number
        val rawNumber = currentNumber.ifBlank { getLastCallNumber(context) }
        val cleanNumber = when {
            rawNumber.isBlank() -> "UNKNOWN"
            rawNumber.equals("PRIVATE", true) -> "PRIVATE"
            else -> rawNumber
        }

        // Duration & direction
        val duration = System.currentTimeMillis() - callStartTime
        val callType = getLastCallType(context)

        val direction = when (callType) {
            CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
            CallLog.Calls.INCOMING_TYPE -> "INCOMING"
            else -> "MISSED"
        }

        // From/To fields
        val from = if (isOutgoing) childName else cleanNumber
        val to = if (isOutgoing) cleanNumber else childName

        // Skip invalid or short calls
        if (duration < 2000 || cleanNumber == "UNKNOWN") {
            Log.d(TAG, "⚠️ Ignoring short or invalid call")
            return
        }

        // Send to CallService (Firebase)
        sendToService(context, childId, direction, cleanNumber, from, to, callStartTime, duration)
    }

    private fun sendToService(
        context: Context,
        childId: String,
        direction: String,
        number: String,
        from: String,
        to: String,
        timestamp: Long,
        duration: Long
    ) {
        try {
            val intent = Intent(context, CallService::class.java).apply {
                putExtra("childId", childId)
                putExtra("direction", direction)
                putExtra("number", number)
                putExtra("from", from)
                putExtra("to", to)
                putExtra("timestamp", timestamp)
                putExtra("duration", duration)
            }

            ContextCompat.startForegroundService(context, intent)
            Log.d(TAG, "🚀 Sent call → $direction $number")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Service start failed: ${e.message}")
        }
    }

    private fun getLastCallNumber(context: Context): String {
        return try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                "${CallLog.Calls.DATE} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(CallLog.Calls.NUMBER)
                    if (index != -1) return it.getString(index) ?: "UNKNOWN"
                }
            }
            "UNKNOWN"
        } catch (e: Exception) {
            Log.e(TAG, "❌ CallLog error: ${e.message}")
            "UNKNOWN"
        }
    }
}