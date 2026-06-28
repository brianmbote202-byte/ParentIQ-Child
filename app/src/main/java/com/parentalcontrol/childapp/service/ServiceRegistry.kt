package com.parentalcontrol.childapp.service

import kotlin.jvm.java

object ServiceRegistry {

    val CORE_SERVICES = listOf(
        com.parentalcontrol.childapp.service.ScreenTimeService::class.java,
        com.parentalcontrol.childapp.service.ChildLocationService::class.java,
        com.parentalcontrol.childapp.vpn.DnsVpnService::class.java,
        com.parentalcontrol.childapp.service.MonitoringService::class.java,

        com.parentalcontrol.childapp.service.BrowsingTracker::class.java
        //com.parentalcontrol.childapp.service.BrowserAccessibilityService::class.java
    )

    val FOREGROUND_HEALTH_CHECK_INTERVAL = 60_000L
}