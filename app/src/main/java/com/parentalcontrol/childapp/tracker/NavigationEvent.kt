package com.parentalcontrol.childapp.tracker

data class NavigationEvent(
    val url: String,
    val domain: String,
    val packageName: String,
    val title: String? = null,
    val visibleText: String? = null,
    val searchQuery: String? = null,
    val videoTitle: String? = null,
    val type: String,
    val timestamp: Long
)