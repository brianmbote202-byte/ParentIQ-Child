package com.parentalcontrol.childapp.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.*
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.database.*
import com.parentalcontrol.childapp.RuleMonitor
import com.parentalcontrol.childapp.utils.ContentClassifier
import com.parentalcontrol.childapp.utils.ContentClassifier2
import com.parentalcontrol.childapp.utils.UsageTracker
import java.util.Calendar
import com.parentalcontrol.childapp.detector.SearchIntentDetector
import com.parentalcontrol.childapp.overlay.BlockOverlayView
import com.parentalcontrol.childapp.overlay.CountdownOverlayView
import android.net.Uri
import com.google.firebase.database.ServerValue
import com.parentalcontrol.childapp.detector.BrowserMetadataResolver
import com.parentalcontrol.childapp.tracker.NavigationEvent
import com.parentalcontrol.childapp.tracker.PageMetadataResolver
import com.parentalcontrol.childapp.utils.SearchDeduplicator
import com.parentalcontrol.childapp.utils.UrlFilter





data class DomainSession(
    val url: String,
    val domain: String,
    val category: String,
    val start: Long,
    var lastSeen: Long
)

data class YoutubeSession(
    val videoId: String,
    val title: String,
    val thumbnail: String,
    val category: String,
    val start: Long,
    var lastSeen: Long
)

