package com.parentalcontrol.childapp.model

data class ChildModel(
    val childId: String = "",
    val name: String = ""
)

// Optional: a model for a call record (if you want to represent calls locally)
data class CallRecord(
    val number: String = "",
    val direction: String = "", // INCOMING / OUTGOING
    val timestamp: Long = 0L,
    val duration: Long = 0L,
    val provider: String = "Unknown",
    val country: String = "Unknown",
    val simSlot: Int = -1,
    val simCarrier: String = "Unknown",
    val subscriptionId: Int = -1

)

data class Message(
    val from: String = "",
    val to: String = "",
    val content: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val direction: String = "INCOMING" // or "OUTGOING"
)
data class AppRule(
    val daily_limit: Long = 0L,
    val block_after_limit: Boolean = false,
    val block_after_9pm: Boolean = false,
    val blocked: Boolean = false,
    val display_name: String = "",
    val allowed_from_hour: Int = 0,
    val allowed_from_minute: Int = 0,
    val allowed_to_hour: Int = 23,
    val allowed_to_minute: Int = 59,
    val use_time_limit: Boolean = false
)
data class Insight(
    val type: String,
    val message: String,
    val severity: String,
    val timestamp: Long = System.currentTimeMillis()
)

//--------safety model---------
data class SafetyScore(
    val score: Int,
    val level: String,
    val breakdown: Map<String, Int>
)

//------child app features----
data class Feature(
    val icon: Int,
    val title: String
)
