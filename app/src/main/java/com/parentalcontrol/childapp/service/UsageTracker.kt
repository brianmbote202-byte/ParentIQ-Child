package com.parentalcontrol.childapp.service

class UsageTracker {
    object UsageTracker {

        private val usageMap = mutableMapOf<String, Long>()
        private var lastApp: String? = null
        private var startTime: Long = 0

        fun onAppForeground(packageName: String) {
            val now = System.currentTimeMillis()

            // Save previous app usage
            if (lastApp != null) {
                val used = now - startTime
                usageMap[lastApp!!] = (usageMap[lastApp!!] ?: 0) + used
            }

            // Start new app timer
            lastApp = packageName
            startTime = now
        }

        fun getUsageToday(packageName: String): Long {
            return usageMap[packageName] ?: 0
        }
    }
}