@SuppressLint("RestrictedApi")
class BrowsingTracker(
    private val context: Context,
    private val childId: String,
    private val blockedWebsites: List<String> = emptyList(),
    private val accessibilityService: AccessibilityService? = null
) {

    // 👇 PUT IT HERE (class level fields section)
    private val metadataResolver by lazy {
        BrowserMetadataResolver(context)
    }

    //-------search dedupe------------
    private val searchDeduplicator = SearchDeduplicator()


    private var activeSession: DomainSession? = null
    private var activeYoutube: YoutubeSession? = null
    private val blockedDomains = mutableListOf<String>()
    private var destroyed = false

    private var currentSessionId: String? = null
    private var currentDomain: String? = null
    private var sessionStart = 0L
    private var lastUrlTime = 0L

    //searches
    private val searchHandler = Handler(Looper.getMainLooper())

    private var pendingSearchRunnable: Runnable? = null

    private val SESSION_TIMEOUT = 30000L // 30 sec inactivity ends session
    private val db = FirebaseDatabase.getInstance().reference
    private val blockedReasons = mutableMapOf<String, String>()


    //add a cool down
    private var lastHomeActionTime = 0L

    //APP USAGE TRACKING
    private val appOpenTimestamps = mutableMapOf<String, Long>()


    private val overlayCooldownMap = mutableMapOf<String, Long>()
    private val OVERLAY_COOLDOWN = 2000L
    var appRules = mutableMapOf<String, AppRule>()



    private val overlayManager = OverlayManager(context)


    // ---------------- PENDING QUEUE ----------------
    private val pendingApps = mutableListOf<String>()

    private var currentAppPackage: String? = null

    private val appStartTimes = mutableMapOf<String, Long>()
    private var currentApp: String? = null

    private var lastLoggedQuery = ""
    private var lastLoggedTime = 0L

    //---------add simple url debounce + filter--
    private var lastSavedUrl = ""
    private var lastSavedTime = 0L
    private val URL_COOLDOWN = 15000L

    //----------avoid video trigger anyhow---
    private val lastYoutubeMap = mutableMapOf<String, Long>()

    private var lastWebsiteBlockTime = 0L






    var onAppBlocked: ((String) -> Unit)? = null

    companion object {
        var lastSearchQuery: String? = null
        var lastDomainCategory: String? = null
        var lastSearchEngine: String? = null
        var lastYoutubeVideo: String? = null
        @Volatile
        var blockedScreenShowing = false
    }

    // ---------------- URL RECEIVER ----------------
    private val urlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val url = intent?.getStringExtra("url") ?: return
            val pkg = intent.getStringExtra("package") ?: ""
            onUrlDetected(url, pkg)
        }
    }

    //------write data to analytics_browsing(reference point)-----------
    private val analyticsRef =
        FirebaseDatabase.getInstance()
            .getReference("analytics_browsing")
            .child(childId)

    //---------write data to analytics_uasge(reference point)----------
    private val analyticsUsageRef =
        FirebaseDatabase.getInstance()
            .getReference("analytics_usage")
            .child(childId)

    // ---------------- HANDLER / SESSION CHECKER ----------------
    private val handler = Handler(Looper.getMainLooper())
    private val sessionChecker = object : Runnable {

        override fun run() {

            // Stop completely if tracker has been destroyed
            if (destroyed) {
                return
            }

            val now = System.currentTimeMillis()

            if (
                currentSessionId != null &&
                now - lastUrlTime > SESSION_TIMEOUT
            ) {

                endCurrentSession()
                activeSession = null
            }

            // Schedule next check only if still alive
            if (!destroyed) {
                handler.postDelayed(this, 10000)
            }
        }
    }

    //-----------------url  detector-----------


    // ---------------- INIT ----------------
    init {
        // Register URL receiver

        val filter = IntentFilter("com.parentalcontrol.childapp.URL_DETECTED")
        ContextCompat.registerReceiver(
            context.applicationContext,
            urlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Listen for blocked websites
        listenForBlockedWebsites()
        //delete urls weekly
        cleanupOldVisitedUrls()
        //clean history searches
        cleanupOldSearchHistory()

        // Start session checker
        handler.post(sessionChecker)
        Log.d("BrowsingTracker", "Receiver registered and session checker started")

        //==============check searches-------
        Log.d("FIREBASE_TEST", "childId = $childId")
        Log.d("FIREBASE_TEST", "analyticsRef = ${analyticsRef.path}")

        // ---------------- APP RULES LISTENER WITH PENDING QUEUE ----------------
        AppRuleManager.startRulesListener(childId) { rules ->
            Log.d("RULE_FLOW", "Rules received from Firebase")
            Log.d("RULE_FLOW", "Rules keys: ${rules.keys}")

            appRules.clear()
            appRules.putAll(rules)
            Log.d("RULE_FLOW", "appRules size: ${appRules.size}")
            Log.d("BrowsingTracker", "App rules updated: ${appRules.keys}")

            Log.d("RULE_FLOW", "Using childId: $childId")

            // Process apps that were opened before rules loaded
            val iterator = pendingApps.iterator()
            while (iterator.hasNext()) {
                val pkg = iterator.next()
                Log.d("BrowsingTracker", "Processing queued app: $pkg")
                processAppOpen(pkg) // call internal app open processor
                iterator.remove()
            }
        }
    }

    // ---------------- INTERNAL PROCESSING ----------------
    private fun processAppOpen(appPackage: String) {

        val now = System.currentTimeMillis()
        val key = normalizePackageKey(appPackage)

        Log.d("FLOW", "==============================")
        Log.d("FLOW", "App opened: $appPackage")
        Log.d("FLOW", "Normalized key: $key")
        Log.d("FLOW", "Available rules: ${appRules.keys}")

        val rule = appRules[key]

        // ================================
        // 🔥 ANALYTICS: CLOSE PREVIOUS APP
        // ================================
        currentApp?.let { previousApp ->
            val startTime = appStartTimes[previousApp]

            if (startTime != null) {
                val duration = now - startTime
                updateAppUsage(previousApp, duration)

                appStartTimes.remove(previousApp)
            }
        }

        // ================================
        // 🔥 START NEW APP SESSION
        // ================================
        currentApp = appPackage
        appStartTimes[appPackage] = now

        // ================================
        // RULE CHECK
        // ================================
        val (isBlocked, reason) = isAppBlocked(appPackage, now)

        Log.d("FLOW", "isAppBlocked → $isBlocked, reason → $reason")

        if (rule != null) {
            checkTimeLimitWithCountdown(appPackage, rule, now)
        }

        if (isBlocked) {

            Log.d("FLOW", "APP BLOCKED → show overlay")

            overlayManager.showOverlay(
                appPackage,
                OverlayType.BLOCK,
                reason ?: "Blocked"
            )

            safeGoHome()

            //accessibilityService?.performGlobalAction(
                //AccessibilityService.GLOBAL_ACTION_HOME
            //

            db.child("children")
                .child(childId)
                .child("app_blocked_attempts")
                .push()
                .setValue(
                    mapOf(
                        "appPackage" to appPackage,
                        "packageKey" to key,
                        "reason" to reason,
                        "time" to now,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )

        } else {
            Log.d("FLOW", "APP ALLOWED → remove overlay")
            overlayManager.removeOverlay(appPackage)
        }

        if (!appPackage.contains("youtube")) {
            activeYoutube?.let {
                saveYoutubeSession(it, System.currentTimeMillis())
                activeYoutube = null
            }
        }
    }

    /**
     * Safe wrapper for showing overlay to prevent crashes from WindowManager
     */


    // ---------------- DESTROY ----------------
    fun destroy() {

        destroyed = true

        // Stop all handler work
        handler.removeCallbacksAndMessages(null)

        // Stop search debounce tasks
        pendingSearchRunnable?.let {
            searchHandler.removeCallbacks(it)
        }

        searchHandler.removeCallbacksAndMessages(null)

        pendingSearchRunnable = null

        AppRuleManager.stopRulesListener(childId)

        try {
            context.applicationContext.unregisterReceiver(urlReceiver)
        } catch (_: Exception) {
        }

        activeSession?.let {
            saveDomainSession(
                it,
                System.currentTimeMillis()
            )
            activeSession = null
        }

        activeYoutube?.let {
            saveYoutubeSession(
                it,
                System.currentTimeMillis()
            )
            activeYoutube = null
        }
    }



    // ---------------- URL DETECTED ----------------
    // ---------------- COOLDOWN CHECK ----------------


    fun onUrlDetected(url: String?, appPackage: String, title: String? = null) {

        Log.e("URL_FLOW", "onUrlDetected fired")
        Log.e("URL_FLOW", "URL = $url")
        Log.e("URL_FLOW", "APP = $appPackage")

        if (destroyed || url.isNullOrBlank()) return

        val now = System.currentTimeMillis()


        // ---------------- APP RULE CHECK ----------------
        val (appBlocked, appReason) = isAppBlocked(appPackage, now)
        val rule = appRules[normalizePackageKey(appPackage)]
        if (rule != null) {
            checkTimeLimitWithCountdown(appPackage, rule, now)
        }

        if (appBlocked) {
            Log.d("FLOW", "APP IS BLOCKED → central blocker")

            overlayManager.showOverlay(
                appPackage,
                OverlayType.BLOCK,
                appReason ?: "Blocked by parent"
            )

            //accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            safeGoHome()

            // Log blocked attempt
            analyticsRef
                .child("blocked_attempts")
                .push()
                .setValue(
                    mapOf(
                        "appPackage" to appPackage,
                        "reason" to appReason,
                        "time" to now,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )
            return
        }


        // ---------------- COOLDOWN CHECK ----------------
        //if (now - lastUrlTime < 300) return

        //----------ignore system url-----
        val fixedUrl = UrlFilter.normalize(url)
        if (UrlFilter.isSystemUrl(fixedUrl, appPackage)) {
            Log.d("BrowsingTracker", "Ignored system URL: $fixedUrl")
            return
        }
        if (BuildConfig.DEBUG) {
            Log.d(
                "BrowsingTracker",
                "URL detected: $fixedUrl"
            )
        }
        // ---------------- EXTRACT DOMAIN ----------------
        val domain = extractDomain(fixedUrl) ?: return
        val normalizedDomain = normalizeDomain(domain)

        // ---------------- BLOCKED DOMAIN CHECK ----------------
        val blocked = isDomainBlocked(normalizedDomain)

        if (blocked && isRealWebsite(fixedUrl)) {

            // Prevent repeated triggers
            if (
                normalizedDomain == lastBlocked &&
                now - lastBlockTime < BLOCK_COOLDOWN
            ) {
                return
            }

            // Prevent overlay launch loops


            lastBlocked = normalizedDomain
            lastBlockTime = now

            val reason = getBlockedReason(normalizedDomain)

            // Save blocked attempt
            analyticsRef
                .child("blocked_attempts")
                .push()
                .setValue(
                    mapOf(
                        "type" to "domain",
                        "domain" to normalizedDomain,
                        "url" to fixedUrl,
                        "reason" to reason,
                        "time" to now,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )

            launchBlockedWebsiteActivity(
                normalizedDomain,
                reason
            )



            return
        }

        // ---------------- SESSION AND SEARCH HANDLING ----------------
        val uri = try {
            Uri.parse(fixedUrl)
        } catch (e: Exception) {
            return
        }

        // 🔥 APP-LEVEL SEARCH DETECTION (fallback)
        val detected = SearchIntentDetector.detect(appPackage, url)
        if (detected != null) {
            Log.d("SEARCH_AI", "Detected: $detected")

            val cleanDetected = searchDeduplicator.normalize(detected)

            if (searchDeduplicator.shouldSave(cleanDetected, now)) {
                saveSearchQuery(
                    cleanDetected,
                    appPackage,
                    now,
                    "app_ai"
                )
            }
        }

        // Search engine / query detection
        val searchEngine = detectSearchEngine(uri)
        lastSearchEngine = searchEngine

        val searchQuery = extractSearchQuery(uri)

        Log.e("SEARCH_FLOW", "searchEngine = $searchEngine")
        Log.e("SEARCH_FLOW", "searchQuery = $searchQuery")
        Log.e("SEARCH_FLOW", "fullUrl = $fixedUrl")

        Log.d("SEARCH_DEBUG", "URL = $fixedUrl")
        Log.d("SEARCH_DEBUG", "Extracted query = $searchQuery")
// ---------------- SEARCH DETECTION ----------------

        Log.e("SEARCH_FLOW", "Checking query block")

        if (!searchQuery.isNullOrBlank()) {

            val cleanQuery = Uri.decode(searchQuery)
                .trim()

            // ignore junk searches
            if (
                cleanQuery.length >= 3 &&
                !cleanQuery.equals("Search YouTube", true)
            ) {

                // cancel previous typing event
                pendingSearchRunnable?.let {
                    searchHandler.removeCallbacks(it)
                }

                pendingSearchRunnable = Runnable {

                    Log.e("SEARCH_FLOW", "RUNNABLE EXECUTED")

                    if (!searchDeduplicator.shouldSave(cleanQuery, now)) {
                        return@Runnable
                    }
                    // ignore incomplete searches
                    if (cleanQuery.length < 2) {
                        return@Runnable
                    }

                    lastSearchQuery = cleanQuery

                    Log.d(
                        "SEARCH_SAVE",
                        "Saving final query = $cleanQuery"
                    )

                    Log.e("SEARCH_FLOW", "ENTERED QUERY BLOCK")

                    saveSearchQuery(
                        cleanQuery,
                        appPackage,
                        System.currentTimeMillis(),
                        searchEngine
                    )
                }

// wait until user stops typing
                searchHandler.postDelayed(
                    pendingSearchRunnable!!,
                    2500
                )
            }
        }

        // YouTube detection
        detectYoutubeVideo(uri)?.let { (videoId, title) ->
            saveYoutubeVideo(videoId, title, appPackage, now)
        }

        // Update active session
        val category = classifyDomain(normalizedDomain)
        lastDomainCategory = category
        if (currentDomain == null || currentDomain != normalizedDomain || now - lastUrlTime > SESSION_TIMEOUT) {
            endCurrentSession()
            startNewSession(normalizedDomain, fixedUrl)
        }

        activeSession = DomainSession(
            url = fixedUrl,
            domain = normalizedDomain,
            category = category,
            start = sessionStart,
            lastSeen = now
        )

        if (fixedUrl == lastSavedUrl && now - lastSavedTime < URL_COOLDOWN) return

        //if (fixedUrl.startsWith("https://app://")) return
        if (isJunkUrl(fixedUrl)) return
        if (UrlFilter.isSystemUrl(fixedUrl, appPackage)) return

        /*val normalizedUrl = fixedUrl.substringBefore("?")

        if (normalizedUrl == lastSavedUrl &&
            now - lastSavedTime < URL_COOLDOWN
        ) return*/
        // ---------------- DUPLICATE FILTER ----------------

        // Reuse the already parsed URI
        val uriNormalized = uri

        val normalizedUrl = when {

            // YouTube watch page
            fixedUrl.contains("youtube.com/watch", ignoreCase = true) -> {

                val videoId =
                    uriNormalized.getQueryParameter("v")
                        ?: return

                "youtube_watch_$videoId"
            }

            // YouTube Shorts
            fixedUrl.contains("/shorts/", ignoreCase = true) -> {

                val shortsId =
                    uriNormalized.pathSegments
                        .lastOrNull()
                        ?: return

                "youtube_shorts_$shortsId"
            }

            // Everything else
            else -> {

                fixedUrl
                    .substringBefore("?")
                    .trim()
                    .lowercase()
            }
        }
        // Prevent duplicate spam writes
        if (
            normalizedUrl == lastSavedUrl &&
            now - lastSavedTime < URL_COOLDOWN
        ) {

            Log.d(
                "BrowsingTracker",
                "Skipped duplicate URL: $normalizedUrl"
            )

            return
        }

        // Save visit
        val dateKey = java.text.SimpleDateFormat(
            "yyyy-MM-dd",
            java.util.Locale.getDefault()
        ).format(java.util.Date())

        analyticsRef
            .child("visited_urls")
            .child(dateKey)
            .push()
            .setValue(
                mapOf(
                    "url" to fixedUrl,
                    "domain" to normalizedDomain,
                    "category" to category,
                    "time" to now,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )

        //lastSavedUrl = fixedUrl
        lastSavedUrl = normalizedUrl
        lastSavedTime = now


        // Update session timing AFTER checks
        lastUrlTime = now

    }

    //=================delete url weekly======
    private fun cleanupOldVisitedUrls() {

        val ref = FirebaseDatabase.getInstance()
            .getReference("analytics_browsing")
            .child(childId)
            .child("visited_urls")

        ref.get().addOnSuccessListener { snapshot ->

            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.getDefault()
            )

            val now = System.currentTimeMillis()

            for (daySnapshot in snapshot.children) {

                val dateKey = daySnapshot.key ?: continue

                try {

                    val date = sdf.parse(dateKey) ?: continue

                    val diffDays =
                        (now - date.time) / (1000 * 60 * 60 * 24)

                    // 🔥 DELETE AFTER 7 DAYS
                    if (diffDays > 7) {

                        ref.child(dateKey)
                            .removeValue()

                        Log.d(
                            "BrowsingTracker",
                            "Deleted old URL history: $dateKey"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "BrowsingTracker",
                        "Cleanup error: ${e.message}"
                    )
                }
            }
        }
    }

    //clean old search history
    private fun cleanupOldSearchHistory() {

        val ref = FirebaseDatabase.getInstance()
            .getReference("analytics_browsing")
            .child(childId)
            .child("search_history")

        ref.get().addOnSuccessListener { snapshot ->

            val sdf = java.text.SimpleDateFormat(
                "yyyy-MM-dd",
                java.util.Locale.getDefault()
            )

            val now = System.currentTimeMillis()

            for (daySnapshot in snapshot.children) {

                val dateKey = daySnapshot.key ?: continue

                try {

                    val date = sdf.parse(dateKey) ?: continue

                    val diffDays =
                        (now - date.time) / (1000 * 60 * 60 * 24)

                    // 🔥 DELETE AFTER 7 DAYS
                    if (diffDays > 7) {

                        ref.child(dateKey)
                            .removeValue()

                        Log.d(
                            "BrowsingTracker",
                            "Deleted old search history: $dateKey"
                        )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "BrowsingTracker",
                        "Search cleanup error: ${e.message}"
                    )
                }
            }
        }
    }

    //--cleanerfiltering---
    private fun isJunkUrl(url: String): Boolean {
        return url.contains("app://") ||
                url.contains("about:blank") ||
                url.contains("youtube.com/results?search_query=Search") ||
                url.contains("chrome://") ||
                url.contains("com.android.systemui")
    }


    //-----app usage detection---------
    private fun updateAppUsage(packageName: String, durationMs: Long) {

        if (durationMs < 1000) return

        analyticsUsageRef
            .child("app_usage")
            .child(packageName)
            .child("totalTime")
            .runTransaction(object : Transaction.Handler {

                override fun doTransaction(currentData: MutableData): Transaction.Result {
                    val current = currentData.getValue(Long::class.java) ?: 0L
                    currentData.value = current + durationMs
                    return Transaction.success(currentData)
                }

                override fun onComplete(
                    error: DatabaseError?,
                    committed: Boolean,
                    snapshot: DataSnapshot?
                ) {}
            })
    }

    //-------------end app session---------
    private fun endAppSession(appPackage: String) {
        val startTime = appOpenTimestamps[appPackage] ?: return
        val duration = System.currentTimeMillis() - startTime

        if (duration > 1000) {
            updateAppUsage(appPackage, duration)
        }

        appOpenTimestamps.remove(appPackage)
    }


    // ---------------- START / END SESSION ----------------
    private fun startNewSession(domain: String, url: String) {
        val sessionRef = db.child("children")
            .child(childId)
            .child("browsing")
            .child("sessions")
            .push()

        val sessionId = sessionRef.key ?: return
        currentSessionId = sessionId
        currentDomain = domain
        sessionStart = System.currentTimeMillis()

        val data = mapOf(
            "domain" to domain,
            "url" to url,
            "startTime" to sessionStart,
            "endTime" to null,              // ✅ ADD THIS
            "duration" to 0,                // ✅ ADD THIS
            "active" to true,               // ✅ IMPORTANT FOR UI
            "createdAt" to ServerValue.TIMESTAMP
        )

        sessionRef.setValue(data)

        activeSession = DomainSession(
            url = url,
            domain = domain,
            category = classifyDomain(domain),
            start = sessionStart,
            lastSeen = sessionStart
        )
    }

    private fun endCurrentSession() {
        val sessionId = currentSessionId ?: return
        val endTime = System.currentTimeMillis()
        val duration = endTime - sessionStart

        db.child("children")
            .child(childId)
            .child("browsing")
            .child("sessions")
            .child(sessionId)
            .updateChildren(
                mapOf(
                    "endTime" to endTime,
                    "duration" to duration,
                    "active" to false,                      // ✅ IMPORTANT FIX
                    "updatedAt" to ServerValue.TIMESTAMP    // ✅ ADD THIS
                )
            )

        activeSession?.let { session ->
            if (duration >= 1000) {
                saveDomainSession(session, endTime)
            }
        }

        currentSessionId = null
        currentDomain = null
        activeSession = null
    }

    //notify when a blocked domain is attempted
    private fun logBlockedAttempt(domain: String, url: String, reason: String) {

        val attempt = mapOf(
            "type" to "domain",
            "domain" to domain,
            "url" to url,
            "reason" to reason,
            "createdAt" to ServerValue.TIMESTAMP   // ✅ FIXED
        )

        db.child("children")
            .child(childId)
            .child("browsing")
            .child("blocked_attempts")
            .push()
            .setValue(attempt)
    }

    // ---------------- DOMAIN / URL UTILS ----------------
    private fun extractDomain(url: String): String? {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return null
            host.lowercase().replace("www.", "").replace("m.", "").replace("mobile.", "")
        } catch (e: Exception) {
            null
        }
    }

    private fun normalizeDomain(domain: String): String =
        domain.lowercase().replace("www.", "").replace("m.", "").replace("mobile.", "")

    private fun classifyDomain(domain: String): String = when {
        domain.contains("facebook") || domain.contains("instagram") ||
                domain.contains("twitter") || domain.contains("x.com") ||
                domain.contains("tiktok") -> "social"

        domain.contains("telegram") || domain.contains("whatsapp") -> "messaging"

        domain.contains("youtube") -> "video"

        ContentClassifier.isAdult(domain) -> "adult"
        ContentClassifier.isGambling(domain) -> "gambling"
        ContentClassifier.isGame(domain) -> "games"

        else -> "general"
    }

    private fun detectSearchEngine(uri: Uri): String {
        val host = uri.host?.lowercase() ?: return "unknown"
        return when {
            host.contains("google.") -> "google"
            host.contains("bing.") -> "bing"
            host.contains("duckduckgo.") -> "duckduckgo"
            host.contains("yahoo.") -> "yahoo"
            host.contains("youtube") -> "youtube"
            else -> "unknown"
        }
    }

    //----------universal search extractor------------
    private fun extractSearchQuery(uri: Uri): String? {
        val host = uri.host?.lowercase() ?: return null

        return when {
            host.contains("google.") -> {
                if (uri.path?.contains("/search") == true) {
                    uri.getQueryParameter("q")
                        ?: uri.getQueryParameter("oq")
                        ?: uri.getQueryParameter("query")
                } else null
            }

            host.contains("bing.") || host.contains("duckduckgo.") -> {
                uri.getQueryParameter("q")
            }

            host.contains("yahoo.") -> {
                uri.getQueryParameter("p")
            }

            host.contains("youtube.com") -> {
                uri.getQueryParameter("search_query")
                    ?: uri.getQueryParameter("q")
            }

            host.contains("tiktok.com") -> {
                uri.getQueryParameter("q")
                    ?: uri.getQueryParameter("keyword")
                    ?: uri.getQueryParameter("search")
            } //ADD THIS


            else -> null
        }
    }

    //--------app level  search detection----
    fun detectSearchFromApp(appPackage: String, text: String?): String? {
        if (text.isNullOrBlank()) return null

        val clean = text.lowercase().trim()

        return when {
            appPackage.contains("youtube") && clean.length > 3 -> clean
            appPackage.contains("tiktok") && clean.length > 3 -> clean
            appPackage.contains("instagram") && clean.length > 3 -> clean
            appPackage.contains("facebook") && clean.length > 3 -> clean
            appPackage.contains("chrome") && clean.length > 3 -> clean
            appPackage.contains("googlequicksearchbox") -> clean
            else -> null
        }
    }

    // ---------------- YOUTUBE ----------------
    private fun detectYoutubeVideo(uri: Uri): Pair<String, String>? {
        val host = uri.host ?: return null
        if (!host.contains("youtube") && !host.contains("youtu.be")) return null

        val videoId = uri.getQueryParameter("v")
        val path = uri.path ?: ""
        val shortsId = if (path.contains("/shorts/")) path.substringAfter("/shorts/")
            .substringBefore("/") else null
        val id = videoId ?: shortsId ?: return null
        val title = uri.getQueryParameter("title") ?: "YouTube Video"
        return Pair(id, title)


    }

    //----------search fallback-------
    private fun isLikelySearch(text: String): Boolean {
        val keywords = listOf("search", "find", "looking for", "how to")
        return keywords.any { text.contains(it) }
    }

    private fun saveYoutubeVideo(
        videoId: String,
        title: String,
        pkg: String,
        time: Long
    ) {

        val last = lastYoutubeMap[videoId] ?: 0L

        // 🔥 use time instead of now
        if (time - last < 5000) return

        lastYoutubeMap[videoId] = time

        val risk = ContentClassifier2.urlFlag(title.lowercase()) ?: "safe"

        activeYoutube?.let { saveYoutubeSession(it, time) }

        activeYoutube = YoutubeSession(
            videoId = videoId,
            title = title,
            thumbnail = "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
            category = if (risk == "adult") "adult" else "video",
            start = time,
            lastSeen = time
        )

        lastYoutubeVideo = videoId

        analyticsRef
            .child("youtube_history")
            .push()
            .setValue(
                mapOf(
                    "type" to "youtube",
                    "videoId" to videoId,
                    "title" to title,
                    "thumbnail" to "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                    "package" to pkg,
                    "riskLevel" to risk,
                    "startTime" to time,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
    }

    private fun saveYoutubeSession(session: YoutubeSession, end: Long) {

        val duration = end - session.start
        if (duration < 1000) return

        analyticsRef
            .child("youtube_history")
            .push()
            .setValue(
                mapOf(
                    "type" to "youtube_session",   // ✅ IMPORTANT FIX
                    "videoId" to session.videoId,
                    "title" to session.title,
                    "thumbnail" to session.thumbnail,
                    "category" to session.category,
                    "startTime" to session.start,
                    "endTime" to end,
                    "durationMs" to duration,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
    }

    // ---------------- SEARCH QUERY SAVE ----------------
    // ---------------- SEARCH QUERY SAVE ----------------
    private fun saveSearchQuery(
        query: String,
        pkg: String,
        time: Long,
        engine: String
    ) {
        val decodedQuery = Uri.decode(query)
            .trim()
            .lowercase()

        Log.e("SEARCH_FLOW", "saveSearchQuery CALLED → $decodedQuery")

        // =========================
        // 1. STRONG FILTERING
        // =========================
        if (
            decodedQuery.isBlank() ||
            decodedQuery.length < 3 ||
            decodedQuery in listOf(
                "search",
                "search youtube",
                "listening...",
                "google",
                "http"
            ) ||
            decodedQuery.startsWith("http")
        ) {
            Log.d("SEARCH_FIREBASE", "BLOCKED BY FILTER → $decodedQuery")
            return
        }

        // =========================
        // 2. STRONG DEDUPLICATION
        // =========================
        val isDuplicate =
            decodedQuery == lastLoggedQuery &&
                    (time - lastLoggedTime) < 15000  // 15s cooldown

        if (isDuplicate) {
            Log.d("SEARCH_FIREBASE", "BLOCKED DUPLICATE → $decodedQuery")
            return
        }

        lastLoggedQuery = decodedQuery
        lastLoggedTime = time

        // =========================
        // 3. RISK CLASSIFICATION
        // =========================
        val risk =
            ContentClassifier2.urlFlag(decodedQuery) ?: "safe"

        val dateKey = getTodayKey()

        // =========================
        // 4. FIREBASE WRITE
        // =========================
        analyticsRef
            .child("search_history")
            .child(dateKey)
            .push()
            .setValue(
                mapOf(
                    "query" to decodedQuery,
                    "package" to pkg,
                    "engine" to engine,
                    "riskLevel" to risk,
                    "time" to time,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
            .addOnSuccessListener {
                Log.d("SEARCH_FIREBASE", "SUCCESS → $decodedQuery")
            }
            .addOnFailureListener { e ->
                Log.e("SEARCH_FIREBASE", "FAILED → ${e.message}")
            }
    }
    // ---------------- DOMAIN SESSION SAVE ----------------
    private fun saveDomainSession(session: DomainSession, end: Long) {

        val duration = end - session.start
        if (duration < 1000) return

        analyticsRef
            .child("sessions")
            .child(getTodayKey())
            .push()
            .setValue(
                mapOf(
                    "type" to "domain_session",
                    "url" to session.url,
                    "domain" to session.domain,
                    "category" to session.category,
                    "startTime" to session.start,
                    "endTime" to end,
                    "durationMs" to duration,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
    }
    //--------getTodayKey----
    private fun getTodayKey(): String {
        val format = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return format.format(java.util.Date())
    }

    // ---------------- BLOCKED DOMAINS ----------------
    private fun listenForBlockedWebsites() {
        db.child("blocked_websites")
            .child(childId)
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    blockedDomains.clear()
                    blockedReasons.clear()

                    for (child in snapshot.children) {
                        val domain = child.child("domain").getValue(String::class.java)
                        val reason = child.child("reason").getValue(String::class.java)

                        if (!domain.isNullOrEmpty()) {
                            val normalized = normalizeDomain(domain)
                            blockedDomains.add(normalized)
                            blockedReasons[normalized] = reason ?: "Blocked by Parent"
                        }
                    }

                    Log.d("BrowsingTracker", "Blocked domains updated: $blockedDomains")
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("BrowsingTracker", "Failed to load blocked domains")
                }
            })
    }

    private fun isDomainBlocked(domain: String): Boolean {
        return blockedDomains.any { blocked ->
            domain == blocked || domain.endsWith(".$blocked")
        }
    }

    //check if its a real domain before blocking
    private fun isRealWebsite(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return false

            // Ignore Google searches
            //if (host.contains("google.") && url.contains("/search")) return false

            // Ignore blank pages
            if (url.contains("about:blank")) return false

            // Ignore browser new tab pages
            if (url.contains("chrome://")) return false

            // Only block real domains with dots
            host.contains(".")
        } catch (e: Exception) {
            false
        }
    }

    //-----navigaion-------
    fun onNavigationEvent(event: NavigationEvent) {

        Log.d(
            "NAV_EVENT",
            """
        TYPE=${event.type}
        URL=${event.url}
        TITLE=${event.title}
        SEARCH=${event.searchQuery}
        TEXT=${event.visibleText}
        """.trimIndent()
        )

        // --------------------------------
        // SAVE SEARCHES FROM ACCESSIBILITY
        // --------------------------------

        event.searchQuery?.let { query ->

            if (query.length >= 2) {

                saveSearchQuery(
                    query = query,
                    pkg = event.packageName,
                    time = event.timestamp,
                    engine = event.type
                )
            }
        }

        // --------------------------------
        // SAVE VIDEO TITLES
        // --------------------------------

        if (
            event.type.contains("youtube") ||
            event.type.contains("tiktok") ||
            event.type.contains("instagram")
        ) {

            analyticsRef
                .child("media_history")
                .push()
                .setValue(
                    mapOf(
                        "url" to event.url,
                        "title" to event.videoTitle,
                        "package" to event.packageName,
                        "type" to event.type,
                        "visibleText" to event.visibleText,
                        "time" to event.timestamp,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )
        }

        // --------------------------------
        // CONTINUE NORMAL URL FLOW
        // --------------------------------

        onUrlDetected(
            event.url,
            event.packageName,
            event.title
        )
    }


    private fun getBlockedReason(domain: String): String {
        return blockedReasons[domain] ?: "Blocked by Parent"
    }

    private var lastBlocked = ""
    private var lastBlockTime = 0L
    private val BLOCK_COOLDOWN = 5000L


    private fun logBlockedAttempt(domain: String, url: String) {
        analyticsRef
            .child("blocked_attempts")
            .push()
            .setValue(
                mapOf(
                    "domain" to domain,
                    "url" to url,
                    "searchQuery" to lastSearchQuery,
                    "time" to System.currentTimeMillis(),
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
    }
    //----------------------show blocked screen-----
    private fun showBlockedWebsiteScreen(
        domain: String,
        reason: String
    ) {

        val now = System.currentTimeMillis()

        if (now - lastWebsiteBlockTime < 5000) {
            return
        }

        lastWebsiteBlockTime = now

        overlayManager.showOverlay(
            domain,
            OverlayType.BLOCK,
            "Website blocked\n$domain\n$reason"
        )

        Handler(Looper.getMainLooper()).postDelayed({
            safeGoHome()
        }, 500)
    }

    private fun isAppBlocked(appPackage: String, now: Long): Pair<Boolean, String?> {

        val key = normalizePackageKey(appPackage)
        val rule = appRules[key] ?: return false to null

        Log.d("BLOCK_CHECK", "Checking app: $appPackage")
        Log.d("BLOCK_CHECK", "Rule: $rule")

        // Always blocked by parent
        if (rule.blocked) {
            Log.d("BLOCK_CHECK", "Blocked by parent")
            return true to "Blocked by parent"
        }

        val cal = Calendar.getInstance()
        cal.timeInMillis = now

        val currentMinutes =
            cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val fromMinutes =
            rule.allowed_from_hour * 60 + rule.allowed_from_minute

        val toMinutes =
            rule.allowed_to_hour * 60 + rule.allowed_to_minute

        Log.d("BLOCK_CHECK", "Current: $currentMinutes From: $fromMinutes To: $toMinutes")

        // Handle overnight schedules
        val insideAllowedTime = if (fromMinutes <= toMinutes) {
            currentMinutes in fromMinutes..toMinutes
        } else {
            currentMinutes >= fromMinutes || currentMinutes <= toMinutes
        }

        if (!insideAllowedTime) {
            Log.d("BLOCK_CHECK", "Outside allowed time")
            return true to "Outside allowed time"
        }

        // Daily limit
        val usedMinutes = UsageTracker.getUsageForApp(appPackage, context)
        if (rule.block_after_limit && rule.daily_limit > 0 && usedMinutes >= rule.daily_limit) {
            Log.d("BLOCK_CHECK", "Daily limit reached")
            return true to "Daily limit reached"
        }

        // After 9 PM
        if (rule.block_after_9pm && cal.get(Calendar.HOUR_OF_DAY) >= 21) {
            Log.d("BLOCK_CHECK", "Blocked after 9PM")
            return true to "Blocked after 9 PM"
        }

        Log.d("BLOCK_CHECK", "App allowed")
        return false to null
    }

    //countdown logic
    private fun checkTimeLimitWithCountdown(appPackage: String, rule: AppRule, now: Long) {
        val key = normalizePackageKey(appPackage)
        val cal = Calendar.getInstance().apply { timeInMillis = now }

        val currentMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val toMinutes = rule.allowed_to_hour * 60 + rule.allowed_to_minute
        val totalRemainingSeconds = (toMinutes - currentMinutes) * 60 - cal.get(Calendar.SECOND)

        when {
            // ✅ Countdown: less than 60 seconds remaining
            totalRemainingSeconds in 1..60 -> {
                overlayManager.showOverlay(
                    appPackage,
                    OverlayType.COUNTDOWN,
                    "Time almost up",
                    totalRemainingSeconds
                )
            }

            // ⏰ Time finished → block immediately
            totalRemainingSeconds <= 0 -> {
                overlayManager.showOverlay(
                    appPackage,
                    OverlayType.BLOCK,
                    "Time limit reached"
                )

                //accessibilityService?.performGlobalAction(
                   // AccessibilityService.GLOBAL_ACTION_HOME
               // )
                safeGoHome()
            }
            // Otherwise → still allowed, do nothing
        }
    }

    //nomalize app package
    private fun normalizePackageKey(pkg: String): String {
        return pkg.replace(".", "_")
    }

    //------call overlay------
    private fun launchBlockedWebsiteActivity(
        domain: String,
        reason: String
    ) {



        /*val intent = Intent(
            context,
            BlockOverlayActivity::class.java
        ).apply {

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            putExtra("domain", domain)
            putExtra("reason", reason)
        }*/
        val intent = Intent(
            context,
            BlockOverlayActivity::class.java
        ).apply {

            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )

            putExtra("domain", domain)
            putExtra("reason", reason)
        }

        context.startActivity(intent)
    }

    //----------go home------
    private fun safeGoHome() {

        val now = System.currentTimeMillis()

        if (now - lastHomeActionTime < 3000) {
            return
        }

        lastHomeActionTime = now

        Handler(Looper.getMainLooper()).post {

            try {

                accessibilityService?.performGlobalAction(
                    AccessibilityService.GLOBAL_ACTION_HOME
                )

            } catch (_: Exception) {
            }
        }
    }

    //check the overlay functionality


    // check app open
    fun checkAppOpen(appPackage: String) {
        val key = normalizePackageKey(appPackage)
        val now = System.currentTimeMillis()
        val (appBlocked, appReason) = isAppBlocked(appPackage, now)
        val rule = appRules[key]

        // WAIT UNTIL ALLOWED → Countdown
        if (appReason == "WAIT_UNTIL_ALLOWED" && rule != null) {
            val nowCal = Calendar.getInstance()
            val nowMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            val allowedMinutes = rule.allowed_from_hour * 60 + rule.allowed_from_minute
            val totalRemainingSeconds =
                (allowedMinutes - nowMinutes) * 60 - nowCal.get(Calendar.SECOND)

            if (totalRemainingSeconds > 0) {

                // 🔥 SEND ALERT
                //ruleMonitor.checkViolation("Tried opening $appPackage before allowed time")
                overlayManager.showOverlay(
                    appPackage,
                    OverlayType.COUNTDOWN,
                    "Available soon",
                    totalRemainingSeconds
                )

                //accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                safeGoHome()
                return
            }
        }

        currentAppPackage = appPackage
        appOpenTimestamps[appPackage] = System.currentTimeMillis()

        // BLOCKED
        if (appBlocked) {

            if (!overlayManager.isOverlayShowing(appPackage)) {

                overlayManager.showOverlay(
                    appPackage,
                    OverlayType.BLOCK,
                    appReason ?: "Blocked"
                )
            }
            //accessibilityService?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            safeGoHome()
            return
        }

        // OPTIONAL: track allowed usage
        //ruleMonitor.checkViolation("Opened app: $appPackage")

        // ALLOWED → remove overlay
        overlayManager.removeOverlay(appPackage)
    }



}