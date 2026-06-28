package com.parentalcontrol.childapp.utils

import com.parentalcontrol.childapp.model.AppSchedule

object AppScheduleManager {

    fun isWithinBlockedWindow(schedule: AppSchedule): Boolean {
        val now = System.currentTimeMillis()
        return now in schedule.startTime until schedule.endTime
    }

    fun getRemainingTime(schedule: AppSchedule): Long {
        val now = System.currentTimeMillis()
        return (schedule.endTime - now).coerceAtLeast(0L)
    }
}