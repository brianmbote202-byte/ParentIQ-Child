package com.parentalcontrol.childapp.detector

import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

data class PageMetadata(
    val title: String? = null,
    val visibleText: String? = null,
    val searchQuery: String? = null,
    val confidence: Float = 0f,
    val pageType: String = "unknown",
    val source: String
)

class BrowserMetadataResolver(private val context: Context) {

    // =========================
    // PUBLIC ENTRY
    // =========================
    fun extractFastTitle(
        root: AccessibilityNodeInfo?
    ): String? {

        if (root == null) return null

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var scanned = 0
        val MAX_SCAN = 40

        while (queue.isNotEmpty() && scanned < MAX_SCAN) {

            val node = queue.removeFirst()
            scanned++

            val text = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()

            val candidate = text ?: desc

            if (
                !candidate.isNullOrBlank() &&
                isGoodTitle(candidate)
            ) {

                Log.d(
                    "META_TITLE",
                    "TITLE FOUND = $candidate"
                )

                return candidate
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let {
                    queue.add(it)
                }
            }
        }

        return null
    }

    //smart filter
    private fun isGoodTitle(text: String): Boolean {

        val t = text.lowercase()

        if (text.length !in 6..120) return false

        if (
            t.contains("home") ||
            t.contains("shorts") ||
            t.contains("subscribe") ||
            t.contains("comments") ||
            t.contains("like") ||
            t.contains("share") ||
            t.contains("search")
        ) {
            return false
        }

        return true
    }



    //----------------------collect texts---------
    private fun collectTexts(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>
    ) {
        if (node == null) return

        try {
            node.text?.toString()?.let { out.add(it) }

            node.contentDescription?.toString()?.let { out.add(it) }

            for (i in 0 until node.childCount) {
                collectTexts(node.getChild(i), out)
            }
        } catch (_: Exception) {
            // ignore broken nodes
        }
    }

    //---------------domain extractor--------
    fun extractDomain(url: String?): String? {

        if (url.isNullOrBlank()) return null

        return try {

            val host = android.net.Uri
                .parse(url)
                .host
                ?: return null

            host.lowercase()
                .replace("www.", "")
                .replace("m.", "")

        } catch (_: Exception) {
            null
        }
    }
    //-----------meta data -----------------
    fun extractMetadata(
        root: AccessibilityNodeInfo?,
        packageName: String,
        url: String?
    ): PageMetadata? {

        if (root == null) return null

        val candidates = mutableListOf<String>()

        // 🔥 app-specific extraction
        when {

            packageName.contains("youtube") -> {
                extractYouTube(root, candidates)
            }

            packageName.contains("tiktok") -> {
                extractTikTok(root, candidates)
            }

            packageName.contains("chrome") ||
                    packageName.contains("browser") ||
                    packageName.contains("firefox") ||
                    packageName.contains("opera") ||
                    packageName.contains("edge") -> {

                extractBrowser(root, candidates)
            }

            packageName.contains("instagram") ||
                    packageName.contains("facebook") -> {

                extractSocialInAppBrowser(root, candidates)
            }

            else -> {
                extractGeneric(root, candidates)
            }
        }

        // 🔥 cleanup
        val cleaned = candidates
            .map { clean(it) }
            .distinct()
            .filter { isValidTitle(it) }

        val best = cleaned
            .maxByOrNull { score(it) }
            ?: return null

        val confidence =
            calculateConfidence(best, packageName)

        val pageType =
            detectPageType(best, packageName, url)

        return PageMetadata(
            title = best,
            confidence = confidence,
            source = packageName,
            pageType = pageType
        )
    }

    //-----------detect page type---------
    private fun detectPageType(
        title: String,
        packageName: String,
        url: String?
    ): String {

        val t = title.lowercase()
        val u = url?.lowercase() ?: ""

        return when {

            packageName.contains("youtube") &&
                    u.contains("/watch") -> {
                "youtube_video"
            }

            packageName.contains("youtube") &&
                    u.contains("/shorts") -> {
                "youtube_shorts"
            }

            packageName.contains("youtube") -> {
                "youtube_feed"
            }

            u.contains("/product") ||
                    t.contains("jumia") ||
                    t.contains("amazon") -> {
                "ecommerce_product"
            }

            u.contains("/search") -> {
                "search_result"
            }

            else -> {
                "web_page"
            }
        }
    }


    //---------------------confidence percentage----
    private fun calculateConfidence(text: String, packageName: String): Float {

        var score = 0f
        val t = text.lowercase()

        // 🔥 strong signal: real page titles
        if (text.contains(" - ")) score += 0.3f
        if (text.length in 20..80) score += 0.3f

        // YouTube signal
        if (packageName.contains("youtube")) {
            if (!isYouTubeNoise(t)) score += 0.3f
        }

        // Chrome / browser signal
        if (packageName.contains("chrome")) {
            if (text.any { it.isUpperCase() }) score += 0.2f
        }

        // penalty for UI junk
        if (t.contains("search")) score -= 0.3f
        if (t.contains("google")) score -= 0.2f
        if (t.contains("loading")) score -= 0.4f

        return score.coerceIn(0f, 1f)
    }

