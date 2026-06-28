package com.parentalcontrol.childapp

import android.content.Context
import android.util.Log
import com.google.firebase.database.*

class RuleMonitor(private val context: Context) {

    private val db = FirebaseDatabase.getInstance().reference
    private val dbRules = db.child("rules")
    private val dbAlerts = db.child("alerts")

    //===========screen time monitoring======
    private var startScreenTime: Long = 0L
    private var endScreenTime: Long = 0L
    private var lastWarningLevel: Int = -1

    private var ruleEventListener: ((String) -> Unit)? = null

    // ✅ Get the correct childId from shared prefs
    private fun getChildId(): String {
        val prefs = context.getSharedPreferences("child_prefs", Context.MODE_PRIVATE)
        return prefs.getString("child_id", null) ?: "unknown_child"
    }

    // ✅ Listen to device status updates for this child
    fun startListening() {
        val childId = getChildId()
        Log.d("RULE_MONITOR", "🚀 Listening for rules for child: $childId")

        dbRules.child(childId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val charging = snapshot.child("charging").getValue(String::class.java) ?: "Unknown"
                    val battery = snapshot.child("battery").getValue(Int::class.java) ?: -1
                    val internet = snapshot.child("internet").getValue(String::class.java) ?: "Unknown"
                    val device = snapshot.child("device").getValue(String::class.java) ?: "Unknown"
                    val lastUpdate = snapshot.child("last_update").getValue(Long::class.java) ?: 0L

                    //==========monitor screen time ========
                    startScreenTime = snapshot.child("startScreenTime").getValue(Long::class.java) ?: 0L
                    endScreenTime = snapshot.child("endScreenTime").getValue(Long::class.java) ?: 0L

                    //==============time check logic==========
                    val calendar = java.util.Calendar.getInstance()

                    val currentSeconds =
                        calendar.get(java.util.Calendar.HOUR_OF_DAY) * 3600 +
                                calendar.get(java.util.Calendar.MINUTE) * 60 +
                                calendar.get(java.util.Calendar.SECOND)

                    val remaining = endScreenTime - currentSeconds

                    //trigger smart alerts
                    when {
                        remaining <= 0 -> {
                            if (lastWarningLevel != 0) {
                                sendAlert("Screen time ended", "screen_time")
                                lastWarningLevel = 0
                            }
                        }

                        remaining <= 300 -> { // 5 min
                            if (lastWarningLevel != 1) {
                                sendAlert("5 minutes remaining", "screen_time")
                                lastWarningLevel = 1
                            }
                        }

                        remaining <= 600 -> { // 10 min
                            if (lastWarningLevel != 2) {
                                sendAlert("10 minutes remaining", "screen_time")
                                lastWarningLevel = 2
                            }
                        }
                    }

                    val isOffline = System.currentTimeMillis() - lastUpdate > 15000

                    val deviceText = when {
                        isOffline -> "🔴 Device Offline"
                        device == "On" -> "🟢 Device On"
                        else -> "🔴 Device Off"
                    }

                    val chargingText = if (charging == "Yes") "✅ Charging" else "❌ Not Charging"
                    val batteryText = "🔋 Battery: $battery%"
                    val internetText = if (internet == "On") "🌐 Internet On" else "❌ Internet Off"

                    val statusText = "$chargingText\n$batteryText\n$internetText\n$deviceText"

                    ruleEventListener?.invoke(statusText)
                    Log.d("RULE_MONITOR", statusText)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("RULE_MONITOR", "❌ Firebase error: ${error.message}")
                }
            })
    }

    // ✅ Centralized alert function with optional extra info
    fun sendAlert(
        message: String,
        type: String = "general",
        extra: Map<String, Any>? = null
    ) {
        val childId = getChildId()
        val timestamp = System.currentTimeMillis()

        Log.d("RULE_MONITOR", "🚨 Sending alert → $message")
        Log.d("RULE_MONITOR", "👶 childId: $childId")

        val alertData = mutableMapOf<String, Any>(
            "message" to message,
            "type" to type,
            "timestamp" to timestamp
        )

        extra?.let { alertData.putAll(it) }

        dbAlerts.child(childId)
            .push()
            .setValue(alertData)
            .addOnSuccessListener {
                Log.d("RULE_MONITOR", "✅ Alert saved successfully")
            }
            .addOnFailureListener { e ->
                Log.e("RULE_MONITOR", "❌ Failed to save alert: ${e.message}")
            }

        notify(message)
    }

    // ✅ Backward compatibility for older checkViolation usage
    fun checkViolation(content: String) {
        sendAlert(content)
    }

    // ✅ Set listener for UI updates or logs
    fun setOnRuleEventListener(listener: (String) -> Unit) {
        ruleEventListener = listener
    }

    // Internal helper
    private fun notify(message: String) {
        ruleEventListener?.invoke(message)
        Log.d("RULE_MONITOR", message)
    }
}