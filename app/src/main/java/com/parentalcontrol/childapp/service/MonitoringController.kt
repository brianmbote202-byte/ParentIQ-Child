package com.parentalcontrol.childapp.service

import android.content.Context
import android.util.Log
import com.parentalcontrol.childapp.RuleMonitor
import com.parentalcontrol.childapp.SummaryUpdater

class MonitoringController(
    private val context: Context,
    private val childId: String
) {

    private val ruleMonitor = RuleMonitor(context)
    private val summaryUpdater = SummaryUpdater(childId)

    private var alertsToday = 0

    fun start() {

        // Listen to rule/device status
        ruleMonitor.startListening()

        ruleMonitor.setOnRuleEventListener { status ->
            Log.d("MONITOR", status)
        }

        // Example: simulate alerts increasing
        // (Replace with real alert counting later)
        alertsToday++

        pushSummary()
    }

    private fun pushSummary() {

        val battery = getBatteryLevel()
        val online = true
        val location = getLocation()
        val screenTime = getScreenTime()

        summaryUpdater.pushDailySummary(
            name = "Child",
            battery = battery,
            online = online,
            location = location,
            screenTimeMinutes = screenTime,
            alertsToday = alertsToday
        )
    }

    // ---------------- MOCK / REPLACE WITH YOUR REAL LOGIC ----------------

    private fun getBatteryLevel(): Int = 75

    private fun getLocation(): String = "At Home"

    private fun getScreenTime(): Int = 120 // minutes
}