    //--------------------noise filter---------
    private fun isNoise(text: String): Boolean {
        val t = text.lowercase()

        return t.contains("views") ||
                t.contains("like") ||
                t.contains("subscribe") ||
                t.contains("shorts") ||
                t.contains("comments") ||
                t.contains("share") ||
                t.contains("home")
    }

    // =========================
    // BROWSER (Chrome etc.)
    // =========================
    private fun extractBrowser(
        root: AccessibilityNodeInfo,
        out: MutableList<String>
    ) {
        scan(root, out)

        // prioritize known address bar / title fields
        findByKeywords(root, out, listOf(
            "url_bar",
            "address",
            "omnibox",
            "search_box",
            "title",
            "tab"
        ))
    }

    // =========================
    // YOUTUBE INTELLIGENCE
    // =========================
    private fun extractYouTube(
        root: AccessibilityNodeInfo,
        out: MutableList<String>
    ) {
        scan(root, out)

        findByKeywords(root, out, listOf(
            "video title",
            "watch",
            "shorts",
            "yt",
            "player",
            "headline"
        ))

        // remove noise
        out.removeAll { isYouTubeNoise(it) }
    }

    // =========================
    // TIKTOK INTELLIGENCE
    // =========================
    private fun extractTikTok(
        root: AccessibilityNodeInfo,
        out: MutableList<String>
    ) {
        scan(root, out)

        findByKeywords(root, out, listOf(
            "video",
            "desc",
            "title",
            "music",
            "sound"
        ))

        out.removeAll { isTikTokNoise(it) }
    }

    // =========================
    // SOCIAL APPS
    // =========================
    private fun extractSocialInAppBrowser(
        root: AccessibilityNodeInfo,
        out: MutableList<String>
    ) {
        scan(root, out)

        findByKeywords(root, out, listOf(
            "title",
            "header",
            "link",
            "open in browser"
        ))
    }

    // =========================
    // GENERIC FALLBACK
    // =========================
    private fun extractGeneric(
        root: AccessibilityNodeInfo,
        out: MutableList<String>
    ) {
        scan(root, out)
    }

    // =========================
    // CORE RECURSIVE SCAN
    // =========================
    private fun scan(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>
    ) {
        if (node == null) return

        node.text?.toString()?.let { out.add(it) }
        node.contentDescription?.toString()?.let { out.add(it) }

        for (i in 0 until node.childCount) {
            scan(node.getChild(i), out)
        }
    }

    // =========================
    // KEYWORD FINDER (IMPORTANT)
    // =========================
    private fun findByKeywords(
        node: AccessibilityNodeInfo?,
        out: MutableList<String>,
        keywords: List<String>
    ) {
        if (node == null) return

        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (keywords.any { text.contains(it) || desc.contains(it) }) {
            node.text?.toString()?.let { out.add(it) }
            node.contentDescription?.toString()?.let { out.add(it) }
        }

        for (i in 0 until node.childCount) {
            findByKeywords(node.getChild(i), out, keywords)
        }
    }

    // =========================
    // CLEANING
    // =========================
    private fun clean(input: String): String {
        return input
            .replace("\n", " ")
            .replace("\t", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    // =========================
    // VALID TITLE CHECK
    // =========================
    private fun isValidTitle(text: String): Boolean {
        val t = text.lowercase()

        return text.length in 4..120 &&
                !t.contains("search") &&
                !t.contains("google") &&
                !t.contains("loading") &&
                !t.contains("http") &&
                !t.contains("www.") &&
                !t.contains("subscribe") &&
                !t.contains("views") &&
                !t.contains("shorts") ||
                text.contains(" - ")
    }

    // =========================
    // SCORING SYSTEM (VERY IMPORTANT)
    // =========================
    private fun score(text: String): Int {
        var s = 0

        if (text.length > 10) s += 2
        if (text.length in 20..80) s += 3
        if (text.contains(" - ")) s += 3
        if (text.any { it.isUpperCase() }) s += 1

        return s
    }

    // =========================
    // NOISE FILTERS
    // =========================
    private fun isYouTubeNoise(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("views") ||
                t.contains("like") ||
                t.contains("subscribe") ||
                t.contains("shorts") ||
                t.contains("comments")
    }

    private fun isTikTokNoise(text: String): Boolean {
        val t = text.lowercase()
        return t.contains("follow") ||
                t.contains("likes") ||
                t.contains("comments") ||
                t.contains("share")
    }
}