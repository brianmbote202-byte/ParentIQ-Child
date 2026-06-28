package com.parentalcontrol.childapp.model

data class AppSchedule(
    val packageName: String,
    val startTime: Long,
    val endTime: Long
)