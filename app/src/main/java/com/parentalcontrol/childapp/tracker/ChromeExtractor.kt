package com.parentalcontrol.childapp.tracker

import android.view.accessibility.AccessibilityNodeInfo

object ChromeExtractor {

    fun extractTitle(root: AccessibilityNodeInfo): String? {

        val results = mutableListOf<String>()

        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null) return

            node.text?.toString()?.let { results.add(it) }
            node.contentDescription?.toString()?.let { results.add(it) }

            for (i in 0 until node.childCount) {
                scan(node.getChild(i))
            }
        }

        scan(root)

        return results.firstOrNull {
            it.length in 5..120 &&
                    !it.contains("Search", true) &&
                    !it.contains("Google", true) &&
                    !it.contains("Address bar", true)
        }
    }
}