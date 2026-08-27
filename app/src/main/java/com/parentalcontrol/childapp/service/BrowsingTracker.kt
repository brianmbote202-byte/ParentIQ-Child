package com.parentalcontrol.childapp.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.MutableData
import com.google.firebase.database.ServerValue
import com.google.firebase.database.Transaction
import com.google.firebase.database.ValueEventListener

import com.parentalcontrol.childapp.detector.BrowserMetadataResolver
import com.parentalcontrol.childapp.detector.SearchIntentDetector
import com.parentalcontrol.childapp.tracker.NavigationEvent
import com.parentalcontrol.childapp.utils.ContentClassifier
import com.parentalcontrol.childapp.utils.ContentClassifier2
import com.parentalcontrol.childapp.utils.SearchDeduplicator
import com.parentalcontrol.childapp.utils.UsageTracker
import com.parentalcontrol.childapp.utils.UrlFilter

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale


// ============================================================
// DOMAIN SESSION
// ============================================================

data class DomainSession(
    val url: String,
    val domain: String,
    val category: String,
    val start: Long,
    var lastSeen: Long
)


// ============================================================
// YOUTUBE SESSION
// ============================================================

data class YoutubeSession(
    val videoId: String,
    val title: String,
    val thumbnail: String,
    val category: String,
    val start: Long,
    var lastSeen: Long
)


// ============================================================
// BROWSING TRACKER
// ============================================================

