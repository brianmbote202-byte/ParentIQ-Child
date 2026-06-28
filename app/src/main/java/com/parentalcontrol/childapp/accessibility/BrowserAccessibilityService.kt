package com.parentalcontrol.childapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.net.Uri
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.parentalcontrol.childapp.detector.BrowserMetadataResolver
import com.parentalcontrol.childapp.service.BrowsingTracker
import com.parentalcontrol.childapp.tracker.NavigationEvent
import com.parentalcontrol.childapp.utils.TitleSanitizer
import com.parentalcontrol.childapp.utils.UrlQueryExtractor

@SuppressLint("AccessibilityService", "AccessibilityPolicy")
class BrowserAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BrowserAccess"
    }

    private lateinit var metadataResolver: BrowserMetadataResolver

    private var browsingTracker: BrowsingTracker? = null

    //-------------------hard  navigation lock----------
    private var lastDispatchedKey: String? = null
    private var lastDispatchTime = 0L

    private val DISPATCH_COOLDOWN = 1500L

    private val supportedApps = setOf(
        "com.android.chrome",
        "org.mozilla.firefox",
        "com.microsoft.emmx",
        "com.sec.android.app.sbrowser",
        "com.opera.browser",
        "com.brave.browser",
        "com.google.android.youtube",
        "com.instagram.android",
        "com.zhiliaoapp.musically",
        "com.facebook.katana",
        "com.twitter.android"
    )

    override fun onServiceConnected() {

        super.onServiceConnected()

        metadataResolver =
            BrowserMetadataResolver(applicationContext)

        try {

            val prefs =
                getSharedPreferences(
                    "child_prefs",
                    MODE_PRIVATE
                )

            val childId =
                prefs.getString(
                    "child_id",
                    null
                ) ?: return

            browsingTracker =
                BrowsingTracker(
                    context = applicationContext,
                    childId = childId,
                    accessibilityService = this
                )

            serviceInfo =
                AccessibilityServiceInfo().apply {

                    eventTypes =
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

                    feedbackType =
                        AccessibilityServiceInfo.FEEDBACK_GENERIC

                    notificationTimeout = 1000

                    flags =
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                }

            Log.d(TAG, "✅ Accessibility connected")

        } catch (e: Exception) {

            Log.e(TAG, "❌ Service error", e)
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {

        try {

            if (event == null) return

            val packageName =
                event.packageName?.toString()
                    ?: return

            if (!supportedApps.contains(packageName)) {
                return
            }

            val root =
                rootInActiveWindow
                    ?: return

            val rawUrl =
                extractBrowserUrl(
                    root,
                    packageName
                ) ?: return

            val cleanUrl = rawUrl.trim()

// 🔥 HARD NAVIGATION KEY
            val dispatchKey = buildDispatchKey(cleanUrl)

// 🔥 STOP SAME NAVIGATION SPAM
            val now = System.currentTimeMillis()

            if (
                dispatchKey == lastDispatchedKey &&
                now - lastDispatchTime < DISPATCH_COOLDOWN &&
                !isBlockedDomainUrl(cleanUrl)
            ) {
                return
            }

            lastDispatchedKey = dispatchKey
            lastDispatchTime = now

            val title =
                metadataResolver.extractFastTitle(root)

            dispatchNavigation(
                url = rawUrl,
                packageName = packageName,
                root = root,
                title = title
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Event error",
                e
            )
        }
    }

    //-----------acess if blocked domain---------
    private fun isBlockedDomainUrl(url: String): Boolean {

        val host = try {
            Uri.parse(url).host ?: return false
        } catch (_: Exception) {
            return false
        }

        val cleanHost = host
            .replace("www.", "")
            .lowercase()

        return listOf(
            "pornhub.com",
            "xbet.com",
            "betika.com"
        ).any {
            cleanHost == it ||
                    cleanHost.endsWith(".$it")
        }
    }

    private fun buildDispatchKey(url: String): String {

        return try {

            val uri = android.net.Uri.parse(url)

            val host =
                uri.host
                    ?.replace("www.", "")
                    ?.replace("m.", "")
                    ?.lowercase()
                    ?: return url

            // 🔥 YouTube video IDs
            val videoId =
                uri.getQueryParameter("v")

            if (!videoId.isNullOrBlank()) {
                return "youtube_watch_$videoId"
            }

            // 🔥 YouTube shorts
            if (url.contains("/shorts/")) {

                val shortsId =
                    uri.pathSegments.lastOrNull()

                if (!shortsId.isNullOrBlank()) {
                    return "youtube_shorts_$shortsId"
                }
            }

            // 🔥 Normal pages
            val path =
                uri.path
                    ?.substringBefore("?")
                    ?.substringBefore("#")
                    ?.removeSuffix("/")
                    ?: ""

            "$host$path"

        } catch (_: Exception) {

            url.lowercase()
        }
    }

    private fun dispatchNavigation(
        url: String,
        packageName: String,
        root: AccessibilityNodeInfo?,
        title: String?
    ) {

        // =========================
        // LEVEL 1: URL INTELLIGENCE
        // =========================

        val uri = Uri.parse(url)

        val isYouTubeSearch =
            url.contains("youtube.com/results") &&
                    url.contains("search_query=")

        val searchQueryFromUrl =
            if (isYouTubeSearch)
                uri.getQueryParameter("search_query")
            else null

        val googleSearchQuery =
            if (url.contains("google.") && url.contains("/search"))
                uri.getQueryParameter("q")
            else null

        val searchQueryFromUrlFinal =
            searchQueryFromUrl ?: googleSearchQuery


        // =========================
        // LEVEL 2: METADATA FIRST (IMPORTANT FIX)
        // =========================

        val metadata =
            metadataResolver.extractMetadata(
                root = root,
                packageName = packageName,
                url = url
            )

        val rawMetadataTitle = metadata?.title
        val visibleText = metadata?.visibleText


        // =========================
        // LEVEL 3: TITLE CLEANING (FIXED ORDER)
        // =========================

        val sanitizedInputTitle = TitleSanitizer.cleanTitle(title)
        val sanitizedMetadataTitle = TitleSanitizer.cleanTitle(rawMetadataTitle)

        val finalTitle = when {
            !sanitizedInputTitle.isNullOrBlank() -> sanitizedInputTitle
            !sanitizedMetadataTitle.isNullOrBlank() -> sanitizedMetadataTitle
            else -> rawMetadataTitle
        }


        // =========================
        // LEVEL 4: SEARCH FALLBACK (IMPORTANT FIX)
        // =========================

        val searchQuery =
            searchQueryFromUrlFinal
                ?: metadata?.searchQuery
                ?: UrlQueryExtractor.extractQueryFromUrl(url)


        // =========================
        // TYPE CLASSIFICATION
        // =========================

        val type = when {

            packageName.contains("youtube") &&
                    url.contains("watch?v=") ->
                "youtube_video"

            packageName.contains("youtube") &&
                    url.contains("/shorts/") ->
                "youtube_shorts"

            packageName.contains("youtube") &&
                    searchQueryFromUrlFinal != null ->
                "youtube_search"

            packageName.contains("instagram") -> "instagram"
            packageName.contains("tiktok") -> "tiktok"
            packageName.contains("facebook") -> "facebook"

            else -> "web"
        }


        // =========================
        // FINAL DISPATCH
        // =========================

        browsingTracker?.onNavigationEvent(
            NavigationEvent(
                url = url,
                domain = "",
                packageName = packageName,
                title = finalTitle,
                videoTitle = finalTitle,
                searchQuery = searchQuery,
                visibleText = visibleText,
                type = type,
                timestamp = System.currentTimeMillis()
            )
        )
    }

    private fun extractBrowserUrl(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String? {

        val candidates = mutableListOf<String>()

        /*fun isValidUrl(text: String): Boolean {
            val t = text.lowercase().trim()

            return t.startsWith("http") ||
                    t.contains("youtube.com/watch") ||
                    t.contains("youtube.com/results") ||
                    t.contains("youtu.be") ||
                    t.contains("google.com/search") ||
                    t.contains(".com") && t.length > 8
        }*/
        fun isValidUrl(text: String): Boolean {

            val t = text.lowercase().trim()

            if (t.startsWith("http")) return true

            if (t.contains(".")) return true

            return false
        }

        fun isJunkText(text: String): Boolean {
            val t = text.lowercase().trim()

            return t in listOf(
                "clear input",
                "search or type web address",
                "tap to search",
                "google search",
                "2 open tabs, tap to switch tabs",
                "web view",
                "youtube video player"
            ) ||
                    t.length < 4 ||                 // ❌ kills "how to e"
                    t.length > 200                  // ❌ garbage UI blocks
        }

        fun scan(node: AccessibilityNodeInfo?) {
            if (node == null) return

            try {
                val viewId = node.viewIdResourceName ?: ""
                val text = node.text?.toString()?.trim() ?: ""

                // ==============================
                // ONLY URL BAR / SEARCH BOX
                // ==============================
                val isUrlField =
                    viewId.contains("url_bar") ||
                            viewId.contains("search_box") ||
                            viewId.contains("omnibox") ||
                            viewId.contains("address")

                if (isUrlField && text.isNotBlank()) {

                    if (!isJunkText(text) && isValidUrl(text)) {
                        candidates.add(text)
                    }
                }

                // ==============================
                // YOUTUBE SPECIAL CASE
                // ==============================
                if (packageName.contains("youtube")) {

                    if (!isJunkText(text)) {

                        if (text.contains("youtube.com") ||
                            text.contains("youtu.be") ||
                            text.contains("watch?v=") ||
                            text.contains("search_query=")
                        ) {
                            candidates.add(text)
                        }
                    }
                }

                // ==============================
                // GOOGLE SEARCH FIX
                // ==============================
                if (text.contains("google.com/search?q=")) {
                    candidates.add(text)
                }

                for (i in 0 until node.childCount) {
                    scan(node.getChild(i))
                }

            } catch (_: Exception) {}
        }

        scan(root)

        return candidates
            .firstOrNull()
            ?.takeIf { it.length > 5 }
    }


    override fun onInterrupt() {

        Log.e(TAG, "⚠ Interrupted")
    }

    override fun onDestroy() {

        browsingTracker?.destroy()

        super.onDestroy()

        Log.e(TAG, "❌ Service destroyed")
    }
}