package com.parentalcontrol.childapp.tracker

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class PageMetadataResolver {

    fun resolveTitle(
        root: AccessibilityNodeInfo?,
        event: AccessibilityEvent?,
        packageName: String,
        url: String?
    ): String? {

        if (root == null) return null

        // 1. System window title (best)
        root.window?.toString()?.let {
            if (it.isNotBlank()) return it
        }

        // 2. Event text fallback
        event?.text?.joinToString(" ")?.trim()?.let {
            if (it.isNotBlank() && it.length < 200) return it
        }

        // 3. Chrome
        if (packageName.contains("chrome")) {
            ChromeExtractor.extractTitle(root)?.let { return it }
        }

        // 4. YouTube / TikTok
        MediaExtractor.extractTitle(packageName, root, url)?.let {
            return it
        }

        return null
    }
}