@SuppressLint("RestrictedApi")
class BrowsingTracker(
    private val context: Context,
    private val childId: String,
    private val blockedWebsites: List<String> = emptyList(),
    private val accessibilityService: AccessibilityService? = null
) {

    companion object {

        private const val TAG = "BrowsingTracker"

        private const val SESSION_TIMEOUT = 30_000L
        private const val URL_COOLDOWN = 15_000L
        private const val OVERLAY_COOLDOWN = 2_000L
        private const val BLOCK_COOLDOWN = 5_000L

        private const val SEARCH_COOLDOWN = 15_000L

        private const val HOME_COOLDOWN = 3_000L

        private const val WEBSITE_HOME_DELAY = 500L

        private const val SEARCH_ALERT_COOLDOWN =
            60_000L

        @Volatile
        var blockedScreenShowing = false

        var lastSearchQuery: String? = null
        var lastDomainCategory: String? = null
        var lastSearchEngine: String? = null
        var lastYoutubeVideo: String? = null
    }


    // ========================================================
    // FIREBASE
    // ========================================================

    private val db =
        FirebaseDatabase
            .getInstance()
            .reference

    private val analyticsRef =
        FirebaseDatabase
            .getInstance()
            .getReference("analytics_browsing")
            .child(childId)

    private val analyticsUsageRef =
        FirebaseDatabase
            .getInstance()
            .getReference("analytics_usage")
            .child(childId)


    // ========================================================
    // HELPERS
    // ========================================================

    private val metadataResolver by lazy {
        BrowserMetadataResolver(context)
    }

    private val searchDeduplicator =
        SearchDeduplicator()


    // ========================================================
    // URL / DOMAIN STATE
    // ========================================================

    private var activeSession: DomainSession? = null
    private var activeYoutube: YoutubeSession? = null

    // ========================================================
// SEARCH ALERT DEDUPLICATION
// ========================================================

    private var lastSearchAlertKey = ""

    private var lastSearchAlertTime = 0L



    private val blockedDomains =
        mutableListOf<String>()

    private val blockedReasons =
        mutableMapOf<String, String>()


    // ========================================================
    // SESSION STATE
    // ========================================================

    private var currentSessionId: String? = null
    private var currentDomain: String? = null

    private var sessionStart = 0L
    private var lastUrlTime = 0L


    // ========================================================
    // SEARCH STATE
    // ========================================================

    private val searchHandler =
        Handler(Looper.getMainLooper())

    private var pendingSearchRunnable: Runnable? = null

    private var lastLoggedSearchKey = ""
    private var lastLoggedTime = 0L


    // ========================================================
    // URL DEDUPLICATION
    // ========================================================

    private var lastSavedUrl = ""
    private var lastSavedTime = 0L


    // ========================================================
    // YOUTUBE
    // ========================================================

    private val lastYoutubeMap =
        mutableMapOf<String, Long>()


    // ========================================================
    // APP USAGE
    // ========================================================

    private val appOpenTimestamps =
        mutableMapOf<String, Long>()

    private val appStartTimes =
        mutableMapOf<String, Long>()

    private var currentApp: String? = null

    private var currentAppPackage: String? = null


    // ========================================================
    // APP RULES
    // ========================================================

    var appRules =
        mutableMapOf<String, AppRule>()


    // ========================================================
    // OVERLAY MANAGER
    // ========================================================

    private val overlayManager =
        OverlayManager(context)


    // ========================================================
    // OVERLAY COOLDOWN
    // ========================================================

    private val overlayCooldownMap =
        mutableMapOf<String, Long>()


    // ========================================================
    // APP BLOCK STATE
    // ========================================================

    private var lastWebsiteBlockTime = 0L

    private var lastBlocked = ""
    private var lastBlockTime = 0L


    // ========================================================
    // HOME ACTION COOLDOWN
    // ========================================================

    private var lastHomeActionTime = 0L


    // ========================================================
    // PENDING APP QUEUE
    // ========================================================

    private val pendingApps =
        mutableListOf<String>()


    // ========================================================
    // DESTROYED
    // ========================================================

    private var destroyed = false


    // ========================================================
    // CALLBACK
    // ========================================================

    var onAppBlocked:
            ((String) -> Unit)? = null


    // ========================================================
    // URL RECEIVER
    // ========================================================

    private val urlReceiver =
        object : BroadcastReceiver() {

            override fun onReceive(
                context: Context?,
                intent: Intent?
            ) {

                if (destroyed) return

                val url =
                    intent?.getStringExtra("url")
                        ?: return

                val pkg =
                    intent.getStringExtra("package")
                        ?: ""

                onUrlDetected(
                    url = url,
                    appPackage = pkg
                )
            }
        }


    // ========================================================
    // SESSION CHECKER
    // ========================================================

    private val handler =
        Handler(Looper.getMainLooper())

    private val sessionChecker =
        object : Runnable {

            override fun run() {

                if (destroyed) {
                    return
                }

                val now =
                    System.currentTimeMillis()

                if (
                    currentSessionId != null &&
                    now - lastUrlTime > SESSION_TIMEOUT
                ) {

                    Log.d(
                        TAG,
                        "Session timeout reached"
                    )

                    endCurrentSession()

                    activeSession = null
                }

                if (!destroyed) {

                    handler.postDelayed(
                        this,
                        10_000L
                    )
                }
            }
        }


    // ========================================================
    // INIT
    // ========================================================

    init {

        val filter =
            IntentFilter(
                "com.parentalcontrol.childapp.URL_DETECTED"
            )

        ContextCompat.registerReceiver(
            context.applicationContext,
            urlReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        listenForBlockedWebsites()

        cleanupOldVisitedUrls()

        cleanupOldSearchHistory()

        handler.post(sessionChecker)

        Log.d(
            TAG,
            "BrowsingTracker initialized"
        )

        Log.d(
            TAG,
            "childId = $childId"
        )

        Log.d(
            TAG,
            "analyticsRef = ${analyticsRef.path}"
        )


        // ====================================================
        // APP RULE LISTENER
        // ====================================================

        AppRuleManager.startRulesListener(
            childId
        ) { rules ->

            if (destroyed) {
                return@startRulesListener
            }

            Log.d(
                "RULE_FLOW",
                "Rules received from Firebase"
            )

            Log.d(
                "RULE_FLOW",
                "Rules keys = ${rules.keys}"
            )

            appRules.clear()

            appRules.putAll(
                rules
            )

            Log.d(
                "RULE_FLOW",
                "Total rules = ${appRules.size}"
            )

            // -----------------------------------------------
            // Process apps that opened before Firebase rules
            // loaded.
            // -----------------------------------------------

            val queued =
                pendingApps.toList()

            pendingApps.clear()

            queued.forEach { pkg ->

                if (!destroyed) {

                    Log.d(
                        "RULE_FLOW",
                        "Processing queued app = $pkg"
                    )

                    processAppOpen(
                        pkg
                    )
                }
            }
        }
    }


    // ========================================================
    // APP OPEN
    // ========================================================

    private fun processAppOpen(
        appPackage: String
    ) {

        if (destroyed) return

        if (appPackage.isBlank()) return

        val now =
            System.currentTimeMillis()

        Log.d(
            "FLOW",
            "=============================="
        )

        Log.d(
            "FLOW",
            "APP OPEN = $appPackage"
        )

        val key =
            normalizePackageKey(
                appPackage
            )

        Log.d(
            "FLOW",
            "RULE KEY = $key"
        )

        Log.d(
            "FLOW",
            "AVAILABLE RULES = ${appRules.keys}"
        )


        // ====================================================
        // CLOSE PREVIOUS APP SESSION
        // ====================================================

        currentApp?.let { previousApp ->

            val startTime =
                appStartTimes[previousApp]

            if (startTime != null) {

                val duration =
                    now - startTime

                updateAppUsage(
                    previousApp,
                    duration
                )

                appStartTimes.remove(
                    previousApp
                )
            }
        }


        // ====================================================
        // START NEW APP SESSION
        // ====================================================

        currentApp =
            appPackage

        currentAppPackage =
            appPackage

        appStartTimes[
            appPackage
        ] = now

        appOpenTimestamps[
            appPackage
        ] = now


        // ====================================================
        // APPLY RULE
        // ====================================================

        applyAppRule(
            appPackage = appPackage,
            now = now
        )


        // ====================================================
        // YOUTUBE SESSION CLEANUP
        // ====================================================

        if (
            !appPackage.contains(
                "youtube",
                ignoreCase = true
            )
        ) {

            activeYoutube?.let {

                saveYoutubeSession(
                    it,
                    System.currentTimeMillis()
                )

                activeYoutube = null
            }
        }
    }


    // ========================================================
    // CENTRAL APP RULE ENGINE
    // ========================================================

    private fun applyAppRule(
        appPackage: String,
        now: Long
    ) {

        val key =
            normalizePackageKey(
                appPackage
            )

        val rule =
            appRules[key]


        // ====================================================
        // NO RULE
        // ====================================================

        if (rule == null) {

            Log.d(
                "BLOCK_CHECK",
                "No rule for $appPackage → ALLOWED"
            )

            overlayManager.removeOverlay(
                appPackage
            )

            return
        }


        Log.d(
            "BLOCK_CHECK",
            "Rule for $appPackage = $rule"
        )


        // ====================================================
        // PARENT MANUAL BLOCK
        // ====================================================

        if (rule.blocked) {

            showAppBlock(
                appPackage,
                "Blocked by parent"
            )

            return
        }


        // ====================================================
        // CALCULATE SCHEDULE
        // ====================================================

        val schedule =
            evaluateSchedule(
                rule,
                now
            )


        when (schedule.state) {

            AppScheduleState.WAITING_FOR_START -> {

                Log.d(
                    "BLOCK_CHECK",
                    "App is outside allowed START window"
                )

                showAppCountdown(
                    appPackage = appPackage,
                    seconds = schedule.remainingSeconds,
                    message = "Available soon"
                )

                safeGoHome()

                return
            }


            AppScheduleState.ALLOWED -> {

                Log.d(
                    "BLOCK_CHECK",
                    "App is inside allowed time"
                )
            }


            AppScheduleState.FINISHED -> {

                Log.d(
                    "BLOCK_CHECK",
                    "App allowed time finished"
                )

                showAppBlock(
                    appPackage,
                    "Time limit reached"
                )

                return
            }
        }


        // ====================================================
        // DAILY LIMIT
        // ====================================================

        if (
            rule.block_after_limit &&
            rule.daily_limit > 0
        ) {

            val used =
                UsageTracker.getUsageForApp(
                    appPackage,
                    context
                )

            Log.d(
                "BLOCK_CHECK",
                "Usage for $appPackage = $used"
            )

            if (
                used >= rule.daily_limit
            ) {

                showAppBlock(
                    appPackage,
                    "Daily limit reached"
                )

                return
            }
        }


        // ====================================================
        // AFTER 9 PM
        // ====================================================

        if (
            rule.block_after_9pm
        ) {

            val cal =
                Calendar.getInstance()

            cal.timeInMillis =
                now

            if (
                cal.get(
                    Calendar.HOUR_OF_DAY
                ) >= 21
            ) {

                showAppBlock(
                    appPackage,
                    "Blocked after 9 PM"
                )

                return
            }
        }


        // ====================================================
        // LAST 60 SECONDS OF ALLOWED WINDOW
        // ====================================================

        if (
            schedule.remainingSeconds in 1..60
        ) {

            showAppCountdown(
                appPackage = appPackage,
                seconds = schedule.remainingSeconds,
                message = "Time almost up"
            )

            return
        }


        // ====================================================
        // EVERYTHING ALLOWED
        // ====================================================

        Log.d(
            "BLOCK_CHECK",
            "APP ALLOWED → $appPackage"
        )

        overlayManager.removeOverlay(
            appPackage
        )
    }


    // ========================================================
    // APP BLOCK
    // ========================================================

    private fun showAppBlock(
        appPackage: String,
        reason: String
    ) {

        if (destroyed) return

        Log.d(
            "OVERLAY_FLOW",
            "BLOCK → $appPackage → $reason"
        )

        overlayManager.showOverlay(
            appPackage = appPackage,
            type = OverlayType.BLOCK,
            message = reason
        )

        onAppBlocked?.invoke(
            appPackage
        )

        safeGoHome()

        logAppBlockedAttempt(
            appPackage,
            reason
        )
    }


    // ========================================================
    // APP COUNTDOWN
    // ========================================================

    private fun showAppCountdown(
        appPackage: String,
        seconds: Int,
        message: String
    ) {

        if (destroyed) return

        val safeSeconds =
            seconds.coerceAtLeast(1)

        Log.d(
            "OVERLAY_FLOW",
            "COUNTDOWN → $appPackage → $safeSeconds sec"
        )

        overlayManager.showOverlay(
            appPackage = appPackage,
            type = OverlayType.COUNTDOWN,
            message = message,
            countdownSeconds = safeSeconds
        )
    }


    // ========================================================
    // SCHEDULE RESULT
    // ========================================================

    private enum class AppScheduleState {

        WAITING_FOR_START,

        ALLOWED,

        FINISHED
    }


    private data class ScheduleResult(
        val state: AppScheduleState,
        val remainingSeconds: Int
    )


    // ========================================================
    // SCHEDULE EVALUATION
    // ========================================================

    private fun evaluateSchedule(
        rule: AppRule,
        now: Long
    ): ScheduleResult {

        val calendar =
            Calendar.getInstance()

        calendar.timeInMillis =
            now

        val currentMinutes =
            calendar.get(
                Calendar.HOUR_OF_DAY
            ) * 60 +
                    calendar.get(
                        Calendar.MINUTE
                    )

        val currentSeconds =
            calendar.get(
                Calendar.SECOND
            )

        val fromMinutes =
            rule.allowed_from_hour * 60 +
                    rule.allowed_from_minute

        val toMinutes =
            rule.allowed_to_hour * 60 +
                    rule.allowed_to_minute


        // ====================================================
        // SAME-DAY WINDOW
        // ====================================================

        if (fromMinutes <= toMinutes) {

            // Before start
            if (
                currentMinutes < fromMinutes
            ) {

                val seconds =
                    (
                            (fromMinutes - currentMinutes) * 60
                                    - currentSeconds
                            )
                        .coerceAtLeast(1)

                return ScheduleResult(
                    state =
                        AppScheduleState.WAITING_FOR_START,
                    remainingSeconds =
                        seconds
                )
            }


            // After end
            if (
                currentMinutes > toMinutes
            ) {

                return ScheduleResult(
                    state =
                        AppScheduleState.FINISHED,
                    remainingSeconds = 0
                )
            }


            // Exactly at end minute
            if (
                currentMinutes == toMinutes
            ) {

                val secondsUntilEnd =
                    60 - currentSeconds

                if (
                    secondsUntilEnd <= 0
                ) {

                    return ScheduleResult(
                        state =
                            AppScheduleState.FINISHED,
                        remainingSeconds = 0
                    )
                }

                return ScheduleResult(
                    state =
                        AppScheduleState.ALLOWED,
                    remainingSeconds =
                        secondsUntilEnd
                )
            }


            // Inside window
            val secondsUntilEnd =
                (
                        (toMinutes - currentMinutes) * 60
                                - currentSeconds
                        )
                    .coerceAtLeast(0)

            return ScheduleResult(
                state =
                    AppScheduleState.ALLOWED,
                remainingSeconds =
                    secondsUntilEnd
            )
        }


        // ====================================================
        // OVERNIGHT WINDOW
        //
        // Example:
        //
        // from = 21:00
        // to   = 06:00
        //
        // Allowed:
        // 21:00 → 23:59
        // 00:00 → 06:00
        // ====================================================

        val insideOvernight =
            currentMinutes >= fromMinutes ||
                    currentMinutes <= toMinutes


        if (insideOvernight) {

            val secondsUntilEnd: Int

            if (
                currentMinutes <= toMinutes
            ) {

                // After midnight
                secondsUntilEnd =
                    (
                            (toMinutes - currentMinutes) * 60
                                    - currentSeconds
                            )
                        .coerceAtLeast(0)

            } else {

                // Before midnight
                secondsUntilEnd =
                    (
                            (
                                    (24 * 60 - currentMinutes) +
                                            toMinutes
                                    ) * 60 -
                                    currentSeconds
                            )
                        .coerceAtLeast(0)
            }


            if (
                secondsUntilEnd <= 0
            ) {

                return ScheduleResult(
                    state =
                        AppScheduleState.FINISHED,
                    remainingSeconds = 0
                )
            }


            return ScheduleResult(
                state =
                    AppScheduleState.ALLOWED,
                remainingSeconds =
                    secondsUntilEnd
            )
        }


        // ====================================================
        // OUTSIDE OVERNIGHT WINDOW
        //
        // We are between the end and next day's start.
        // ====================================================

        val secondsUntilNextStart =
            (
                    (
                            (24 * 60 - currentMinutes) +
                                    fromMinutes
                            ) * 60 -
                            currentSeconds
                    )
                .coerceAtLeast(1)

        return ScheduleResult(
            state =
                AppScheduleState.WAITING_FOR_START,
            remainingSeconds =
                secondsUntilNextStart
        )
    }


    // ========================================================
    // APP BLOCKED ATTEMPT LOG
    // ========================================================

    private fun logAppBlockedAttempt(
        appPackage: String,
        reason: String
    ) {

        val now =
            System.currentTimeMillis()

        val key =
            normalizePackageKey(
                appPackage
            )

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
    }


    // ========================================================
    // APP BLOCK CHECK
    //
    // Kept for compatibility with the rest of your class.
    // ========================================================

    private fun isAppBlocked(
        appPackage: String,
        now: Long
    ): Pair<Boolean, String?> {

        val key =
            normalizePackageKey(
                appPackage
            )

        val rule =
            appRules[key]
                ?: return false to null


        // Parent block
        if (rule.blocked) {

            return true to
                    "Blocked by parent"
        }


        val schedule =
            evaluateSchedule(
                rule,
                now
            )


        when (schedule.state) {

            AppScheduleState.WAITING_FOR_START -> {

                return true to
                        "WAIT_UNTIL_ALLOWED"
            }


            AppScheduleState.FINISHED -> {

                return true to
                        "Time limit reached"
            }


            AppScheduleState.ALLOWED -> {
                // Continue
            }
        }


        // Daily limit
        if (
            rule.block_after_limit &&
            rule.daily_limit > 0
        ) {

            val used =
                UsageTracker.getUsageForApp(
                    appPackage,
                    context
                )

            if (
                used >= rule.daily_limit
            ) {

                return true to
                        "Daily limit reached"
            }
        }


        // 9 PM
        if (
            rule.block_after_9pm
        ) {

            val cal =
                Calendar.getInstance()

            cal.timeInMillis =
                now

            if (
                cal.get(
                    Calendar.HOUR_OF_DAY
                ) >= 21
            ) {

                return true to
                        "Blocked after 9 PM"
            }
        }


        return false to null
    }


    // ========================================================
    // CHECK APP OPEN
    // ========================================================

    fun checkAppOpen(
        appPackage: String
    ) {

        if (destroyed) return

        if (appPackage.isBlank()) {
            return
        }

        val now =
            System.currentTimeMillis()

        val key =
            normalizePackageKey(
                appPackage
            )

        val rule =
            appRules[key]


        // ====================================================
        // RULES NOT LOADED YET
        // ====================================================

        if (rule == null) {

            if (
                !pendingApps.contains(
                    appPackage
                )
            ) {

                pendingApps.add(
                    appPackage
                )
            }

            Log.d(
                "RULE_FLOW",
                "Rule not loaded yet → queued $appPackage"
            )

            return
        }


        // ====================================================
        // CENTRAL RULE PROCESSING
        // ====================================================

        applyAppRule(
            appPackage,
            now
        )


        // ====================================================
        // TRACK APP OPEN
        // ====================================================

        currentAppPackage =
            appPackage

        appOpenTimestamps[
            appPackage
        ] =
            now
    }


    // ========================================================
    // URL DETECTED
    // ========================================================
    fun onUrlDetected(
        url: String?,
        appPackage: String,
        title: String? = null
    ) {

        Log.d(
            "URL_FLOW",
            "========================================"
        )

        Log.d(
            "URL_FLOW",
            "onUrlDetected()"
        )

        Log.d(
            "URL_FLOW",
            "URL = $url"
        )

        Log.d(
            "URL_FLOW",
            "APP = $appPackage"
        )

        if (
            destroyed ||
            url.isNullOrBlank()
        ) {

            Log.d(
                "URL_FLOW",
                "Ignored: tracker destroyed or URL empty"
            )

            return
        }


        val now =
            System.currentTimeMillis()


        // ====================================================
        // APP RULE CHECK
        //
        // IMPORTANT:
        // A missing rule MUST NOT stop analytics.
        //
        // URLs and searches should still be recorded even
        // when Firebase app_rules has not loaded a rule for
        // this package.
        // ====================================================

        val rule =
            appRules[
                normalizePackageKey(
                    appPackage
                )
            ]


        if (rule == null) {

            Log.d(
                "RULE_FLOW",
                "No rule for $appPackage"
            )

            Log.d(
                "RULE_FLOW",
                "Continuing with analytics"
            )

        } else {

            Log.d(
                "RULE_FLOW",
                "Rule found for $appPackage"
            )


            // =================================================
            // EVALUATE SCHEDULE
            // =================================================

            val schedule =
                evaluateSchedule(
                    rule,
                    now
                )


            // =================================================
            // PARENT MANUAL BLOCK
            // =================================================

            if (
                rule.blocked
            ) {

                showAppBlock(
                    appPackage,
                    "Blocked by parent"
                )

                return
            }


            // =================================================
            // WAITING FOR ALLOWED WINDOW
            // =================================================

            if (
                schedule.state ==
                AppScheduleState.WAITING_FOR_START
            ) {

                showAppCountdown(
                    appPackage,
                    schedule.remainingSeconds,
                    "Available soon"
                )

                safeGoHome()

                return
            }


            // =================================================
            // ALLOWED WINDOW FINISHED
            // =================================================

            if (
                schedule.state ==
                AppScheduleState.FINISHED
            ) {

                showAppBlock(
                    appPackage,
                    "Time limit reached"
                )

                return
            }


            // =================================================
            // DAILY LIMIT
            // =================================================

            if (
                rule.block_after_limit &&
                rule.daily_limit > 0
            ) {

                val used =
                    UsageTracker.getUsageForApp(
                        appPackage,
                        context
                    )

                Log.d(
                    "BLOCK_CHECK",
                    "Usage for $appPackage = $used"
                )


                if (
                    used >= rule.daily_limit
                ) {

                    showAppBlock(
                        appPackage,
                        "Daily limit reached"
                    )

                    return
                }
            }


            // =================================================
            // AFTER 9 PM
            // =================================================

            if (
                rule.block_after_9pm
            ) {

                val cal =
                    Calendar.getInstance()

                cal.timeInMillis =
                    now


                if (
                    cal.get(
                        Calendar.HOUR_OF_DAY
                    ) >= 21
                ) {

                    showAppBlock(
                        appPackage,
                        "Blocked after 9 PM"
                    )

                    return
                }
            }


            // =================================================
            // COUNTDOWN NEAR END
            // =================================================

            if (
                schedule.remainingSeconds in 1..60
            ) {

                showAppCountdown(
                    appPackage,
                    schedule.remainingSeconds,
                    "Time almost up"
                )

            } else {

                overlayManager.removeOverlay(
                    appPackage
                )
            }
        }


        // ====================================================
        // SYSTEM URL FILTER
        // ====================================================

        val fixedUrl =
            try {

                UrlFilter.normalize(
                    url
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "URL normalization failed: $url",
                    e
                )

                return
            }


        if (
            fixedUrl.isBlank()
        ) {

            Log.d(
                TAG,
                "Ignored: normalized URL is blank"
            )

            return
        }


        if (
            UrlFilter.isSystemUrl(
                fixedUrl,
                appPackage
            )
        ) {

            Log.d(
                TAG,
                "Ignored system URL: $fixedUrl"
            )

            return
        }


        Log.d(
            "URL_FLOW",
            "FIXED URL = $fixedUrl"
        )


        // ====================================================
        // DOMAIN
        // ====================================================

        val domain =
            extractDomain(
                fixedUrl
            )


        if (
            domain.isNullOrBlank()
        ) {

            Log.d(
                "URL_FLOW",
                "Could not extract domain from URL"
            )

            return
        }


        val normalizedDomain =
            normalizeDomain(
                domain
            )


        Log.d(
            "URL_FLOW",
            "DOMAIN = $normalizedDomain"
        )


        // ====================================================
        // BLOCKED DOMAIN
        // ====================================================

        if (
            isDomainBlocked(
                normalizedDomain
            ) &&
            isRealWebsite(
                fixedUrl
            )
        ) {

            Log.d(
                "BLOCK_FLOW",
                "Blocked domain detected: $normalizedDomain"
            )


            if (
                normalizedDomain == lastBlocked &&
                now - lastBlockTime <
                BLOCK_COOLDOWN
            ) {

                Log.d(
                    "BLOCK_FLOW",
                    "Blocked domain cooldown active"
                )

                return
            }


            lastBlocked =
                normalizedDomain

            lastBlockTime =
                now


            val reason =
                getBlockedReason(
                    normalizedDomain
                )


            // =================================================
            // SAVE BLOCKED ATTEMPT
            // =================================================

            analyticsRef
                .child("blocked_attempts")
                .push()
                .setValue(
                    mapOf(
                        "type" to "domain",
                        "domain" to normalizedDomain,
                        "url" to fixedUrl,
                        "package" to appPackage,
                        "reason" to reason,
                        "time" to now,
                        "createdAt" to ServerValue.TIMESTAMP
                    )
                )
                .addOnSuccessListener {

                    Log.d(
                        "FIREBASE_FLOW",
                        "Blocked attempt saved"
                    )
                }
                .addOnFailureListener { e ->

                    Log.e(
                        "FIREBASE_FLOW",
                        "Failed to save blocked attempt",
                        e
                    )
                }


            launchBlockedWebsiteActivity(
                normalizedDomain,
                reason
            )

            return
        }


        // ====================================================
        // URI
        // ====================================================

        val uri =
            try {

                Uri.parse(
                    fixedUrl
                )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "Uri.parse failed: $fixedUrl",
                    e
                )

                return
            }


        // ====================================================
        // APP SEARCH DETECTION
        // ====================================================

        try {

            val detected =
                SearchIntentDetector.detect(
                    appPackage,
                    url
                )


            if (
                detected != null
            ) {

                Log.d(
                    "SEARCH_AI",
                    "Detected search = $detected"
                )


                val cleanDetected =
                    searchDeduplicator.normalize(
                        detected
                    )


                if (
                    cleanDetected.isNotBlank() &&
                    searchDeduplicator.shouldSave(
                        cleanDetected,
                        now
                    )
                ) {

                    Log.d(
                        "SEARCH_FLOW",
                        "Saving app-detected search = $cleanDetected"
                    )


                    saveSearchQuery(
                        cleanDetected,
                        appPackage,
                        now,
                        "app_ai"
                    )

                } else {

                    Log.d(
                        "SEARCH_FLOW",
                        "App search skipped by deduplicator"
                    )
                }
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                "SEARCH_AI",
                "SearchIntentDetector failed",
                e
            )
        }


        // ====================================================
        // SEARCH ENGINE
        // ====================================================

        val searchEngine =
            detectSearchEngine(
                uri
            )


        lastSearchEngine =
            searchEngine


        Log.d(
            "SEARCH_FLOW",
            "Search engine = $searchEngine"
        )


        // ====================================================
        // SEARCH QUERY
        // ====================================================

        val searchQuery =
            extractSearchQuery(
                uri
            )


        Log.d(
            "SEARCH_FLOW",
            "Extracted search query = $searchQuery"
        )


        if (
            !searchQuery.isNullOrBlank()
        ) {

            val cleanQuery =
                try {

                    Uri.decode(
                        searchQuery
                    )

                } catch (
                    _: Exception
                ) {

                    searchQuery
                }
                    .trim()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )


            if (
                cleanQuery.length >= 3 &&
                !cleanQuery.equals(
                    "Search YouTube",
                    true
                )
            ) {

                Log.d(
                    "SEARCH_FLOW",
                    "Valid browser search = $cleanQuery"
                )


                // =============================================
                // CANCEL PREVIOUS PENDING SEARCH
                // =============================================

                pendingSearchRunnable?.let {

                    searchHandler.removeCallbacks(
                        it
                    )
                }


                val queryTime =
                    System.currentTimeMillis()


                pendingSearchRunnable =
                    Runnable {

                        if (
                            destroyed
                        ) {

                            return@Runnable
                        }


                        if (
                            !searchDeduplicator.shouldSave(
                                cleanQuery,
                                queryTime
                            )
                        ) {

                            Log.d(
                                "SEARCH_FLOW",
                                "Search skipped by deduplicator = $cleanQuery"
                            )

                            return@Runnable
                        }


                        Log.d(
                            "SEARCH_FLOW",
                            "Saving browser search = $cleanQuery"
                        )


                        saveSearchQuery(
                            cleanQuery,
                            appPackage,
                            System.currentTimeMillis(),
                            searchEngine
                        )
                    }


                searchHandler.postDelayed(
                    pendingSearchRunnable!!,
                    2500L
                )

            } else {

                Log.d(
                    "SEARCH_FLOW",
                    "Ignored search query = $cleanQuery"
                )
            }
        }


        // ====================================================
        // YOUTUBE
        // ====================================================

        try {

            detectYoutubeVideo(
                uri
            )?.let { youtubeData ->

                val videoId =
                    youtubeData.first

                val videoTitle =
                    youtubeData.second


                Log.d(
                    "YOUTUBE_FLOW",
                    "YouTube video detected = $videoId"
                )


                saveYoutubeVideo(
                    videoId,
                    videoTitle,
                    appPackage,
                    now
                )
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                "YOUTUBE_FLOW",
                "YouTube detection failed",
                e
            )
        }


        // ====================================================
        // DOMAIN CLASSIFICATION
        // ====================================================

        val category =
            classifyDomain(
                normalizedDomain
            )


        lastDomainCategory =
            category


        Log.d(
            "URL_FLOW",
            "CATEGORY = $category"
        )


        // ====================================================
        // DOMAIN SESSION
        // ====================================================

        if (
            currentDomain == null ||
            currentDomain != normalizedDomain ||
            now - lastUrlTime > SESSION_TIMEOUT
        ) {

            Log.d(
                "SESSION_FLOW",
                "Starting new domain session"
            )


            endCurrentSession()


            startNewSession(
                normalizedDomain,
                fixedUrl
            )
        }


        activeSession =
            DomainSession(
                url = fixedUrl,
                domain = normalizedDomain,
                category = category,
                start = sessionStart,
                lastSeen = now
            )


        // ====================================================
        // JUNK URL FILTER
        // ====================================================

        if (
            isJunkUrl(
                fixedUrl
            )
        ) {

            Log.d(
                "URL_FLOW",
                "Ignored junk URL = $fixedUrl"
            )

            return
        }


        // ====================================================
        // NORMALIZED URL
        // ====================================================

        val normalizedUrl =
            when {

                fixedUrl.contains(
                    "youtube.com/watch",
                    ignoreCase = true
                ) -> {

                    val videoId =
                        uri.getQueryParameter(
                            "v"
                        )
                            ?: return

                    "youtube_watch_$videoId"
                }


                fixedUrl.contains(
                    "/shorts/",
                    ignoreCase = true
                ) -> {

                    val shortsId =
                        uri.pathSegments
                            .lastOrNull()
                            ?: return

                    "youtube_shorts_$shortsId"
                }


                else -> {

                    fixedUrl
                        .substringBefore("?")
                        .trim()
                        .lowercase()
                }
            }


        // ====================================================
        // URL DUPLICATE CHECK
        // ====================================================

        if (
            normalizedUrl ==
            lastSavedUrl &&
            now - lastSavedTime <
            URL_COOLDOWN
        ) {

            Log.d(
                TAG,
                "Skipped duplicate URL: $normalizedUrl"
            )

            return
        }


        // ====================================================
        // SAVE VISITED URL
        // ====================================================

        val dateKey =
            getTodayKey()


        Log.d(
            "FIREBASE_FLOW",
            "Saving URL to Firebase"
        )

        Log.d(
            "FIREBASE_FLOW",
            "Path = analytics_browsing/$childId/visited_urls/$dateKey"
        )


        val visitData =
            mapOf(
                "url" to fixedUrl,
                "domain" to normalizedDomain,
                "category" to category,
                "package" to appPackage,
                "title" to (title ?: ""),
                "time" to now,
                "createdAt" to ServerValue.TIMESTAMP
            )


        analyticsRef
            .child("visited_urls")
            .child(dateKey)
            .push()
            .setValue(
                visitData
            )
            .addOnSuccessListener {

                Log.d(
                    "FIREBASE_FLOW",
                    "URL SAVED SUCCESSFULLY"
                )

                Log.d(
                    "FIREBASE_FLOW",
                    "URL = $fixedUrl"
                )
            }
            .addOnFailureListener { e ->

                Log.e(
                    "FIREBASE_FLOW",
                    "URL SAVE FAILED: ${e.message}",
                    e
                )
            }


        // ====================================================
        // UPDATE URL DEDUPLICATION
        // ====================================================

        lastSavedUrl =
            normalizedUrl

        lastSavedTime =
            now


        lastUrlTime =
            now


        Log.d(
            "URL_FLOW",
            "URL processing completed"
        )
    }

    // ========================================================
    // NAVIGATION EVENT
    // ========================================================

    fun onNavigationEvent(
        event: NavigationEvent
    ) {

        if (destroyed) return

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


        event.searchQuery?.let { query ->

            if (
                query.length >= 2
            ) {

                saveSearchQuery(
                    query,
                    event.packageName,
                    event.timestamp,
                    event.type
                )
            }
        }


        if (
            event.type.contains(
                "youtube",
                true
            ) ||
            event.type.contains(
                "tiktok",
                true
            ) ||
            event.type.contains(
                "instagram",
                true
            )
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


        onUrlDetected(
            event.url,
            event.packageName,
            event.title
        )
    }


    // ========================================================
    // DIRECT SEARCH DETECTION
    // ========================================================

    // ========================================================
// DIRECT SEARCH DETECTION
// ========================================================

    fun onSearchDetected(
        query: String,
        packageName: String,
        source: String = "accessibility"
    ) {

        if (destroyed) {
            Log.d(
                "SEARCH_FLOW",
                "⏭ onSearchDetected ignored — tracker destroyed"
            )
            return
        }

        Log.d(
            "SEARCH_FLOW",
            """
        ==========================================
        🚨 onSearchDetected()
        ==========================================
        Raw Query   = [$query]
        Raw Package = [$packageName]
        Source      = [$source]
        ==========================================
        """.trimIndent()
        )

        // ====================================================
        // 1. CLEAN QUERY
        // ====================================================

        val cleanQuery =
            try {
                Uri.decode(query)
            } catch (e: Exception) {

                Log.w(
                    "SEARCH_FLOW",
                    "⚠️ Uri.decode failed, using original query",
                    e
                )

                query
            }
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        Log.d(
            "SEARCH_FLOW",
            "🧹 Clean query = [$cleanQuery]"
        )


        // ====================================================
        // 2. VALIDATE QUERY
        // ====================================================

        if (
            cleanQuery.length < 3 ||
            cleanQuery.length > 200
        ) {

            Log.d(
                "SEARCH_FLOW",
                "🚫 Search rejected — invalid length"
            )

            Log.d(
                "SEARCH_FLOW",
                "Length = ${cleanQuery.length}"
            )

            return
        }


        // ====================================================
        // 3. REJECT URL INPUT
        // ====================================================

        if (
            cleanQuery.startsWith(
                "http://",
                ignoreCase = true
            ) ||
            cleanQuery.startsWith(
                "https://",
                ignoreCase = true
            ) ||
            cleanQuery.startsWith(
                "www.",
                ignoreCase = true
            )
        ) {

            Log.d(
                "SEARCH_FLOW",
                "🚫 Search rejected — looks like URL"
            )

            Log.d(
                "SEARCH_FLOW",
                "Query = [$cleanQuery]"
            )

            return
        }


        // ====================================================
        // 4. REJECT UI PLACEHOLDERS
        // ====================================================

        val normalized =
            cleanQuery
                .lowercase()
                .trim()

        val ignoredQueries =
            setOf(
                "search",
                "search...",
                "search here",
                "search youtube",
                "search tiktok",
                "search instagram",
                "search facebook",
                "search x",
                "search twitter",
                "tap to search",
                "listening...",
                "google search",
                "google",
                "search or type web address"
            )

        if (
            normalized in ignoredQueries
        ) {

            Log.d(
                "SEARCH_FLOW",
                "🚫 Search rejected — UI placeholder"
            )

            Log.d(
                "SEARCH_FLOW",
                "Query = [$cleanQuery]"
            )

            return
        }


        // ====================================================
        // 5. PRESERVE EXACT APPLICATION PACKAGE
        // ====================================================

        val cleanPackage =
            packageName
                .trim()

        if (cleanPackage.isBlank()) {

            Log.e(
                "SEARCH_FLOW",
                "❌ Search rejected — package is empty"
            )

            return
        }


        // ====================================================
        // 6. DETERMINE SEARCH SOURCE
        // ====================================================

        val cleanSource =
            source
                .trim()
                .ifBlank {
                    "accessibility"
                }


        // ====================================================
        // 7. NATIVE APP IDENTIFICATION
        //
        // IMPORTANT:
        //
        // For native searches we deliberately keep the actual
        // application package.
        //
        // Example:
        //
        // TikTok Lite:
        // com.zhiliaoapp.musically.go
        //
        // TikTok:
        // com.zhiliaoapp.musically
        //
        // Instagram:
        // com.instagram.android
        //
        // YouTube:
        // com.google.android.youtube
        //
        // This package is later used by the Parent App to
        // resolve InstalledAppInfo.iconBase64.
        // ====================================================

        val isNativeAppSearch =
            cleanSource.equals(
                "native_app",
                ignoreCase = true
            )

        Log.d(
            "SEARCH_FLOW",
            "📱 Native app search = $isNativeAppSearch"
        )

        if (isNativeAppSearch) {

            Log.d(
                "SEARCH_FLOW",
                "🖼️ Icon lookup package preserved"
            )

            Log.d(
                "SEARCH_FLOW",
                "📦 App package = [$cleanPackage]"
            )
        }


        // ====================================================
        // 8. SAVE
        // ====================================================

        Log.d(
            "SEARCH_FLOW",
            """
        ==========================================
        💾 SAVING SEARCH
        ==========================================
        Query   = [$cleanQuery]
        Package = [$cleanPackage]
        Source  = [$cleanSource]
        Engine  = [$cleanSource]
        Native  = $isNativeAppSearch
        ==========================================
        """.trimIndent()
        )

        saveSearchQuery(
            query = cleanQuery,
            pkg = cleanPackage,
            time = System.currentTimeMillis(),
            engine = cleanSource
        )


        // ====================================================
        // 9. CONFIRM
        // ====================================================

        Log.d(
            "SEARCH_FLOW",
            """
        ==========================================
        ✅ SEARCH DISPATCHED
        ==========================================
        Query   = [$cleanQuery]
        Package = [$cleanPackage]
        Source  = [$cleanSource]
        ==========================================
        """.trimIndent()
        )
    }

    // ========================================================
    // SAVE SEARCH
    // ========================================================

    private fun saveSearchQuery(
        query: String,
        pkg: String,
        time: Long,
        engine: String
    ) {

        Log.d(
            "SEARCH_DEBUG",
            """
        ==========================================
        🚨 saveSearchQuery() CALLED
        ==========================================
        RAW QUERY   = [$query]
        PACKAGE     = [$pkg]
        TIME        = $time
        ENGINE      = [$engine]
        ==========================================
        """.trimIndent()
        )

        // ====================================================
        // CLEAN QUERY
        // ====================================================

        val decodedQuery =
            try {
                Uri.decode(query)
            } catch (_: Exception) {
                query
            }
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )


        // ====================================================
        // VALIDATION VERSION
        // ====================================================

        val normalizedQuery =
            decodedQuery.lowercase()


        // ====================================================
        // BASIC VALIDATION
        // ====================================================

        if (
            decodedQuery.isBlank() ||
            decodedQuery.length < 3 ||
            decodedQuery.length > 200
        ) {
            return
        }


        // ====================================================
        // PACKAGE VALIDATION
        // ====================================================

        val cleanPackage =
            pkg.trim()

        if (cleanPackage.isBlank()) {
            return
        }


        // ====================================================
        // CLEAN ENGINE
        // ====================================================

        val normalizedEngine =
            engine
                .trim()
                .lowercase()
                .ifBlank {
                    "unknown"
                }


        // ====================================================
        // IGNORE NON-USER / SYSTEM SEARCH EVENTS
        //
        // native_app events can be generated by the phone
        // itself rather than by an actual child search.
        //
        // IMPORTANT:
        // This check happens BEFORE:
        // - duplicate tracking
        // - risk classification
        // - alert creation
        // - Firebase writing
        // ====================================================

        if (
            normalizedEngine == "system" ||
            normalizedEngine == "system_app"
        ) {

            Log.d(
                TAG,
                """
        Ignoring system search event
        ----------------------------
        Query   = $decodedQuery
        Package = $cleanPackage
        Engine  = $normalizedEngine
        """.trimIndent()
            )

            return
        }


        // ====================================================
        // IGNORE PLACEHOLDER SEARCH TEXT
        // ====================================================

        val ignoredQueries =
            setOf(
                "search",
                "search youtube",
                "search tiktok",
                "search instagram",
                "search facebook",
                "search x",
                "search twitter",
                "search here",
                "search...",
                "listening...",
                "tap to search",
                "search or type web address",
                "google search",
                "google"
            )


        if (
            normalizedQuery in ignoredQueries
        ) {
            return
        }


        // ====================================================
        // IGNORE URL / WEB ADDRESS INPUT
        // ====================================================

        if (
            normalizedQuery.startsWith("http://") ||
            normalizedQuery.startsWith("https://") ||
            normalizedQuery.startsWith("www.")
        ) {
            return
        }


        // ====================================================
        // DUPLICATE CHECK
        //
        // package + engine + query
        // ====================================================

        val searchKey =
            "$cleanPackage|$normalizedEngine|$normalizedQuery"


        val duplicate =
            searchKey == lastLoggedSearchKey &&
                    time - lastLoggedTime < SEARCH_COOLDOWN


        if (duplicate) {
            return
        }


        // ====================================================
        // UPDATE DEDUPLICATION STATE
        // ====================================================

        lastLoggedSearchKey =
            searchKey

        lastLoggedTime =
            time


        // ====================================================
        // RISK CLASSIFICATION
        // ====================================================

        val risk =
            ContentClassifier2
                .urlFlag(
                    normalizedQuery
                )
                ?: "safe"

        Log.d(
            "SEARCH_RISK",
            "Query = $decodedQuery | Classified risk = $risk"
        )

        // ====================================================
        // CREATE ALERT FOR RISKY SEARCH
        // ====================================================

        if (
            risk in setOf(
                "porn",
                "sexual",
                "religious",
                "violence",
                "drugs"
            )
        ) {

            createSearchRiskAlert(
                query = decodedQuery,
                category = risk,
                packageName = cleanPackage,
                time = time
            )
        }


        // ====================================================
        // SEARCH DATA
        // ====================================================

        val searchData =
            mapOf(
                "query" to decodedQuery,
                "package" to cleanPackage,
                "engine" to normalizedEngine,
                "source" to normalizedEngine,
                "riskLevel" to risk,
                "time" to time,
                "createdAt" to ServerValue.TIMESTAMP
            )


        // ====================================================
        // SAVE SEARCH HISTORY
        // ====================================================
        Log.d(
            "FIREBASE_SEARCH",
            """
    ==========================================
    💾 SAVING SEARCH TO FIREBASE
    ==========================================
    Child ID = $childId
    Date     = ${getTodayKey()}
    Query    = $decodedQuery
    Package  = $cleanPackage
    Engine   = $normalizedEngine
    Risk     = $risk
    Path     = analytics_browsing/$childId/search_history/${getTodayKey()}
    ==========================================
    """.trimIndent()
        )

        analyticsRef
            .child("search_history")
            .child(getTodayKey())
            .push()
            .setValue(searchData)
            .addOnSuccessListener {

                Log.d(
                    "FIREBASE_SEARCH",
                    """
            ==========================================
            ✅ SEARCH SAVED SUCCESSFULLY
            ==========================================
            Query   = $decodedQuery
            Package = $cleanPackage
            Engine  = $normalizedEngine
            Risk    = $risk
            ==========================================
            """.trimIndent()
                )
            }
            .addOnFailureListener { e ->

                Log.e(
                    "FIREBASE_SEARCH",
                    """
            ==========================================
            ❌ SEARCH FIREBASE WRITE FAILED
            ==========================================
            Query = $decodedQuery
            Error = ${e.message}
            ==========================================
            """.trimIndent(),
                    e
                )
            }
    }
    // ========================================================
