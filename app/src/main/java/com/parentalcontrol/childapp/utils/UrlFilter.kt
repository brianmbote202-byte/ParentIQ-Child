package com.parentalcontrol.childapp.utils

object UrlFilter {

    fun isSystemUrl(url: String, appPackage: String): Boolean {
        return appPackage.contains("systemui") ||
                url.startsWith("https://app://") ||
                url.startsWith("app://") ||
                url.contains("com.android.systemui") ||
                url.contains("intent://") ||
                url.contains("about:blank")
    }

    fun normalize(url: String): String {
        return if (url.startsWith("http")) url else "https://$url"
    }
}