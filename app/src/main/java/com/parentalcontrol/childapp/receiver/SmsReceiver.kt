package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat

class SmsReceiver : BroadcastReceiver() {

    private val TAG = "SmsReceiver"

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") return

        val bundle: Bundle? = intent.extras
        if (bundle != null) {
            val pdus = bundle["pdus"] as Array<*>

            var fullMessage = ""
            var sender = ""

            for (pdu in pdus) {
                val sms = SmsMessage.createFromPdu(pdu as ByteArray)
                sender = sms.originatingAddress ?: "unknown_number"
                fullMessage += sms.messageBody
            }

            val timestamp = System.currentTimeMillis()

            Log.d(TAG, "FULL Incoming SMS → $sender : $fullMessage")

            val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
            val childId = prefs.getString("child_id", "") ?: ""

            if (childId.isEmpty()) {
                Log.e(TAG, "Child ID missing")
                return
            }

            val serviceIntent = Intent(context, MessageService::class.java).apply {
                putExtra("type", "SMS")
                putExtra("childId", childId)
                putExtra("fromRaw", sender)
                putExtra("from", sender)
                putExtra("toRaw", "DEVICE")
                putExtra("to", "")
                putExtra("content", fullMessage) // 🔥 FULL MESSAGE HERE
                putExtra("timestamp", timestamp)
                putExtra("direction", "INCOMING")
            }

            ContextCompat.startForegroundService(context, serviceIntent)
        }
    }
}