// CREATE SEARCH RISK ALERT
// ========================================================

    private fun createSearchRiskAlert(
        query: String,
        category: String,
        packageName: String,
        time: Long
    ) {

        try {

            // ====================================================
            // ONLY ALERT FOR SELECTED SEARCH CATEGORIES
            // ====================================================

            val normalizedCategory =
                category
                    .trim()
                    .lowercase()

            if (
                normalizedCategory !in setOf(
                    "porn",
                    "sexual",
                    "religious",
                    "violence",
                    "drugs"
                )
            ) {
                return
            }


            // ====================================================
            // NORMALIZE QUERY
            // ====================================================

            val normalizedQuery =
                query
                    .trim()
                    .lowercase()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )


            // ====================================================
            // ALERT DEDUPLICATION
            // ====================================================

            val now =
                System.currentTimeMillis()

            val alertKey =
                "$normalizedCategory|$normalizedQuery|$packageName"

            val isDuplicate =
                alertKey == lastSearchAlertKey &&
                        now - lastSearchAlertTime <
                        SEARCH_ALERT_COOLDOWN

            if (isDuplicate) {

                Log.d(
                    TAG,
                    "🔁 Duplicate search alert ignored: $query"
                )

                return
            }


            // ====================================================
            // UPDATE ALERT STATE
            // ====================================================

            lastSearchAlertKey =
                alertKey

            lastSearchAlertTime =
                now


            // ====================================================
            // ALERT TITLE
            // ====================================================

            val title =
                when (normalizedCategory) {

                    "porn" ->
                        "Pornographic Search Detected"

                    "sexual" ->
                        "Sexual Content Search Detected"

                    "religious" ->
                        "Religious Content Search Detected"

                    "violence" ->
                        "Violence-Related Search Detected"

                    "drugs" ->
                        "Drug-Related Search Detected"

                    else ->
                        "Risky Search Detected"
                }


            // ====================================================
            // RISK LEVEL
            // ====================================================

            val risk =
                when (normalizedCategory) {

                    "porn" ->
                        "critical"

                    "sexual" ->
                        "critical"

                    "violence" ->
                        "high"

                    "drugs" ->
                        "high"

                    "religious" ->
                        "low"

                    else ->
                        "medium"
                }


            // ====================================================
            // ALERT DATA
            // ====================================================

            val alertData =
                mapOf(

                    // Alert identity
                    "type" to "search",

                    "category" to normalizedCategory,

                    "title" to title,

                    "description" to
                            "The child searched for: $query",

                    // Search details
                    "query" to query,

                    "packageName" to packageName,

                    // Risk
                    "risk" to risk,

                    // Time
                    "timestamp" to time,

                    "createdAt" to ServerValue.TIMESTAMP
                )


            // ====================================================
            // SAVE ALERT
            // ====================================================

            FirebaseDatabase
                .getInstance()
                .getReference("alerts")
                .child(childId)
                .push()
                .setValue(alertData)
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        """
                    ==========================================
                    🚨 SEARCH ALERT CREATED
                    ==========================================
                    Category = $normalizedCategory
                    Query = $query
                    Package = $packageName
                    ==========================================
                    """.trimIndent()
                    )
                }
                .addOnFailureListener { e ->

                    Log.e(
                        TAG,
                        "❌ Failed to create search alert",
                        e
                    )
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Search alert creation error",
                e
            )
        }
    }

    // ========================================================
    // YOUTUBE
    // ========================================================

    private fun detectYoutubeVideo(
        uri: Uri
    ): Pair<String, String>? {

        val host =
            uri.host
                ?: return null

        if (
            !host.contains(
                "youtube"
            ) &&
            !host.contains(
                "youtu.be"
            )
        ) {
            return null
        }


        val videoId =
            uri.getQueryParameter(
                "v"
            )

        val path =
            uri.path
                ?: ""


        val shortsId =
            if (
                path.contains(
                    "/shorts/"
                )
            ) {

                path
                    .substringAfter(
                        "/shorts/"
                    )
                    .substringBefore(
                        "/"
                    )

            } else {
                null
            }


        val id =
            videoId
                ?: shortsId
                ?: return null


        val title =
            uri.getQueryParameter(
                "title"
            )
                ?: "YouTube Video"


        return Pair(
            id,
            title
        )
    }


    private fun saveYoutubeVideo(
        videoId: String,
        title: String,
        pkg: String,
        time: Long
    ) {

        val last =
            lastYoutubeMap[
                videoId
            ]
                ?: 0L


        if (
            time - last < 5000
        ) {
            return
        }


        lastYoutubeMap[
            videoId
        ] = time


        val risk =
            ContentClassifier2
                .urlFlag(
                    title.lowercase()
                )
                ?: "safe"


        activeYoutube?.let {

            saveYoutubeSession(
                it,
                time
            )
        }


        activeYoutube =
            YoutubeSession(
                videoId = videoId,
                title = title,
                thumbnail =
                    "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                category =
                    if (
                        risk == "adult"
                    ) {
                        "adult"
                    } else {
                        "video"
                    },
                start = time,
                lastSeen = time
            )


        lastYoutubeVideo =
            videoId


        analyticsRef
            .child("youtube_history")
            .push()
            .setValue(
                mapOf(
                    "type" to "youtube",
                    "videoId" to videoId,
                    "title" to title,
                    "thumbnail" to
                            "https://img.youtube.com/vi/$videoId/hqdefault.jpg",
                    "package" to pkg,
                    "riskLevel" to risk,
                    "startTime" to time,
                    "createdAt" to ServerValue.TIMESTAMP
                )
            )
    }


    private fun saveYoutubeSession(
        session: YoutubeSession,
        end: Long
    ) {

        val duration =
            end - session.start

        if (
            duration < 1000
        ) {
            return
        }


        analyticsRef
            .child("youtube_history")
            .push()
            .setValue(
                mapOf(
                    "type" to "youtube_session",
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


    // ========================================================
    // APP SEARCH FALLBACK
    // ========================================================

    // ========================================================
// APP SEARCH FALLBACK
// ========================================================

    fun detectSearchFromApp(
        appPackage: String,
        text: String?
    ): String? {

        if (text.isNullOrBlank()) {
            return null
        }

        // ====================================================
        // CLEAN TEXT
        // ====================================================

        val clean =
            Uri.decode(text)
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        // ====================================================
        // BASIC VALIDATION
        // ====================================================

        if (
            clean.length < 3 ||
            clean.length > 200
        ) {
            return null
        }

        // ====================================================
        // IGNORE URL / ADDRESS INPUT
        // ====================================================

        if (
            clean.startsWith("http://", true) ||
            clean.startsWith("https://", true) ||
            clean.startsWith("www.", true)
        ) {
            return null
        }

        // ====================================================
        // IGNORE COMMON SEARCH UI TEXT
        // ====================================================

        val normalized =
            clean.lowercase()

        val ignoredQueries =
            setOf(
                "search",
                "search here",
                "search...",
                "search youtube",
                "search tiktok",
                "search instagram",
                "search facebook",
                "search x",
                "search twitter",
                "listening...",
                "tap to search",
                "google search",
                "search or type web address"
            )

        if (
            normalized in ignoredQueries
        ) {
            return null
        }

        // ====================================================
        // APP PACKAGE VALIDATION
        // ====================================================

        if (appPackage.isBlank()) {
            return null
        }

        // ====================================================
        // OPTION A
        //
        // Do NOT identify apps by hardcoded package names.
        //
        // If the AccessibilityService / NavigationEvent detector
        // has already determined that this text represents a
        // search query, simply return the cleaned query.
        // ====================================================

        return clean
    }


    // ========================================================
    // SEARCH ENGINE
    // ========================================================

    private fun detectSearchEngine(
        uri: Uri
    ): String {

        val host =
            uri.host
                ?.lowercase()
                ?: return "unknown"


        return when {

            host.contains(
                "google."
            ) ->
                "google"

            host.contains(
                "bing."
            ) ->
                "bing"

            host.contains(
                "duckduckgo."
            ) ->
                "duckduckgo"

            host.contains(
                "yahoo."
            ) ->
                "yahoo"

            host.contains(
                "youtube"
            ) ->
                "youtube"

            else ->
                "unknown"
        }
    }


    // ========================================================
    // SEARCH QUERY EXTRACTION
    // ========================================================

    private fun extractSearchQuery(
        uri: Uri
    ): String? {

        val host =
            uri.host
                ?.lowercase()
                ?: return null


        return when {

            host.contains(
                "google."
            ) -> {

                if (
                    uri.path?.contains(
                        "/search"
                    ) == true
                ) {

                    uri.getQueryParameter(
                        "q"
                    )
                        ?: uri.getQueryParameter(
                            "oq"
                        )
                        ?: uri.getQueryParameter(
                            "query"
                        )

                } else {
                    null
                }
            }


            host.contains(
                "bing."
            ) ||
                    host.contains(
                        "duckduckgo."
                    ) -> {

                uri.getQueryParameter(
                    "q"
                )
            }


            host.contains(
                "yahoo."
            ) -> {

                uri.getQueryParameter(
                    "p"
                )
            }


            host.contains(
                "youtube.com"
            ) -> {

                uri.getQueryParameter(
                    "search_query"
                )
                    ?: uri.getQueryParameter(
                        "q"
                    )
            }


            host.contains(
                "tiktok.com"
            ) -> {

                uri.getQueryParameter(
                    "q"
                )
                    ?: uri.getQueryParameter(
                        "keyword"
                    )
                    ?: uri.getQueryParameter(
                        "search"
                    )
            }


            else ->
                null
        }
    }


    // ========================================================
    // URL CLEANING
    // ========================================================

    private fun isJunkUrl(
        url: String
    ): Boolean {

        return url.contains(
            "app://"
        ) ||
                url.contains(
                    "about:blank"
                ) ||
                url.contains(
                    "youtube.com/results?search_query=Search"
                ) ||
                url.contains(
                    "chrome://"
                ) ||
                url.contains(
                    "com.android.systemui"
                )
    }


    // ========================================================
    // APP USAGE
    // ========================================================

    private fun updateAppUsage(
        packageName: String,
        durationMs: Long
    ) {

        if (
            durationMs < 1000
        ) {
            return
        }


        analyticsUsageRef
            .child("app_usage")
            .child(packageName)
            .child("totalTime")
            .runTransaction(
                object : Transaction.Handler {

                    override fun doTransaction(
                        currentData: MutableData
                    ): Transaction.Result {

                        val current =
                            currentData.getValue(
                                Long::class.java
                            )
                                ?: 0L

                        currentData.value =
                            current + durationMs

                        return Transaction.success(
                            currentData
                        )
                    }


                    override fun onComplete(
                        error: DatabaseError?,
                        committed: Boolean,
                        snapshot: DataSnapshot?
                    ) {
                    }
                }
            )
    }


    private fun endAppSession(
        appPackage: String
    ) {

        val start =
            appOpenTimestamps[
                appPackage
            ]
                ?: return

        val duration =
            System.currentTimeMillis() -
                    start


        if (
            duration > 1000
        ) {

            updateAppUsage(
                appPackage,
                duration
            )
        }


        appOpenTimestamps.remove(
            appPackage
        )
    }


    // ========================================================
    // DOMAIN SESSION
    // ========================================================

    private fun startNewSession(
        domain: String,
        url: String
    ) {

        val sessionRef =
            db.child("children")
                .child(childId)
                .child("browsing")
                .child("sessions")
                .push()


        val sessionId =
            sessionRef.key
                ?: return


        currentSessionId =
            sessionId

        currentDomain =
            domain

        sessionStart =
            System.currentTimeMillis()


        sessionRef.setValue(
            mapOf(
                "domain" to domain,
                "url" to url,
                "startTime" to sessionStart,
                "endTime" to null,
                "duration" to 0,
                "active" to true,
                "createdAt" to ServerValue.TIMESTAMP
            )
        )


        activeSession =
            DomainSession(
                url = url,
                domain = domain,
                category =
                    classifyDomain(
                        domain
                    ),
                start = sessionStart,
                lastSeen = sessionStart
            )
    }


    private fun endCurrentSession() {

        val sessionId =
            currentSessionId
                ?: return


        val endTime =
            System.currentTimeMillis()

        val duration =
            endTime - sessionStart


        db.child("children")
            .child(childId)
            .child("browsing")
            .child("sessions")
            .child(sessionId)
            .updateChildren(
                mapOf(
                    "endTime" to endTime,
                    "duration" to duration,
                    "active" to false,
                    "updatedAt" to ServerValue.TIMESTAMP
                )
            )


        activeSession?.let { session ->

            if (
                duration >= 1000
            ) {

                saveDomainSession(
                    session,
                    endTime
                )
            }
        }


        currentSessionId =
            null

        currentDomain =
            null

        activeSession =
            null
    }


    private fun saveDomainSession(
        session: DomainSession,
        end: Long
    ) {

        val duration =
            end - session.start

        if (
            duration < 1000
        ) {
            return
        }


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


    // ========================================================
    // DOMAIN CLASSIFICATION
    // ========================================================

    private fun extractDomain(
        url: String
    ): String? {

        return try {

            val uri =
                Uri.parse(
                    url
                )

            val host =
                uri.host
                    ?: return null

            host
                .lowercase()
                .replace(
                    "www.",
                    ""
                )
                .replace(
                    "m.",
                    ""
                )
                .replace(
                    "mobile.",
                    ""
                )

        } catch (
            e: Exception
        ) {

            null
        }
    }


    private fun normalizeDomain(
        domain: String
    ): String {

        return domain
            .lowercase()
            .replace(
                "www.",
                ""
            )
            .replace(
                "m.",
                ""
            )
            .replace(
                "mobile.",
                ""
            )
    }


    private fun classifyDomain(
        domain: String
    ): String {

        return when {

            domain.contains("facebook") ||
                    domain.contains("instagram") ||
                    domain.contains("twitter") ||
                    domain.contains("x.com") ||
                    domain.contains("tiktok") ->
                "social"


            domain.contains("telegram") ||
                    domain.contains("whatsapp") ->
                "messaging"


            domain.contains("youtube") ->
                "video"


            ContentClassifier.isAdult(
                domain
            ) ->
                "adult"


            ContentClassifier.isGambling(
                domain
            ) ->
                "gambling"


            ContentClassifier.isGame(
                domain
            ) ->
                "games"


            else ->
                "general"
        }
    }


    // ========================================================
    // BLOCKED WEBSITES
    // ========================================================

    private fun listenForBlockedWebsites() {

        db.child("blocked_websites")
            .child(childId)
            .addValueEventListener(
                object : ValueEventListener {

                    override fun onDataChange(
                        snapshot: DataSnapshot
                    ) {

                        blockedDomains.clear()

                        blockedReasons.clear()


                        for (
                        child in snapshot.children
                        ) {

                            val domain =
                                child.child(
                                    "domain"
                                )
                                    .getValue(
                                        String::class.java
                                    )

                            val reason =
                                child.child(
                                    "reason"
                                )
                                    .getValue(
                                        String::class.java
                                    )


                            if (
                                !domain.isNullOrEmpty()
                            ) {

                                val normalized =
                                    normalizeDomain(
                                        domain
                                    )

                                blockedDomains.add(
                                    normalized
                                )

                                blockedReasons[
                                    normalized
                                ] =
                                    reason
                                        ?: "Blocked by Parent"
                            }
                        }


                        Log.d(
                            TAG,
                            "Blocked domains = $blockedDomains"
                        )
                    }


                    override fun onCancelled(
                        error: DatabaseError
                    ) {

                        Log.e(
                            TAG,
                            "Failed to load blocked domains: ${error.message}"
                        )
                    }
                }
            )
    }


    private fun isDomainBlocked(
        domain: String
    ): Boolean {

        return blockedDomains.any { blocked ->

            domain == blocked ||
                    domain.endsWith(
                        ".$blocked"
                    )
        }
    }


    private fun getBlockedReason(
        domain: String
    ): String {

        return blockedReasons[
            domain
        ]
            ?: "Blocked by Parent"
    }


    private fun isRealWebsite(
        url: String
    ): Boolean {

        return try {

            val uri =
                Uri.parse(
                    url
                )

            val host =
                uri.host
                    ?: return false


            if (
                url.contains(
                    "about:blank"
                )
            ) {
                return false
            }


            if (
                url.contains(
                    "chrome://"
                )
            ) {
                return false
            }


            host.contains(
                "."
            )

        } catch (
            e: Exception
        ) {

            false
        }
    }


    // ========================================================
    // BLOCKED WEBSITE ACTIVITY
    // ========================================================

    private fun launchBlockedWebsiteActivity(
        domain: String,
        reason: String
    ) {

        try {

            val intent =
                Intent(
                    context,
                    BlockOverlayActivity::class.java
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )

                    putExtra(
                        "domain",
                        domain
                    )

                    putExtra(
                        "reason",
                        reason
                    )
                }


            context.startActivity(
                intent
            )

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "Unable to launch blocked website activity",
                e
            )
        }
    }


    // ========================================================
    // SAFE HOME
    // ========================================================

    private fun safeGoHome() {

        if (destroyed) return

        val now =
            System.currentTimeMillis()


        if (
            now - lastHomeActionTime <
            HOME_COOLDOWN
        ) {

            return
        }


        lastHomeActionTime =
            now


        Handler(
            Looper.getMainLooper()
        ).post {

            try {

                accessibilityService
                    ?.performGlobalAction(
                        AccessibilityService.GLOBAL_ACTION_HOME
                    )

            } catch (
                e: Exception
            ) {

                Log.e(
                    TAG,
                    "GLOBAL_ACTION_HOME failed",
                    e
                )
            }
        }
    }


    // ========================================================
    // TODAY KEY
    // ========================================================

    private fun getTodayKey(): String {

        val format =
            SimpleDateFormat(
                "yyyy-MM-dd",
                Locale.getDefault()
            )

        return format.format(
            Date()
        )
    }


    // ========================================================
    // CLEAN OLD URL HISTORY
    // ========================================================

    private fun cleanupOldVisitedUrls() {

        val ref =
            FirebaseDatabase
                .getInstance()
                .getReference(
                    "analytics_browsing"
                )
                .child(childId)
                .child("visited_urls")


        ref.get()
            .addOnSuccessListener { snapshot ->

                val sdf =
                    SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.getDefault()
                    )

                val now =
                    System.currentTimeMillis()


                for (
                daySnapshot in snapshot.children
                ) {

                    val dateKey =
                        daySnapshot.key
                            ?: continue


                    try {

                        val date =
                            sdf.parse(
                                dateKey
                            )
                                ?: continue


                        val diffDays =
                            (
                                    now - date.time
                                    ) /
                                    (
                                            1000L *
                                                    60 *
                                                    60 *
                                                    24
                                            )


                        if (
                            diffDays > 7
                        ) {

                            ref.child(
                                dateKey
                            )
                                .removeValue()
                        }

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "URL cleanup error",
                            e
                        )
                    }
                }
            }
    }


    // ========================================================
    // CLEAN OLD SEARCH HISTORY
    // ========================================================

    private fun cleanupOldSearchHistory() {

        val ref =
            FirebaseDatabase
                .getInstance()
                .getReference(
                    "analytics_browsing"
                )
                .child(childId)
                .child("search_history")


        ref.get()
            .addOnSuccessListener { snapshot ->

                val sdf =
                    SimpleDateFormat(
                        "yyyy-MM-dd",
                        Locale.getDefault()
                    )

                val now =
                    System.currentTimeMillis()


                for (
                daySnapshot in snapshot.children
                ) {

                    val dateKey =
                        daySnapshot.key
                            ?: continue


                    try {

                        val date =
                            sdf.parse(
                                dateKey
                            )
                                ?: continue


                        val diffDays =
                            (
                                    now - date.time
                                    ) /
                                    (
                                            1000L *
                                                    60 *
                                                    60 *
                                                    24
                                            )


                        if (
                            diffDays > 7
                        ) {

                            ref.child(
                                dateKey
                            )
                                .removeValue()
                        }

                    } catch (
                        e: Exception
                    ) {

                        Log.e(
                            TAG,
                            "Search cleanup error",
                            e
                        )
                    }
                }
            }
    }


    // ========================================================
    // NORMALIZE PACKAGE
    // ========================================================

    private fun normalizePackageKey(
        pkg: String
    ): String {

        return pkg.replace(
            ".",
            "_"
        )
    }


    // ========================================================
    // DESTROY
    // ========================================================

    fun destroy() {

        if (destroyed) {
            return
        }


        Log.d(
            TAG,
            "Destroying BrowsingTracker"
        )


        destroyed =
            true


        // Stop session checker
        handler.removeCallbacksAndMessages(
            null
        )


        // Stop search tasks
        pendingSearchRunnable?.let {

            searchHandler.removeCallbacks(
                it
            )
        }


        searchHandler.removeCallbacksAndMessages(
            null
        )


        pendingSearchRunnable =
            null


        // Stop Firebase rules listener
        AppRuleManager.stopRulesListener(
            childId
        )


        // Remove URL receiver
        try {

            context.applicationContext
                .unregisterReceiver(
                    urlReceiver
                )

        } catch (
            _: Exception
        ) {
        }


        // Save active domain session
        activeSession?.let {

            saveDomainSession(
                it,
                System.currentTimeMillis()
            )
        }

        activeSession =
            null


        // Save active YouTube session
        activeYoutube?.let {

            saveYoutubeSession(
                it,
                System.currentTimeMillis()
            )
        }

        activeYoutube =
            null


        // Close current app usage
        currentApp?.let {

            endAppSession(
                it
            )
        }


        currentApp =
            null

        currentAppPackage =
            null


        // Remove every overlay
        try {

            overlayManager.destroy()

        } catch (
            e: Exception
        ) {

            Log.e(
                TAG,
                "OverlayManager destroy failed",
                e
            )
        }


        pendingApps.clear()

        appRules.clear()

        blockedDomains.clear()

        blockedReasons.clear()

        appStartTimes.clear()

        appOpenTimestamps.clear()

        overlayCooldownMap.clear()

        lastYoutubeMap.clear()


        Log.d(
            TAG,
            "BrowsingTracker destroyed"
        )
    }
}