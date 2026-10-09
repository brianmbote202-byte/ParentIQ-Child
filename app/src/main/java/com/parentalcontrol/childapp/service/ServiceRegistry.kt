package com.parentalcontrol.childapp.service

object ServiceRegistry {

    /**
     * Services that Guardian may monitor diagnostically.
     *
     * IMPORTANT:
     * Guardian does NOT automatically restart these services.
     *
     * VPN has its own dedicated recovery path.
     * Accessibility and NotificationListener are managed by Android.
     * MediaProjection requires user authorization.
     */
    val CORE_SERVICES = listOf(
        ScreenTimeService::class.java,
        ChildLocationService::class.java,
        MonitoringService::class.java
    )

    const val FOREGROUND_HEALTH_CHECK_INTERVAL = 60_000L
}