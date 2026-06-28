package com.parentalcontrol.childapp.tracker

import android.view.accessibility.AccessibilityNodeInfo

object MediaExtractor {

    fun extractTitle(
        packageName: String,
        root: AccessibilityNodeInfo,
        url: String?
    ): String? {

        return when {
            packageName.contains("youtube") ->
                extractYouTube(root)

            packageName.contains("tiktok") ->
                extractTikTok(root)

            else -> null
        }
    }

    private fun extractYouTube(root: AccessibilityNodeInfo): String? {

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
            it.length in 10..120 &&
                    !it.contains("YouTube", true)
        }
    }

    private fun extractTikTok(root: AccessibilityNodeInfo): String? {

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
            it.length in 10..200 &&
                    !it.contains("Follow", true) &&
                    !it.contains("Like", true) &&
                    !it.contains("Comment", true)
        }
    }
}