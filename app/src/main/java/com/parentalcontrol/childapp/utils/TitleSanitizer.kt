package com.parentalcontrol.childapp.utils

object TitleSanitizer {

    fun cleanTitle(title: String?): String? {
        if (title.isNullOrBlank()) return null

        val t = title.lowercase().trim()

        val junk = listOf(
            "2 open tabs, tap to switch tabs",
            "tap to switch tabs",
            "web view",
            "connection is secure",
            "site information",
            "search or type web address",
            "loading",
            "tab switcher",
            "google"
        )

        if (junk.any { t.contains(it) }) return null

        if (t.length < 5) return null

        return title
    }
}