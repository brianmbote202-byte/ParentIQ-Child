package com.parentalcontrol.childapp.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.google.firebase.database.FirebaseDatabase

class NotificationMonitorService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifMonitor"

        private val monitoredApps = setOf(
            "com.whatsapp",
            "com.instagram.android",
            "com.snapchat.android",
            "org.telegram.messenger"
        )

        private val riskyWords = listOf(
            "sex",
            "nude",
            "drugs",
            "kill",
            "suicide",
            "meet alone",
            "send pic",
            "send photo"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {

        val packageName = sbn.packageName

        if (!monitoredApps.contains(packageName)) return

        val extras = sbn.notification.extras

        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""

        Log.d(TAG, "Notification from $packageName : $title -> $text")

        analyzeMessage(packageName, title, text)
    }

    private fun analyzeMessage(app: String, sender: String, message: String) {

        val lower = message.lowercase()

        for (word in riskyWords) {

            if (lower.contains(word)) {

                val alert = mapOf(
                    "type" to "SOCIAL_RISK",
                    "app" to app,
                    "from" to sender,
                    "message" to message,
                    "timestamp" to System.currentTimeMillis()
                )

                val prefs = getSharedPreferences("child_prefs", MODE_PRIVATE)
                val childId = prefs.getString("active_child_id", null) ?: return

                FirebaseDatabase.getInstance()
                    .getReference("aiAlerts")
                    .child(childId)
                    .push()
                    .setValue(alert)

                Log.d(TAG, "⚠ Risky social message detected")

                break
            }
        }
    }
}