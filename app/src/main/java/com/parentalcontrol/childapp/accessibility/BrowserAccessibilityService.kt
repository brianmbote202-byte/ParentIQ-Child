package com.parentalcontrol.childapp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.parentalcontrol.childapp.detector.BrowserMetadataResolver
import com.parentalcontrol.childapp.service.BrowsingTracker
import com.parentalcontrol.childapp.tracker.NavigationEvent
import com.parentalcontrol.childapp.utils.TitleSanitizer
import com.parentalcontrol.childapp.utils.UrlQueryExtractor
import android.view.KeyEvent
import java.util.Locale

@SuppressLint("AccessibilityService", "AccessibilityPolicy")
class BrowserAccessibilityService : AccessibilityService() {

    companion object {

        private const val TAG = "BrowserAccess"

        // ====================================================
        // SEARCH
        // ====================================================

        private const val SEARCH_DEBOUNCE = 1600L

        private const val SEARCH_REPEAT_COOLDOWN = 5000L

        private const val NATIVE_SEARCH_DUPLICATE_WINDOW = 5_000L


        private const val MIN_SEARCH_LENGTH = 3

        private const val MAX_SEARCH_LENGTH = 200

        private const val PENDING_SEARCH_TIMEOUT = 10_000L

        // ========================================================
// SEARCH EXCLUDED PACKAGES
// ========================================================
//
// Search detection remains universal.
//
// We are NOT maintaining an "allowed apps" list.
// New apps can still be detected.
//
// These packages are excluded because their accessibility
// text is normally system/UI/keyboard text rather than
// the child's actual search query.
//

        private val SEARCH_EXCLUDED_PACKAGES = setOf(

            "com.parentalcontrol.parentapp",
            "com.parentalcontrol.childapp",

            "com.sec.android.app.launcher",

            "com.android.systemui",
            "com.samsung.android.systemui",

            "com.android.settings",
            "com.samsung.android.settings",

            "com.samsung.android.honeyboard",

            "com.google.android.inputmethod.latin",
            "com.android.inputmethod.latin",

            "com.touchtype.swiftkey"
        )

        // ====================================================
        // NAVIGATION
        // ====================================================

        private const val NAVIGATION_COOLDOWN = 1500L

        // ====================================================
        // ACCESSIBILITY TREE
        // ====================================================

        private const val MAX_TREE_DEPTH = 45

        // ====================================================
        // SEARCH CONFIDENCE
        // ====================================================

        private const val MIN_SEARCH_SCORE = 6

        // ====================================================
        // EVENT SEARCH MEMORY
        // ====================================================

        private const val EVENT_QUERY_COOLDOWN = 1200L


    }


    // Last search that was actually saved to Firebase
    private var lastSavedNativeSearchQuery: String? = null
    private var lastSavedNativeSearchPackage: String? = null
    private var lastSavedNativeSearchTime: Long = 0L

    private val prefs by lazy {
        getSharedPreferences("child_prefs", MODE_PRIVATE)
    }

    private var childId: String = ""

    private var browsingTracker: BrowsingTracker? = null

    override fun onKeyEvent(
        event: KeyEvent
    ): Boolean {

        try {

            // We only care about key DOWN.
            if (event.action != KeyEvent.ACTION_DOWN) {
                return false
            }

            val isEnter =
                event.keyCode == KeyEvent.KEYCODE_ENTER ||
                        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

            if (!isEnter) {
                return false
            }

            Log.d(
                TAG,
                "⌨ ENTER detected"
            )

            commitPendingSearch(
                reason = "ENTER"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Key event processing error",
                e
            )
        }

        // IMPORTANT:
        // false allows the Enter key to continue to the target app.
        return false
    }

    // ========================================================
// SEARCH PACKAGE FILTER
// ========================================================

    private fun isSearchExcludedPackage(
        packageName: String
    ): Boolean {

        val pkg =
            packageName
                .trim()
                .lowercase()

        Log.d(
            TAG,
            "🔍 CHECKING SEARCH EXCLUSION"
        )

        Log.d(
            TAG,
            "📦 Original package = [$packageName]"
        )

        Log.d(
            TAG,
            "📦 Normalized package = [$pkg]"
        )

        if (pkg.isBlank()) {

            Log.d(
                TAG,
                "🚫 Excluded because package is blank"
            )

            return true
        }

        // ====================================================
        // EXACT PACKAGE EXCLUSIONS
        // ====================================================

        if (
            SEARCH_EXCLUDED_PACKAGES.contains(pkg)
        ) {

            Log.d(
                TAG,
                "🚫 EXCLUDED BY SEARCH_EXCLUDED_PACKAGES: $pkg"
            )

            return true
        }

        // ====================================================
        // ANDROID KEYBOARDS
        // ====================================================

        if (
            pkg.startsWith("com.android.inputmethod")
        ) {

            Log.d(
                TAG,
                "🚫 Excluded Android keyboard: $pkg"
            )

            return true
        }

        if (
            pkg.startsWith("com.google.android.inputmethod")
        ) {

            Log.d(
                TAG,
                "🚫 Excluded Google keyboard: $pkg"
            )

            return true
        }

        if (
            pkg.contains("honeyboard")
        ) {

            Log.d(
                TAG,
                "🚫 Excluded Samsung keyboard: $pkg"
            )

            return true
        }

        if (
            pkg.contains("swiftkey")
        ) {

            Log.d(
                TAG,
                "🚫 Excluded SwiftKey: $pkg"
            )

            return true
        }

        Log.d(
            TAG,
            "✅ PACKAGE ALLOWED FOR NATIVE SEARCH: $pkg"
        )

        return false
    }
    // ========================================================
    // CORE
    // ========================================================

    private lateinit var metadataResolver: BrowserMetadataResolver



    // ========================================================
    // BROWSERS
    // ========================================================

    private val browserApps = setOf(

        "com.android.chrome",

        "org.mozilla.firefox",

        "com.microsoft.emmx",

        "com.sec.android.app.sbrowser",

        "com.opera.browser",

        "com.brave.browser",

        "com.vivaldi.browser",

        "com.duckduckgo.mobile.android",

        "com.kiwibrowser.browser",

        "com.microsoft.bing",

        // Bing
        "com.microsoft.bing"

    )

    // ========================================================
    // KNOWN NATIVE SEARCH APPS
    //
    // These are confidence hints only.
    //
    // Search detection does NOT require the package
    // to be present here.
    // ========================================================

    private val knownNativeSearchApps = setOf(

        // YouTube
        "com.google.android.youtube",

        // Instagram
        "com.instagram.android",

        // TikTok
        "com.zhiliaoapp.musically",
        "com.ss.android.ugc.trill",

        // Facebook
        "com.facebook.katana",
        "com.facebook.lite",

        // X
        "com.twitter.android"

    )

    // ========================================================
    // NAVIGATION DEDUPLICATION
    // ========================================================

    private var lastDispatchedKey: String? = null

    private var lastDispatchTime = 0L

    // ========================================================
    // SEARCH DEBOUNCE
    // ========================================================

    private val searchHandler =
        Handler(Looper.getMainLooper())

    private var pendingSearchRunnable: Runnable? = null

    private var lastSearchQuery = ""

    private var lastSearchPackage = ""

    private var lastSearchSavedTime = 0L

    // ========================================================
    // SEARCH CANDIDATE MEMORY
    // ========================================================

    // ========================================================
// PENDING SEARCH
// ========================================================
//
// Text entered into an editable field is NOT a search yet.
//
// It becomes a search only after a submit action:
// - Enter
// - Search
// - Go
// - Done
// - Search button click
//

    private var pendingSearchQuery = ""
    private var pendingSearchPackage = ""
    private var pendingSearchTime = 0L





    // ========================================================
    // EVENT MEMORY
    // ========================================================

    private var lastEventQuery = ""

    private var lastEventPackage = ""

    private var lastEventQueryTime = 0L

    // ========================================================
    // SERVICE CONNECTED
    // ========================================================

    override fun onServiceConnected() {

        super.onServiceConnected()

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🔥🔥🔥 BROWSER ACCESSIBILITY SERVICE CONNECTED 🔥🔥🔥"
        )

        try {

            // ====================================================
            // 1. CONFIGURE ACCESSIBILITY SERVICE
            // ====================================================

            serviceInfo =
                AccessibilityServiceInfo().apply {

                    eventTypes =
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                                AccessibilityEvent.TYPE_VIEW_CLICKED or
                                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                                AccessibilityEvent.TYPE_VIEW_SCROLLED

                    feedbackType =
                        AccessibilityServiceInfo.FEEDBACK_GENERIC

                    notificationTimeout = 50

                    flags =
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                }

            Log.e(
                TAG,
                "✅ SERVICE INFO CONFIGURED"
            )


            // ====================================================
            // 2. LOAD CHILD ID
            // ====================================================

            childId =
                prefs
                    .getString("child_id", "")
                    ?.trim()
                    .orEmpty()

            Log.e(
                TAG,
                "🔑 Loaded childId = [$childId]"
            )


            // ====================================================
            // 3. VALIDATE CHILD ID
            // ====================================================

            if (childId.isBlank()) {

                Log.e(
                    TAG,
                    "❌ CHILD ID IS EMPTY"
                )

                Log.e(
                    TAG,
                    "❌ BrowsingTracker will NOT be initialized"
                )

                Log.e(
                    TAG,
                    "⚠️ Make sure pairing saves child_id into"
                )

                Log.e(
                    TAG,
                    "⚠️ SharedPreferences: child_prefs"
                )

                Log.e(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ====================================================
            // 4. DESTROY OLD TRACKER
            // ====================================================

            browsingTracker?.let {

                try {

                    it.destroy()

                    Log.d(
                        TAG,
                        "🧹 Previous BrowsingTracker destroyed"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "⚠️ Failed to destroy previous tracker",
                        e
                    )
                }
            }

            browsingTracker = null


            // ====================================================
            // 5. CREATE BROWSING TRACKER
            // ====================================================

            browsingTracker =
                BrowsingTracker(
                    context = applicationContext,
                    childId = childId,
                    accessibilityService = this
                )

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "✅ BROWSING TRACKER INITIALIZED"
            )

            Log.e(
                TAG,
                "📦 childId = [$childId]"
            )

            Log.e(
                TAG,
                "📡 Tracker = ${browsingTracker != null}"
            )

            Log.e(
                TAG,
                "=================================================="
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ BROWSER ACCESSIBILITY INITIALIZATION FAILED"
            )

            Log.e(
                TAG,
                "❌ ${e.message}",
                e
            )

            browsingTracker = null

            Log.e(
                TAG,
                "=================================================="
            )
        }
    }
    //================SAVE THE PENDING SEARCH=============
    // ============================================================
// COMMIT PENDING NATIVE SEARCH
// ============================================================
    private fun commitPendingSearch(
        reason: String
    ) {

        try {

            // ========================================================
            // 0. HARD SUBMISSION GATE
            //
            // IMPORTANT:
            // Accessibility text-change events MUST NEVER save.
            //
            // Only an actual submit action is allowed to reach
            // saveNativeSearch().
            // ========================================================

            val normalizedReason =
                reason
                    .trim()
                    .uppercase(Locale.getDefault())

            val isRealSubmission =
                normalizedReason == "ENTER" ||
                        normalizedReason == "GO" ||
                        normalizedReason == "SUBMIT" ||
                        normalizedReason == "SEARCH_BUTTON" ||
                        normalizedReason == "SEARCH_ICON" ||
                        normalizedReason == "SEARCH_SUBMIT" ||
                        normalizedReason == "VERIFIED_RESULTS_TRANSITION"

            if (!isRealSubmission) {

                Log.d(
                    TAG,
                    "🛑 COMMIT BLOCKED — not a real search submission"
                )

                Log.d(
                    TAG,
                    "📌 Reason = [$reason]"
                )

                Log.d(
                    TAG,
                    "🔎 Pending = [$pendingSearchQuery]"
                )

                return
            }


            // ========================================================
            // 1. COPY PENDING VALUES
            // ========================================================

            val query =
                pendingSearchQuery
                    .trim()

            val packageName =
                pendingSearchPackage
                    .trim()

            val pendingTime =
                pendingSearchTime

            val now =
                System.currentTimeMillis()


            // ========================================================
            // 2. NOTHING PENDING
            // ========================================================

            if (
                query.isBlank() ||
                packageName.isBlank()
            ) {

                Log.d(
                    TAG,
                    "⌨ Real submit detected but no pending search"
                )

                return
            }


            Log.d(
                TAG,
                "=========================================="
            )

            Log.d(
                TAG,
                "🚀 REAL SEARCH SUBMISSION"
            )

            Log.d(
                TAG,
                "📦 Package = [$packageName]"
            )

            Log.d(
                TAG,
                "🔎 Query = [$query]"
            )

            Log.d(
                TAG,
                "📌 Reason = [$reason]"
            )

            Log.d(
                TAG,
                "=========================================="
            )


            // ========================================================
            // 3. EXPIRATION
            // ========================================================

            if (
                pendingTime <= 0L ||
                now - pendingTime > PENDING_SEARCH_TIMEOUT
            ) {

                Log.d(
                    TAG,
                    "⌛ Pending search expired"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 4. CLEAN QUERY
            // ========================================================

            val cleanQuery =
                cleanSearchQuery(query)
                    .trim()

            Log.d(
                TAG,
                "🧹 Clean query = [$cleanQuery]"
            )


            // ========================================================
            // 5. VALIDATION
            // ========================================================

            if (
                !isValidSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Invalid submitted search"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 6. REJECT URL
            // ========================================================

            if (
                looksLikeUrl(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Submitted value looks like URL"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 7. IGNORED SEARCH
            // ========================================================

            if (
                isIgnoredSearch(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Submitted search is ignored"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 8. UI NOISE
            // ========================================================

            if (
                isUiNoiseSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Submitted search is UI noise"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 9. APP / UI NAME
            // ========================================================

            val isKnownNativeApp =
                knownNativeSearchApps.any {
                    it.equals(
                        packageName,
                        ignoreCase = true
                    )
                }

            if (
                !isKnownNativeApp &&
                isLikelyAppName(
                    query = cleanQuery,
                    packageName = packageName
                )
            ) {

                Log.d(
                    TAG,
                    "🚫 Submitted value looks like app/UI name"
                )

                Log.d(
                    TAG,
                    "📦 Package = [$packageName]"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                clearPendingSearch()

                return
            }


            // ========================================================
            // 10. SAVE
            // ========================================================

            Log.d(
                TAG,
                "=========================================="
            )

            Log.d(
                TAG,
                "💾 SAVING CONFIRMED SEARCH"
            )

            Log.d(
                TAG,
                "📦 Package = [$packageName]"
            )

            Log.d(
                TAG,
                "🔎 Query = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "📌 Reason = [$reason]"
            )

            Log.d(
                TAG,
                "=========================================="
            )


            saveNativeSearch(
                query = cleanQuery,
                packageName = packageName,
                reason = reason
            )


            // ========================================================
            // 11. CLEAR
            // ========================================================

            clearPendingSearch()

            Log.d(
                TAG,
                "🧹 Pending native search cleared"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Commit pending search error",
                e
            )

            clearPendingSearch()
        }
    }
// ============================================================
// CLEAR PENDING SEARCH
// ============================================================

    private fun clearPendingSearch() {

        Log.d(
            TAG,
            "🧹 Clearing pending search"
        )

        pendingSearchQuery = ""

        pendingSearchPackage = ""

        pendingSearchTime = 0L
    }
    //==========detect an accessibility click that represents a Search action.====
    private fun isSearchSubmitClick(
        event: AccessibilityEvent
    ): Boolean {

        try {

            // ============================================================
            // 1. MUST HAVE A PENDING SEARCH
            // ============================================================

            val pendingQuery =
                pendingSearchQuery
                    .trim()

            val pendingPackage =
                pendingSearchPackage
                    .trim()

            if (
                pendingQuery.isBlank() ||
                pendingPackage.isBlank()
            ) {
                return false
            }


            // ============================================================
            // 2. PACKAGE MUST MATCH
            // ============================================================

            val eventPackage =
                event.packageName
                    ?.toString()
                    ?.trim()
                    ?: return false

            if (
                !eventPackage.equals(
                    pendingPackage,
                    ignoreCase = true
                )
            ) {
                return false
            }


            // ============================================================
            // 3. PENDING SEARCH MUST STILL BE FRESH
            // ============================================================

            val now =
                System.currentTimeMillis()

            if (
                pendingSearchTime <= 0L ||
                now - pendingSearchTime >
                PENDING_SEARCH_TIMEOUT
            ) {

                Log.d(
                    TAG,
                    "⌛ Pending search expired"
                )

                clearPendingSearch()

                return false
            }


            val eventType =
                event.eventType


            // ============================================================
            // 4. READ EVENT SOURCE
            // ============================================================

            val source =
                event.source


            var text = ""
            var description = ""
            var viewId = ""
            var className = ""


            if (source != null) {

                text =
                    source.text
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?: ""

                description =
                    source.contentDescription
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?: ""

                viewId =
                    source.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                className =
                    source.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""
            }


            // ============================================================
            // 5. DEBUG
            // ============================================================

            Log.d(
                TAG,
                "🔎 SUBMIT CHECK"
            )

            Log.d(
                TAG,
                "📦 Package = $eventPackage"
            )

            Log.d(
                TAG,
                "🔎 Pending = [$pendingQuery]"
            )

            Log.d(
                TAG,
                "📌 Event = $eventType"
            )

            Log.d(
                TAG,
                "📝 Text = [$text]"
            )

            Log.d(
                TAG,
                "💬 Description = [$description]"
            )

            Log.d(
                TAG,
                "🆔 ViewId = [$viewId]"
            )

            Log.d(
                TAG,
                "🏷 Class = [$className]"
            )


            // ============================================================
            // 6. STRONG SEARCH BUTTON IDENTIFICATION
            // ============================================================

            val searchText =
                text == "search" ||
                        text == "go" ||
                        text == "done" ||
                        text == "submit" ||
                        text == "enter"

            val searchDescription =
                description == "search" ||
                        description.contains("search button") ||
                        description.contains("submit search") ||
                        description.contains("perform search") ||
                        description.contains("search icon") ||
                        description.contains("search")

            val searchViewId =
                viewId.contains("search") ||
                        viewId.contains("submit") ||
                        viewId.contains("query")


            val explicitSearchControl =
                searchText ||
                        searchDescription ||
                        searchViewId


            // ============================================================
            // 7. ACTUAL SEARCH BUTTON CLICK
            //
            // This is the strongest confirmation.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_CLICKED &&
                explicitSearchControl
            ) {

                Log.d(
                    TAG,
                    "🚀 REAL SEARCH BUTTON CLICK"
                )

                return true
            }


            // ============================================================
            // 8. KEYBOARD / IME SEARCH ACTION
            //
            // Some apps expose the keyboard action as a click/focus
            // around the search field rather than the search button.
            //
            // We only accept this when the source clearly represents
            // a search control.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_CLICKED &&
                (
                        className.contains("button") ||
                                className.contains("imagebutton")
                        ) &&
                explicitSearchControl
            ) {

                Log.d(
                    TAG,
                    "🚀 SEARCH BUTTON CLASS CLICK"
                )

                return true
            }


            // ============================================================
            // 9. DO NOT TREAT TEXT CHANGES AS SUBMISSION
            //
            // VERY IMPORTANT:
            //
            // b
            // bi
            // bit
            // bitc
            // bitco
            // bitcoin
            //
            // must NEVER submit here.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {

                Log.d(
                    TAG,
                    "⌨ Text changed = typing only"
                )

                return false
            }


            // ============================================================
            // 10. DO NOT TREAT FOCUS AS SUBMISSION
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            ) {

                Log.d(
                    TAG,
                    "🎯 Focus event = not submission"
                )

                return false
            }


            // ============================================================
            // 11. WINDOW CONTENT CHANGE
            //
            // NEVER automatically submit merely because content changed.
            //
            // The separate confirmPendingSearchFromUi() logic in
            // onAccessibilityEvent() can determine whether a genuine
            // result transition occurred.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {

                Log.d(
                    TAG,
                    "🔄 Content changed = NOT automatically submitted"
                )

                return false
            }


            // ============================================================
            // 12. WINDOW STATE CHANGE
            //
            // Also do NOT automatically submit.
            //
            // A real result transition should be confirmed separately.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            ) {

                Log.d(
                    TAG,
                    "🪟 Window state changed = not automatically submitted"
                )

                return false
            }


        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Search submit detection error",
                e
            )
        }


        return false
    }
    //=============native save search method=====
    private fun saveNativeSearch(
        query: String,
        packageName: String,
        reason: String
    ) {

        try {

            // ========================================================
            // 1. START
            // ========================================================

            Log.d(
                TAG,
                "=================================================="
            )

            Log.d(
                TAG,
                "🔥 saveNativeSearch() STARTED"
            )

            Log.d(
                TAG,
                "📦 Package      = [$packageName]"
            )

            Log.d(
                TAG,
                "🔎 Original     = [$query]"
            )

            Log.d(
                TAG,
                "📌 Reason       = [$reason]"
            )


            // ========================================================
            // 2. CLEAN PACKAGE
            // ========================================================

            val cleanPackage =
                packageName
                    .trim()


            if (cleanPackage.isBlank()) {

                Log.e(
                    TAG,
                    "❌ Native search rejected: EMPTY PACKAGE"
                )

                Log.e(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 3. CLEAN QUERY
            // ========================================================

            val cleanQuery =
                cleanSearchQuery(query)
                    .trim()


            Log.d(
                TAG,
                "🧹 Clean query = [$cleanQuery]"
            )


            // ========================================================
            // 4. BASIC VALIDATION
            // ========================================================

            if (cleanQuery.isBlank()) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: blank query"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            if (
                !isValidSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: invalid query"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 5. REJECT URL
            // ========================================================

            if (
                looksLikeUrl(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: URL"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 6. REJECT IGNORED SEARCH
            // ========================================================

            if (
                isIgnoredSearch(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: ignored query"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 7. REJECT UI NOISE
            // ========================================================

            if (
                isUiNoiseSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: UI noise"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 8. REJECT APP / UI NAME
            // ========================================================

            if (
                isLikelyAppName(
                    query = cleanQuery,
                    packageName = cleanPackage
                )
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected: likely app/UI name"
                )

                Log.d(
                    TAG,
                    "📦 Package = [$cleanPackage]"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 9. IMPORTANT:
            // DO NOT PERFORM ANOTHER DUPLICATE CHECK HERE
            //
            // commitPendingSearch() already performs:
            //
            // isDuplicateNativeSearch(...)
            //
            // Doing another duplicate check here can cause a valid
            // search to disappear before it reaches BrowsingTracker.
            // ========================================================

            Log.d(
                TAG,
                "✅ Query passed saveNativeSearch validation"
            )

            Log.d(
                TAG,
                "🔎 Query = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "📦 Package = [$cleanPackage]"
            )


            // ========================================================
            // 10. CHECK BROWSING TRACKER
            // ========================================================

            Log.d(
                TAG,
                "🔍 Checking BrowsingTracker instance..."
            )

            val tracker =
                browsingTracker


            Log.d(
                TAG,
                "📡 BrowsingTracker available = ${tracker != null}"
            )


            if (tracker == null) {

                Log.e(
                    TAG,
                    "=================================================="
                )

                Log.e(
                    TAG,
                    "❌ CANNOT SAVE NATIVE SEARCH"
                )

                Log.e(
                    TAG,
                    "❌ browsingTracker == null"
                )

                Log.e(
                    TAG,
                    "📦 Package = [$cleanPackage]"
                )

                Log.e(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.e(
                    TAG,
                    "📌 Reason = [$reason]"
                )

                Log.e(
                    TAG,
                    "⚠️ Firebase write was NOT attempted"
                )

                Log.e(
                    TAG,
                    "⚠️ BrowsingTracker initialization must be checked"
                )

                Log.e(
                    TAG,
                    "=================================================="
                )

                return
            }


            // ========================================================
            // 11. TRACKER AVAILABLE
            // ========================================================

            Log.d(
                TAG,
                "=================================================="
            )

            Log.d(
                TAG,
                "📡 BROWSING TRACKER AVAILABLE"
            )

            Log.d(
                TAG,
                "📦 Package = [$cleanPackage]"
            )

            Log.d(
                TAG,
                "🔎 Query   = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "🌐 Source  = [native_app]"
            )

            Log.d(
                TAG,
                "📌 Reason  = [$reason]"
            )

            Log.d(
                TAG,
                "=================================================="
            )


            // ========================================================
            // 12. DISPATCH TO BROWSING TRACKER
            // ========================================================

            Log.d(
                TAG,
                "🚀 CALLING BrowsingTracker.onSearchDetected()"
            )

            Log.d(
                TAG,
                "--------------------------------------------------"
            )

            Log.d(
                TAG,
                "➡️ query       = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "➡️ packageName = [$cleanPackage]"
            )

            Log.d(
                TAG,
                "➡️ source      = [native_app]"
            )

            Log.d(
                TAG,
                "--------------------------------------------------"
            )


            tracker.onSearchDetected(
                query = cleanQuery,
                packageName = cleanPackage,
                source = "native_app"
            )


            // ========================================================
            // 13. METHOD RETURNED
            //
            // VERY IMPORTANT:
            //
            // This confirms that onSearchDetected() itself did not
            // throw an exception.
            //
            // It does NOT yet prove Firebase was written.
            // The Firebase confirmation must come from
            // BrowsingTracker.onSearchDetected()/saveSearchQuery().
            // ========================================================

            Log.d(
                TAG,
                "=================================================="
            )

            Log.d(
                TAG,
                "✅ BrowsingTracker.onSearchDetected() RETURNED"
            )

            Log.d(
                TAG,
                "📦 Package = [$cleanPackage]"
            )

            Log.d(
                TAG,
                "🔎 Query   = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "📌 Reason  = [$reason]"
            )

            Log.d(
                TAG,
                "⚠️ Firebase success must be confirmed by"
            )

            Log.d(
                TAG,
                "⚠️ BrowsingTracker's Firebase write callback"
            )

            Log.d(
                TAG,
                "=================================================="
            )


        } catch (e: Exception) {

            // ========================================================
            // 14. EXCEPTION
            // ========================================================

            Log.e(
                TAG,
                "=================================================="
            )

            Log.e(
                TAG,
                "❌ saveNativeSearch() EXCEPTION"
            )

            Log.e(
                TAG,
                "📦 Package = [$packageName]"
            )

            Log.e(
                TAG,
                "🔎 Query   = [$query]"
            )

            Log.e(
                TAG,
                "📌 Reason  = [$reason]"
            )

            Log.e(
                TAG,
                "❌ Error   = ${e.message}"
            )

            Log.e(
                TAG,
                "❌ Exception type = ${e.javaClass.name}",
                e
            )

            Log.e(
                TAG,
                "=================================================="
            )
        }
    }
    // ========================================================
    // ACCESSIBILITY EVENT
    // ========================================================

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {

        // ============================================================
        // 0. ABSOLUTE RAW EVENT LOG
        // ============================================================

        if (event == null) {
            Log.d(TAG, "⏭ Accessibility event = null")
            return
        }

        Log.d(
            "BROWSER_ACCESSIBILITY_TEST",
            "EVENT package=${event.packageName}, type=${event.eventType}"
        )

        try {

            // ========================================================
            // 1. BASIC EVENT VALIDATION
            // ========================================================

            val packageName =
                event.packageName
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: run {

                        Log.d(
                            TAG,
                            "⏭ Event has no package name"
                        )

                        return
                    }

            if (packageName.isBlank()) {
                Log.d(
                    TAG,
                    "⏭ Blank package name"
                )

                return
            }

            val eventType = event.eventType

            // ========================================================
            // 2. ONLY PROCESS EVENTS THAT CAN ACTUALLY HELP US
            //
            // This prevents unnecessary work from accessibility noise.
            // ========================================================

            val relevantEvent =
                when (eventType) {

                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_CLICKED,
                    AccessibilityEvent.TYPE_VIEW_FOCUSED,
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                    AccessibilityEvent.TYPE_VIEW_SCROLLED,
                    AccessibilityEvent.TYPE_VIEW_SELECTED -> true

                    else -> false
                }

            if (!relevantEvent) {

                Log.d(
                    TAG,
                    "⏭ Irrelevant accessibility event: $eventType"
                )

                return
            }

            // ========================================================
            // 3. EVENT DEBUG
            // ========================================================

            Log.d(
                TAG,
                "=========================================="
            )

            Log.d(
                TAG,
                "🔥 ACCESSIBILITY EVENT"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "📌 Type = $eventType"
            )

            Log.d(
                TAG,
                "📝 Text = ${event.text}"
            )

            Log.d(
                TAG,
                "🔤 Class = ${event.className}"
            )

            Log.d(
                TAG,
                "=========================================="
            )

            // ========================================================
            // 4. DETERMINE PACKAGE TYPE
            // ========================================================

            val excluded =
                isSearchExcludedPackage(packageName)

            val browser =
                isBrowserPackage(packageName)

            Log.d(
                TAG,
                "🔍 Search excluded = $excluded"
            )

            Log.d(
                TAG,
                "🌐 Browser package = $browser"
            )

            // ========================================================
            // 5. GET ACTIVE WINDOW
            //
            // Do this once.
            // ========================================================

            val root =
                try {

                    rootInActiveWindow

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Failed to obtain rootInActiveWindow",
                        e
                    )

                    null
                }

            // ========================================================
            // 6. SEARCH SUBMISSION
            //
            // Check this BEFORE attempting to create a new candidate.
            // ========================================================

            val explicitSubmit =
                try {

                    isSearchSubmitClick(event)

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ isSearchSubmitClick() failed",
                        e
                    )

                    false
                }

            Log.d(
                TAG,
                "🧪 Explicit submit = $explicitSubmit"
            )

            if (explicitSubmit) {

                val pendingQuery =
                    pendingSearchQuery.trim()

                val pendingPackage =
                    pendingSearchPackage.trim()

                Log.d(
                    TAG,
                    "🚀 SEARCH SUBMISSION EVENT"
                )

                Log.d(
                    TAG,
                    "📦 Current package = $packageName"
                )

                Log.d(
                    TAG,
                    "📦 Pending package = $pendingPackage"
                )

                Log.d(
                    TAG,
                    "🔎 Pending query = [$pendingQuery]"
                )

                if (
                    pendingQuery.isNotBlank() &&
                    pendingPackage.isNotBlank() &&
                    pendingPackage.equals(
                        packageName,
                        ignoreCase = true
                    )
                ) {

                    Log.d(
                        TAG,
                        "✅ Matching pending search found"

                    )

                    commitPendingSearch(
                        reason = "SEARCH_SUBMIT"
                    )

                    // IMPORTANT:
                    // Do NOT allow this same accessibility event
                    // to go through native-search detection again.
                    Log.d(
                        TAG,
                        "🛑 Submit event consumed"
                    )

                    return
                }

                Log.d(
                    TAG,
                    "⚠️ Submit event has no matching pending search"
                )
            }
            // ========================================================
            // 7. NATIVE / APP SEARCH DETECTION
            //
            // IMPORTANT:
            //
            // Browsers are intentionally NOT excluded here.
            //
            // The only package exclusion is SEARCH_EXCLUDED_PACKAGES.
            // ========================================================

            if (excluded) {

                Log.d(
                    TAG,
                    "🔕 Search detection excluded for $packageName"
                )

            } else if (root == null) {

                Log.d(
                    TAG,
                    "⏭ No accessibility root available"
                )

            } else {

                Log.d(
                    TAG,
                    "🚀 Starting generic search detection"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🌐 Browser = $browser"
                )

                try {

                    detectNativeSearch(
                        root = root,
                        packageName = packageName,
                        event = event
                    )

                    Log.d(
                        TAG,
                        "✅ detectNativeSearch() completed"
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ detectNativeSearch() failed",
                        e
                    )
                }
            }
            // ========================================================
// 8. PENDING SEARCH / RESULTS TRANSITION
//
// We allow the UI confirmation function to inspect:
//
//   TYPE_WINDOW_STATE_CHANGED
//   TYPE_WINDOW_CONTENT_CHANGED
//
// BUT:
//
// TYPE_VIEW_SCROLLED is NOT sent to confirmation.
//
// The confirmation function itself decides whether the
// content change contains enough evidence to represent a
// real search result transition.
//
// This is important for apps such as TikTok Lite where
// search results may appear through WINDOW_CONTENT_CHANGED
// without generating WINDOW_STATE_CHANGED.
// ========================================================

            val currentPendingQuery =
                pendingSearchQuery.trim()

            val currentPendingPackage =
                pendingSearchPackage.trim()

            if (
                currentPendingQuery.isNotBlank() &&
                currentPendingPackage.isNotBlank() &&
                currentPendingPackage.equals(
                    packageName,
                    ignoreCase = true
                )
            ) {

                val pendingAge =
                    if (pendingSearchTime > 0L) {

                        System.currentTimeMillis() -
                                pendingSearchTime

                    } else {

                        Long.MAX_VALUE
                    }

                Log.d(
                    TAG,
                    "⏳ PENDING SEARCH"
                )

                Log.d(
                    TAG,
                    "📦 Package = $currentPendingPackage"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$currentPendingQuery]"
                )

                Log.d(
                    TAG,
                    "⏱ Age = ${pendingAge}ms"
                )


                // ========================================================
                // EXPIRED
                // ========================================================

                if (
                    pendingAge >
                    PENDING_SEARCH_TIMEOUT
                ) {

                    Log.d(
                        TAG,
                        "⌛ Pending search expired"
                    )

                    clearPendingSearch()

                } else {

                    // ====================================================
                    // ONLY THESE EVENTS CAN ENTER RESULT CONFIRMATION
                    //
                    // DO NOT include TYPE_VIEW_SCROLLED.
                    //
                    // DO NOT include TYPE_VIEW_CLICKED.
                    // ====================================================

                    val canCheckForResultTransition =
                        eventType ==
                                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                                eventType ==
                                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED


                    if (
                        canCheckForResultTransition &&
                        root != null &&
                        pendingAge >= 700L
                    ) {

                        Log.d(
                            TAG,
                            "🔎 Checking verified search results transition"
                        )

                        Log.d(
                            TAG,
                            "📌 Event = $eventType"
                        )

                        Log.d(
                            TAG,
                            "🔎 Query = [$currentPendingQuery]"
                        )

                        val confirmed =
                            try {

                                confirmPendingSearchFromUi(
                                    root = root,
                                    packageName = packageName,
                                    event = event
                                )

                            } catch (e: Exception) {

                                Log.e(
                                    TAG,
                                    "❌ Search UI confirmation failed",
                                    e
                                )

                                false
                            }


                        if (confirmed) {

                            Log.d(
                                TAG,
                                "🚀 VERIFIED SEARCH RESULTS TRANSITION"
                            )

                            Log.d(
                                TAG,
                                "📦 Package = $packageName"
                            )

                            Log.d(
                                TAG,
                                "🔎 Query = [$currentPendingQuery]"
                            )

                            Log.d(
                                TAG,
                                "📌 Event = $eventType"
                            )

                            commitPendingSearch(
                                reason = "VERIFIED_RESULTS_TRANSITION"
                            )

                        } else {

                            Log.d(
                                TAG,
                                "⏳ UI changed but search submission was NOT verified"
                            )
                        }

                    } else {

                        if (
                            eventType ==
                            AccessibilityEvent.TYPE_VIEW_SCROLLED
                        ) {

                            Log.d(
                                TAG,
                                "📜 Scroll event = NOT used for search confirmation"
                            )

                        } else {

                            Log.d(
                                TAG,
                                "⏳ Event not eligible for result confirmation"
                            )
                        }
                    }
                }
            }

            // ========================================================
            // 9. URL DETECTION
            //
            // Only meaningful for browser-like packages.
            //
            // This prevents trying to extract URLs from every native
            // application.
            // ========================================================

            if (!browser) {

                Log.d(
                    TAG,
                    "⏭ Not a browser: skipping browser URL extraction"
                )

                return
            }

            if (root == null) {

                Log.d(
                    TAG,
                    "⏭ No root available for browser URL"
                )

                return
            }

            // ========================================================
            // 10. EXTRACT URL
            // ========================================================

            val rawUrl =
                try {

                    extractBrowserUrl(
                        root = root,
                        packageName = packageName
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Browser URL extraction failed",
                        e
                    )

                    null
                }

            if (rawUrl.isNullOrBlank()) {

                Log.d(
                    TAG,
                    "⏭ No browser URL detected"
                )

                return
            }

            val cleanUrl =
                rawUrl.trim()

            if (cleanUrl.length < 5) {

                return
            }

            Log.d(
                TAG,
                "🌐 BROWSER URL DETECTED"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "🔗 URL = $cleanUrl"
            )

            // ========================================================
            // 11. URL DEDUPLICATION
            // ========================================================

            val dispatchKey =
                buildDispatchKey(
                    cleanUrl
                )

            val now =
                System.currentTimeMillis()

            val duplicateNavigation =
                dispatchKey == lastDispatchedKey &&
                        now - lastDispatchTime <
                        NAVIGATION_COOLDOWN &&
                        !isBlockedDomainUrl(cleanUrl)

            if (duplicateNavigation) {

                Log.d(
                    TAG,
                    "🔁 Duplicate browser navigation ignored"
                )

                return
            }

            lastDispatchedKey =
                dispatchKey

            lastDispatchTime =
                now

            // ========================================================
            // 12. TITLE
            // ========================================================

            val title =
                try {

                    metadataResolver.extractFastTitle(
                        root
                    )

                } catch (e: Exception) {

                    Log.d(
                        TAG,
                        "⚠️ Fast title extraction failed: ${e.message}"
                    )

                    null
                }

            // ========================================================
            // 13. DISPATCH URL
            // ========================================================

            Log.d(
                TAG,
                "🚀 DISPATCHING NAVIGATION"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "🔗 URL = $cleanUrl"
            )

            Log.d(
                TAG,
                "🏷 Title = $title"
            )

            dispatchNavigation(
                url = cleanUrl,
                packageName = packageName,
                root = root,
                title = title
            )

            Log.d(
                TAG,
                "✅ Navigation dispatched"

            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "=========================================="
            )

            Log.e(
                TAG,
                "❌ ACCESSIBILITY EVENT ERROR",
                e
            )

            Log.e(
                TAG,
                "❌ Package = ${event.packageName}"
            )

            Log.e(
                TAG,
                "❌ Type = ${event.eventType}"
            )

            Log.e(
                TAG,
                "❌ Message = ${e.message}"
            )

            Log.e(
                TAG,
                "=========================================="
            )
        }
    }
    //==============PENDING SEARCH===========
    private fun confirmPendingSearchFromUi(
        root: AccessibilityNodeInfo,
        packageName: String,
        event: AccessibilityEvent
    ): Boolean {

        try {

            // =====================================================
            // 1. VERIFY PENDING SEARCH
            // =====================================================

            val pendingQuery =
                pendingSearchQuery
                    .trim()

            val pendingPackage =
                pendingSearchPackage
                    .trim()

            if (pendingQuery.isBlank()) {

                Log.d(
                    TAG,
                    "⏭ CONFIRM: no pending query"
                )

                return false
            }

            if (pendingPackage.isBlank()) {

                Log.d(
                    TAG,
                    "⏭ CONFIRM: no pending package"
                )

                return false
            }

            if (
                !pendingPackage.equals(
                    packageName,
                    ignoreCase = true
                )
            ) {

                Log.d(
                    TAG,
                    "⏭ CONFIRM: package mismatch"
                )

                Log.d(
                    TAG,
                    "Pending package = $pendingPackage"
                )

                Log.d(
                    TAG,
                    "Current package = $packageName"
                )

                return false
            }


            // =====================================================
            // 2. VERIFY PENDING TIME
            // =====================================================

            val now =
                System.currentTimeMillis()

            if (pendingSearchTime <= 0L) {

                Log.d(
                    TAG,
                    "⏭ CONFIRM: invalid pendingSearchTime"
                )

                return false
            }

            val pendingAge =
                now - pendingSearchTime


            if (pendingAge < 300L) {

                Log.d(
                    TAG,
                    "⏳ CONFIRM: pending search too new"
                )

                Log.d(
                    TAG,
                    "Query = [$pendingQuery]"
                )

                Log.d(
                    TAG,
                    "Age = ${pendingAge}ms"
                )

                return false
            }


            if (
                pendingAge >
                PENDING_SEARCH_TIMEOUT
            ) {

                Log.d(
                    TAG,
                    "⌛ CONFIRM: pending search expired"
                )

                Log.d(
                    TAG,
                    "Query = [$pendingQuery]"
                )

                Log.d(
                    TAG,
                    "Age = ${pendingAge}ms"
                )

                clearPendingSearch()

                return false
            }


            // =====================================================
            // 3. NEVER CONFIRM TEXT CHANGES
            //
            // This is critical.
            //
            // bitcoin
            // bitcoin p
            // bitcoin pr
            // bitcoin price
            //
            // must remain pending.
            // =====================================================

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {

                Log.d(
                    TAG,
                    "⌨ CONFIRM: text changed = still typing"
                )

                Log.d(
                    TAG,
                    "Query = [$pendingQuery]"
                )

                return false
            }


            // =====================================================
            // 4. NORMALIZE QUERY
            // =====================================================

            val normalizedQuery =
                pendingQuery
                    .lowercase()
                    .trim()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )


            // =====================================================
            // 5. EVENT TEXT
            // =====================================================

            val eventText =
                event.text
                    ?.joinToString(" ")
                    ?.trim()
                    ?.lowercase()
                    ?.replace(
                        Regex("\\s+"),
                        " "
                    )
                    ?: ""

            val eventDescription =
                event.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?.replace(
                        Regex("\\s+"),
                        " "
                    )
                    ?: ""


            // =====================================================
            // 6. SOURCE INFORMATION
            // =====================================================

            val source =
                try {

                    event.source

                } catch (
                    _: Exception
                ) {

                    null
                }


            val sourceText =
                source
                    ?.text
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: ""


            val sourceDescription =
                source
                    ?.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: ""


            val sourceId =
                source
                    ?.viewIdResourceName
                    ?.lowercase()
                    ?: ""


            val sourceClass =
                source
                    ?.className
                    ?.toString()
                    ?.lowercase()
                    ?: ""


            // =====================================================
            // 7. DEBUG
            // =====================================================

            Log.d(
                TAG,
                "=========================================="
            )

            Log.d(
                TAG,
                "🔎 SUBMIT CONFIRMATION CHECK"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "🔎 Pending = [$pendingQuery]"
            )

            Log.d(
                TAG,
                "📌 Event = ${event.eventType}"
            )

            Log.d(
                TAG,
                "📝 Event text = [$eventText]"
            )

            Log.d(
                TAG,
                "💬 Description = [$eventDescription]"
            )

            Log.d(
                TAG,
                "📝 Source text = [$sourceText]"
            )

            Log.d(
                TAG,
                "💬 Source description = [$sourceDescription]"
            )

            Log.d(
                TAG,
                "🆔 Source ID = [$sourceId]"
            )

            Log.d(
                TAG,
                "🏷 Source class = [$sourceClass]"
            )

            Log.d(
                TAG,
                "⏱ Pending age = ${pendingAge}ms"
            )


            // =====================================================
            // 8. EXPLICIT SEARCH CONTROL
            //
            // Strongest signal.
            // =====================================================

            val textLooksLikeSearch =
                sourceText == "search" ||
                        sourceText == "go" ||
                        sourceText == "done" ||
                        sourceText == "submit" ||
                        sourceText == "enter"

            val descriptionLooksLikeSearch =
                sourceDescription == "search" ||
                        sourceDescription.contains("search button") ||
                        sourceDescription.contains("submit search") ||
                        sourceDescription.contains("perform search") ||
                        sourceDescription.contains("search")

            val idLooksLikeSearch =
                sourceId.contains("search") ||
                        sourceId.contains("submit") ||
                        sourceId.contains("query") ||
                        sourceId.contains("go")

            val explicitSearchControl =
                textLooksLikeSearch ||
                        descriptionLooksLikeSearch ||
                        idLooksLikeSearch


            Log.d(
                TAG,
                "🧪 Explicit submit = $explicitSearchControl"
            )


            // =====================================================
            // 9. REAL SEARCH BUTTON CLICK
            // =====================================================

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_VIEW_CLICKED &&
                explicitSearchControl
            ) {

                Log.d(
                    TAG,
                    "🚀 SEARCH CONFIRMED"
                )

                Log.d(
                    TAG,
                    "📌 Reason = explicit search control clicked"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$pendingQuery]"
                )

                return true
            }


            // =====================================================
            // 10. QUERY APPEARS IN RESULT EVENT
            //
            // This is strong evidence that the submitted query has
            // propagated into the new UI.
            // =====================================================

            val queryAppearedInEvent =
                eventText.contains(
                    normalizedQuery
                ) ||
                        eventDescription.contains(
                            normalizedQuery
                        )


            if (
                queryAppearedInEvent &&
                event.eventType !=
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {

                if (
                    event.eventType ==
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    event.eventType ==
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
                ) {

                    Log.d(
                        TAG,
                        "🚀 SEARCH CONFIRMED"
                    )

                    Log.d(
                        TAG,
                        "📌 Reason = query appeared after UI transition"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$pendingQuery]"
                    )

                    return true
                }
            }


            // =====================================================
            // 11. CHECK WHETHER QUERY EXISTS IN CURRENT UI
            //
            // IMPORTANT:
            //
            // Finding the query alone does NOT submit it.
            //
            // We use this to determine whether the results screen
            // is displaying the submitted query.
            // =====================================================

            var queryFoundInUi = false

            var searchFieldStillFocused = false

            var resultLikeNodeFound = false


            fun scanUi(
                node: AccessibilityNodeInfo?,
                depth: Int = 0
            ) {

                if (
                    node == null ||
                    depth > MAX_TREE_DEPTH ||
                    queryFoundInUi && resultLikeNodeFound
                ) {

                    return
                }


                val nodeText =
                    node.text
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?.replace(
                            Regex("\\s+"),
                            " "
                        )
                        ?: ""


                val nodeDescription =
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?.lowercase()
                        ?.replace(
                            Regex("\\s+"),
                            " "
                        )
                        ?: ""


                val nodeId =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""


                val nodeClass =
                    node.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""


                // -------------------------------------------------
                // QUERY FOUND
                // -------------------------------------------------

                if (
                    nodeText.contains(
                        normalizedQuery
                    ) ||
                    nodeDescription.contains(
                        normalizedQuery
                    )
                ) {

                    queryFoundInUi = true
                }


                // -------------------------------------------------
                // SEARCH FIELD STILL FOCUSED
                // -------------------------------------------------

                if (
                    node.isFocused &&
                    (
                            nodeClass.contains("edittext") ||
                                    nodeClass.contains("textfield") ||
                                    nodeId.contains("search") ||
                                    nodeId.contains("query")
                            )
                ) {

                    searchFieldStillFocused = true
                }


                // -------------------------------------------------
                // RESULT-LIKE UI
                //
                // We deliberately keep this broad enough for native
                // apps such as TikTok Lite, Instagram, YouTube, etc.
                // -------------------------------------------------

                if (
                    nodeId.contains("result") ||
                    nodeId.contains("content") ||
                    nodeId.contains("feed") ||
                    nodeId.contains("item") ||
                    nodeDescription.contains("result")
                ) {

                    resultLikeNodeFound = true
                }


                // -------------------------------------------------
                // CHILDREN
                // -------------------------------------------------

                for (
                i in 0 until node.childCount
                ) {

                    val child =
                        try {

                            node.getChild(i)

                        } catch (
                            _: Exception
                        ) {

                            null
                        }


                    scanUi(
                        node = child,
                        depth = depth + 1
                    )


                    try {

                        child?.recycle()

                    } catch (
                        _: Exception
                    ) {
                    }


                    if (
                        queryFoundInUi &&
                        resultLikeNodeFound
                    ) {

                        break
                    }
                }
            }


            scanUi(root)


            Log.d(
                TAG,
                "🔍 Query found in UI = $queryFoundInUi"
            )

            Log.d(
                TAG,
                "⌨ Search field still focused = $searchFieldStillFocused"
            )

            Log.d(
                TAG,
                "📱 Result-like UI found = $resultLikeNodeFound"
            )

            // =====================================================
// 12. VERIFIED RESULT SCREEN EVIDENCE
//
// Accept:
//   1. WINDOW_STATE_CHANGED
//   2. WINDOW_CONTENT_CHANGED when strong evidence exists
//
// SCROLL is kept separate for the known-native-app fallback
// below.
// =====================================================

            val uiTransition =
                event.eventType ==
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

            val scrollResultTransition =
                event.eventType ==
                        AccessibilityEvent.TYPE_VIEW_SCROLLED &&
                        pendingAge >= 700L &&
                        !searchFieldStillFocused

            val contentChangedResultTransition =
                event.eventType ==
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                        queryFoundInUi &&
                        !searchFieldStillFocused &&
                        resultLikeNodeFound


            if (
                (
                        uiTransition ||
                                contentChangedResultTransition
                        ) &&
                queryFoundInUi &&
                !searchFieldStillFocused &&
                resultLikeNodeFound &&
                pendingAge >= 700L
            ) {

                Log.d(
                    TAG,
                    "🚀 SEARCH CONFIRMED"
                )

                Log.d(
                    TAG,
                    "📌 Reason = verified native-app result transition"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$pendingQuery]"
                )

                Log.d(
                    TAG,
                    "🔍 Query found in UI = $queryFoundInUi"
                )

                Log.d(
                    TAG,
                    "⌨ Search field still focused = $searchFieldStillFocused"
                )

                Log.d(
                    TAG,
                    "📱 Result-like UI found = $resultLikeNodeFound"
                )

                Log.d(
                    TAG,
                    "🪟 Window state transition = $uiTransition"
                )

                Log.d(
                    TAG,
                    "🔄 Content changed result transition = $contentChangedResultTransition"
                )

                Log.d(
                    TAG,
                    "📌 Event = ${event.eventType}"
                )

                Log.d(
                    TAG,
                    "⏱ Age = ${pendingAge}ms"
                )

                return true
            }

            // =====================================================
            // 13. KNOWN NATIVE APP FALLBACK
            //
            // TikTok Lite and some other apps may expose almost no
            // useful accessibility information.
            //
            // For those apps, a WINDOW_STATE_CHANGED or SCROLLED
            // event after a reasonable delay is the fallback.
            //
            // NEVER use WINDOW_CONTENT_CHANGED alone.
            // =====================================================

            val isKnownNativeApp =
                knownNativeSearchApps.any {
                    it.equals(
                        packageName,
                        ignoreCase = true
                    )
                }


            if (
                isKnownNativeApp &&
                (
                        uiTransition ||
                                scrollResultTransition
                        ) &&
                pendingAge >= 700L &&
                !searchFieldStillFocused
            ) {

                Log.d(
                    TAG,
                    "🚀 SEARCH CONFIRMED"
                )

                Log.d(
                    TAG,
                    "📌 Reason = native app result transition"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$pendingQuery]"
                )

                Log.d(
                    TAG,
                    "📌 Event = ${event.eventType}"
                )

                Log.d(
                    TAG,
                    "⌨ Search field still focused = $searchFieldStillFocused"
                )

                Log.d(
                    TAG,
                    "⏱ Age = ${pendingAge}ms"
                )

                return true
            }


            // =====================================================
            // 14. WINDOW CONTENT CHANGED
            //
            // NEVER submit solely because this event happened.
            // =====================================================

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            ) {

                Log.d(
                    TAG,
                    "🔄 Window content changed"
                )

                Log.d(
                    TAG,
                    "⏳ NOT enough evidence to submit"
                )

                return false
            }


            // =====================================================
            // 15. CLICK WITHOUT SEARCH CONTROL
            // =====================================================

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {

                Log.d(
                    TAG,
                    "🖱 Click detected"
                )

                Log.d(
                    TAG,
                    "⏳ Click is not identified as Search"
                )

                return false
            }


            // =====================================================
            // 16. FOCUS
            // =====================================================

            if (
                event.eventType ==
                AccessibilityEvent.TYPE_VIEW_FOCUSED
            ) {

                Log.d(
                    TAG,
                    "🎯 Focus event"
                )

                Log.d(
                    TAG,
                    "⏳ Search remains pending"
                )

                return false
            }


            // =====================================================
            // 17. DEFAULT
            // =====================================================

            Log.d(
                TAG,
                "⏳ SEARCH NOT CONFIRMED"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "🔎 Query = [$pendingQuery]"
            )

            Log.d(
                TAG,
                "📌 Event = ${event.eventType}"
            )

            Log.d(
                TAG,
                "⏱ Age = ${pendingAge}ms")

            Log.d(
                TAG,
                "=========================================="
            )

            return false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Pending search UI confirmation error",
                e
            )

            return false
        }
    }
    //check if its a browser-----
    private fun isBrowserPackage(
        packageName: String
    ): Boolean {

        val pkg =
            packageName
                .trim()
                .lowercase()

        // ----------------------------------------------------
        // Known browsers
        // ----------------------------------------------------

        if (browserApps.contains(pkg)) {
            return true
        }

        // ----------------------------------------------------
        // Browser package-name hints
        // ----------------------------------------------------

        return pkg.contains("browser") ||
                pkg.contains("firefox") ||
                pkg.contains("chrome") ||
                pkg.contains("opera") ||
                pkg.contains("brave") ||
                pkg.contains("vivaldi") ||
                pkg.contains("duckduckgo") ||
                pkg.contains("kiwi")
    }



    // ========================================================
    // UNIVERSAL NATIVE SEARCH DETECTION
    // ========================================================
    private fun detectNativeSearch(
        root: AccessibilityNodeInfo,
        packageName: String,
        event: AccessibilityEvent
    ) {

        try {

            Log.d(TAG, "==========================================")
            Log.d(TAG, "🔥 NATIVE SEARCH DETECTION")
            Log.d(TAG, "🔥 Package = $packageName")
            Log.d(TAG, "🔥 Event = ${event.eventType}")
            Log.d(TAG, "🔥 Event text = ${event.text}")

            // ============================================================
            // STEP 0 — IGNORE ONLY SYSTEM / NON-SEARCH PACKAGES
            //
            // DO NOT hard-code social-media apps here.
            //
            // TikTok
            // TikTok Lite
            // Instagram
            // Facebook
            // YouTube
            // X
            // etc.
            //
            // must remain allowed.
            // ============================================================

            if (isIgnoredNativeSearchPackage(packageName)) {

                Log.d(
                    TAG,
                    "🚫 Native search ignored package = $packageName"
                )

                return
            }


            // ============================================================
            // STEP 1 — FIND CURRENT QUERY
            //
            // We always inspect the CURRENT UI first.
            //
            // This allows:
            //
            // "por"
            // "porn"
            // "porn video"
            //
            // to update the pending query correctly.
            // ============================================================

            var query: String? = null


            // ============================================================
            // STRATEGY 1 — FOCUSED SEARCH FIELD
            // ============================================================

            query = try {

                findFocusedSearchField(
                    root = root,
                    packageName = packageName
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ Focused search field detection failed",
                    e
                )

                null
            }


            if (!query.isNullOrBlank()) {

                Log.d(
                    TAG,
                    "🎯 SEARCH QUERY FROM FOCUSED FIELD"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$query]"
                )
            }


            // ============================================================
            // STRATEGY 2 — EVENT TEXT
            // ============================================================

            if (query.isNullOrBlank()) {

                query = try {

                    extractQueryFromEvent(
                        event = event,
                        packageName = packageName,
                        root = root
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Event query extraction failed",
                        e
                    )

                    null
                }


                if (!query.isNullOrBlank()) {

                    Log.d(
                        TAG,
                        "🎯 SEARCH QUERY FROM EVENT"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$query]"
                    )
                }
            }


            // ============================================================
            // STRATEGY 3 — TREE SEARCH
            // ============================================================

            if (query.isNullOrBlank()) {

                query = try {

                    findSearchCandidateInTree(
                        root = root,
                        packageName = packageName
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Search tree scan failed",
                        e
                    )

                    null
                }


                if (!query.isNullOrBlank()) {

                    Log.d(
                        TAG,
                        "🎯 SEARCH QUERY FROM TREE"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$query]"
                    )
                }
            }


            // ============================================================
            // STRATEGY 4 — NATIVE APP RECOVERY
            // ============================================================

            if (query.isNullOrBlank()) {

                query = try {

                    findNativeAppSearchCandidate(
                        root = root,
                        packageName = packageName
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ Native app search recovery failed",
                        e
                    )

                    null
                }


                if (!query.isNullOrBlank()) {

                    Log.d(
                        TAG,
                        "🎯 SEARCH QUERY FROM NATIVE APP RECOVERY"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$query]"
                    )
                }
            }


            // ============================================================
            // STEP 2 — NOTHING FOUND
            // ============================================================

            if (query.isNullOrBlank()) {

                Log.d(
                    TAG,
                    "⏭ No native search candidate"
                )

                return
            }


            // ============================================================
            // STEP 3 — CLEAN QUERY
            // ============================================================

            val cleanQuery =
                cleanSearchQuery(query)


            Log.d(
                TAG,
                "🧹 Clean query = [$cleanQuery]"
            )


            // ============================================================
            // STEP 4 — NATIVE SEARCH EXCLUSION
            //
            // This is where specific bad candidates are rejected.
            //
            // Social-media packages should NOT be rejected merely because
            // they are not in a hard-coded app list.
            // ============================================================

            if (
                shouldExcludeNativeSearch(
                    packageName = packageName,
                    query = cleanQuery
                )
            ) {

                Log.d(
                    TAG,
                    "🚫 Native search rejected by exclusion rules"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                return
            }


            // ============================================================
            // STEP 5 — VALIDATE
            // ============================================================

            if (
                !isValidSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Invalid search query"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                return
            }


            // ============================================================
            // STEP 6 — REJECT URL
            // ============================================================

            if (
                looksLikeUrl(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 Search candidate looks like URL"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                return
            }


            // ============================================================
            // STEP 7 — REJECT CLEAR UI NOISE
            // ============================================================

            if (
                isUiNoiseSearchQuery(cleanQuery)
            ) {

                Log.d(
                    TAG,
                    "🚫 UI noise rejected"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                return
            }


            // ============================================================
            // STEP 8 — APP/UI NAME FILTER
            //
            // IMPORTANT:
            //
            // Do NOT apply isLikelyAppName() blindly to known native
            // search applications.
            //
            // A child may legitimately search:
            //
            // "TikTok"
            // "Instagram"
            // "YouTube"
            // "Facebook"
            //
            // inside another app.
            //
            // For known native-search apps, allow the query through.
            // ============================================================

            val isKnownNativeApp =
                knownNativeSearchApps.any {
                    it.equals(
                        packageName,
                        ignoreCase = true
                    )
                }


            if (
                !isKnownNativeApp &&
                isLikelyAppName(
                    query = cleanQuery,
                    packageName = packageName
                )
            ) {

                Log.d(
                    TAG,
                    "🚫 Likely app/UI name rejected"
                )

                Log.d(
                    TAG,
                    "📦 Package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                return
            }


            // ============================================================
            // STEP 9 — NORMALIZE
            // ============================================================

            val normalizedNewQuery =
                cleanQuery
                    .trim()
                    .lowercase()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )


            val normalizedExistingQuery =
                pendingSearchQuery
                    .trim()
                    .lowercase()
                    .replace(
                        Regex("\\s+"),
                        " "
                    )


            // ============================================================
            // STEP 10 — CHECK EXISTING PENDING SEARCH
            // ============================================================

            val samePackage =
                pendingSearchPackage.equals(
                    packageName,
                    ignoreCase = true
                )


            val sameQuery =
                normalizedExistingQuery ==
                        normalizedNewQuery


            if (
                samePackage &&
                sameQuery &&
                pendingSearchTime > 0L
            ) {

                val pendingAge =
                    System.currentTimeMillis() -
                            pendingSearchTime


                if (
                    pendingAge <=
                    PENDING_SEARCH_TIMEOUT
                ) {

                    Log.d(
                        TAG,
                        "⏭ SEARCH ALREADY PENDING"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                    Log.d(
                        TAG,
                        "⏱ Age = ${pendingAge}ms"
                    )

                    return
                }


                // --------------------------------------------------------
                // EXPIRED
                // --------------------------------------------------------

                Log.d(
                    TAG,
                    "⌛ Existing pending search expired"
                )

                Log.d(
                    TAG,
                    "🔎 Old query = [$pendingSearchQuery]"
                )

                clearPendingSearch()
            }


            // ============================================================
            // STEP 11 — QUERY CHANGED
            //
            // Example:
            //
            // football
            //      ↓
            // football news
            //
            // Keep the newest query.
            // ============================================================

            if (
                pendingSearchQuery.isNotBlank() &&
                (
                        !samePackage ||
                                !sameQuery
                        )
            ) {

                Log.d(
                    TAG,
                    "🔄 SEARCH QUERY CHANGED"
                )

                Log.d(
                    TAG,
                    "📦 Old package = $pendingSearchPackage"
                )

                Log.d(
                    TAG,
                    "🔎 Old query = [$pendingSearchQuery]"
                )

                Log.d(
                    TAG,
                    "📦 New package = $packageName"
                )

                Log.d(
                    TAG,
                    "🔎 New query = [$cleanQuery]"
                )

                clearPendingSearch()
            }


            // ============================================================
            // STEP 12 — CREATE PENDING SEARCH
            //
            // IMPORTANT:
            //
            // We DO NOT save to Firebase here.
            //
            // This only means:
            //
            // "We believe the user is typing a search."
            //
            // commitPendingSearch() performs the actual save.
            // ============================================================

            rememberPendingSearch(
                query = cleanQuery,
                packageName = packageName
            )


            // ============================================================
            // STEP 13 — FINAL DEBUG
            // ============================================================

            Log.d(
                TAG,
                "=========================================="
            )

            Log.d(
                TAG,
                "📝 NEW NATIVE SEARCH CANDIDATE"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "🔎 Query = [$cleanQuery]"
            )

            Log.d(
                TAG,
                "📌 Event = ${event.eventType}"
            )

            Log.d(
                TAG,
                "📱 Known native app = $isKnownNativeApp"
            )

            Log.d(
                TAG,
                "⏳ Status = WAITING FOR SUBMISSION"
            )

            Log.d(
                TAG,
                "=========================================="
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Native search detection error",
                e
            )
        }
    }
    //========check if excluded ========
    private fun shouldExcludeNativeSearch(
        packageName: String?,
        query: String
    ): Boolean {

        val pkg = packageName
            ?.trim()
            ?.lowercase()
            ?: ""

        val cleanQuery = query.trim()

        // ====================================================
        // RULE 1 — NEVER TREAT ANDROID SYSTEM UI AS SEARCH
        // ====================================================

        val systemPackages = setOf(
            "android",
            "com.android.systemui",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.honeyboard",
            "com.sec.android.inputmethod",
            "com.android.inputmethod.latin"
        )

        if (pkg in systemPackages) {

            Log.d(TAG, "🚫 Native search excluded")
            Log.d(TAG, "📦 Package = $pkg")
            Log.d(TAG, "🔎 Query = [$cleanQuery]")
            Log.d(TAG, "📌 Reason = Android/system package")

            return true
        }


        // ====================================================
        // RULE 2 — EXISTING EXCLUDED APPLICATIONS
        // ====================================================

        if (isExcludedNativeSearchPackage(pkg)) {

            Log.d(TAG, "🚫 Native search excluded")
            Log.d(TAG, "📦 Package = $pkg")
            Log.d(TAG, "🔎 Query = [$cleanQuery]")
            Log.d(TAG, "📌 Reason = excluded application")

            return true
        }


        // ====================================================
        // RULE 3 — PHONE NUMBER
        // ====================================================

        if (looksLikePhoneNumber(cleanQuery)) {

            Log.d(TAG, "🚫 Native search excluded")
            Log.d(TAG, "📦 Package = $pkg")
            Log.d(TAG, "🔎 Query = [$cleanQuery]")
            Log.d(TAG, "📌 Reason = phone number")

            return true
        }


        // ====================================================
        // RULE 4 — ANDROID ERROR / SYSTEM DIALOG TEXT
        // ====================================================

        val systemDialogPatterns = listOf(
            "isn't responding",
            "is not responding",
            "close app",
            "wait",
            "app info",
            "force stop",
            "keeps stopping",
            "has stopped",
            "not responding"
        )

        val lowerQuery =
            cleanQuery.lowercase()

        if (
            systemDialogPatterns.any {
                lowerQuery.contains(it)
            }
        ) {

            Log.d(TAG, "🚫 Native search excluded")
            Log.d(TAG, "📦 Package = $pkg")
            Log.d(TAG, "🔎 Query = [$cleanQuery]")
            Log.d(TAG, "📌 Reason = system dialog text")

            return true
        }


        // ====================================================
        // OTHERWISE — ALLOW
        // ====================================================

        return false
    }
    //============REMEMBER PENDING SEARCH========
    private fun rememberPendingSearch(
        query: String,
        packageName: String
    ) {

        val cleanQuery =
            cleanSearchQuery(query)

        // ==========================================
        // VALIDATION
        // ==========================================

        if (!isValidSearchQuery(cleanQuery)) {
            return
        }

        if (looksLikeUrl(cleanQuery)) {
            return
        }

        if (isUiNoiseSearchQuery(cleanQuery)) {
            return
        }

        if (isIgnoredSearch(cleanQuery)) {
            return
        }

        // ==========================================
        // IGNORE OBVIOUS APP / UI NAMES
        // ==========================================

        if (
            isLikelyAppName(
                query = cleanQuery,
                packageName = packageName
            )
        ) {
            Log.d(
                TAG,
                "🚫 Ignoring likely app/UI name: [$cleanQuery]"
            )

            return
        }

        // ==========================================
        // REMEMBER CURRENT SEARCH ONLY
        //
        // IMPORTANT:
        // This function MUST NOT save anything.
        //
        // It only stores the candidate while we wait
        // for an actual search submission.
        // ==========================================

        pendingSearchQuery =
            cleanQuery

        pendingSearchPackage =
            packageName

        pendingSearchTime =
            System.currentTimeMillis()

        Log.d(
            TAG,
            "📝 Pending native search"
        )

        Log.d(
            TAG,
            "Package = $packageName"
        )

        Log.d(
            TAG,
            "Query = $cleanQuery"
        )

        Log.d(
            TAG,
            "Status = WAITING FOR SUBMISSION"
        )
    }

    //----------ignored package filter---
    private fun isIgnoredNativeSearchPackage(
        packageName: String
    ): Boolean {

        return when (packageName) {

            // Android system UI
            "com.android.systemui" -> true

            // Samsung launcher / One UI Home
            "com.sec.android.app.launcher" -> true

            // ParentIQ itself
            applicationContext.packageName -> true

            else -> false
        }
    }
    //-----noise search query-----
    private fun isUiNoiseSearchQuery(
        query: String
    ): Boolean {

        val normalized =
            query
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")


        // ------------------------------------------------------------
        // Exact UI labels
        // ------------------------------------------------------------

        val exactNoise = setOf(

            "search",
            "search query",
            "search history",

            "1 search",
            "2 searches",
            "3 searches",
            "4 searches",
            "5 searches",
            "6 searches",
            "7 searches",
            "8 searches",
            "9 searches",
            "10 searches"
        )


        if (normalized in exactNoise) {
            return true
        }


        // ------------------------------------------------------------
        // Generic "N search/searches"
        //
        // catches:
        // 11 searches
        // 25 searches
        // 73 searches
        // etc.
        // ------------------------------------------------------------

        if (
            normalized.matches(
                Regex("^\\d+\\s+search(es)?$")
            )
        ) {
            return true
        }


        // ------------------------------------------------------------
        // Generic accessibility UI text
        // ------------------------------------------------------------

        val noisePatterns = listOf(

            "search query",
            "unlock search history",
            "all time",
            "clear",
            "safe",
            "blocked",
            "allow",
            "cancel",
            "close"
        )


        for (pattern in noisePatterns) {

            if (normalized == pattern) {
                return true
            }
        }


        return false
    }
    //-----------ignore duplicate native search---
    private fun isDuplicateNativeSearch(
        query: String,
        packageName: String
    ): Boolean {

        val now = System.currentTimeMillis()

        val normalizedQuery =
            query
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")

        val normalizedPackage =
            packageName
                .trim()
                .lowercase()

        // ========================================================
        // PREVIOUS ACTUALLY SAVED SEARCH
        // ========================================================

        val lastQuery =
            lastSavedNativeSearchQuery
                ?.trim()
                ?.lowercase()
                ?.replace(Regex("\\s+"), " ")

        val lastPackage =
            lastSavedNativeSearchPackage
                ?.trim()
                ?.lowercase()

        // ========================================================
        // COMPARE
        // ========================================================

        val sameQuery =
            lastQuery == normalizedQuery

        val samePackage =
            lastPackage == normalizedPackage

        val withinWindow =
            lastSavedNativeSearchTime > 0L &&
                    now - lastSavedNativeSearchTime <
                    NATIVE_SEARCH_DUPLICATE_WINDOW

        Log.d(TAG, "🔎 Native search duplicate check")

        Log.d(TAG, "Current query = [$normalizedQuery]")
        Log.d(TAG, "Last query = [$lastQuery]")
        Log.d(TAG, "Current package = [$normalizedPackage]")
        Log.d(TAG, "Last package = [$lastPackage]")
        Log.d(TAG, "Same query = $sameQuery")
        Log.d(TAG, "Same package = $samePackage")
        Log.d(TAG, "Within window = $withinWindow")

        // ========================================================
        // RESULT
        // ========================================================

        val duplicate =
            sameQuery &&
                    samePackage &&
                    withinWindow

        if (duplicate) {
            Log.d(
                TAG,
                "🔁 DUPLICATE NATIVE SEARCH"
            )
        } else {
            Log.d(
                TAG,
                "✅ NOT A DUPLICATE"
            )
        }

        // IMPORTANT:
        // DO NOT update lastSavedNativeSearchQuery here.
        // DO NOT update lastSavedNativeSearchPackage here.
        // DO NOT update lastSavedNativeSearchTime here.
        //
        // Those values must only be updated AFTER the search
        // has actually been accepted/sent to BrowsingTracker.

        return duplicate
    }
    //==========CENTRAL EXLUDED PACKAGE FUNCTION=====
    private fun isExcludedNativeSearchPackage(
        packageName: String?
    ): Boolean {

        val pkg = packageName
            ?.trim()
            ?.lowercase()
            ?: return false

        return when {

            // ====================================================
            // PHONE / CONTACTS / DIALER
            // ====================================================

            pkg == "com.sh.smart.caller" -> true

            pkg == "com.android.contacts" -> true
            pkg == "com.google.android.contacts" -> true

            pkg == "com.android.dialer" -> true
            pkg == "com.google.android.dialer" -> true

            pkg == "com.samsung.android.dialer" -> true
            pkg == "com.samsung.android.contacts" -> true


            // ====================================================
            // SMS / MESSAGES
            // ====================================================

            pkg == "com.android.messaging" -> true

            pkg == "com.google.android.apps.messaging" -> true

            pkg == "com.samsung.android.messaging" -> true

            pkg == "com.android.mms" -> true


            // ====================================================
            // WHATSAPP
            // ====================================================

            pkg == "com.whatsapp" -> true

            pkg == "com.whatsapp.w4b" -> true


            // ====================================================
            // TELEGRAM
            // ====================================================

            pkg == "org.telegram.messenger" -> true


            // ====================================================
            // GOOGLE PLAY STORE
            // ====================================================

            pkg == "com.android.vending" -> true


            // ====================================================
            // OTHERWISE
            // ====================================================

            else -> false
        }
    }
    //===========CHECK IF ITS A PHONE NUMBER=============
    private fun looksLikePhoneNumber(
        query: String
    ): Boolean {

        // ========================================================
        // CLEAN QUERY
        // ========================================================

        val cleaned = query
            .trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        Log.d(
            TAG,
            "📞 PHONE NUMBER CHECK"
        )

        Log.d(
            TAG,
            "Original query = [$query]"
        )

        Log.d(
            TAG,
            "Cleaned query = [$cleaned]"
        )


        // ========================================================
        // EMPTY QUERY
        // ========================================================

        if (cleaned.isEmpty()) {

            Log.d(
                TAG,
                "📞 Phone number = false (empty)"
            )

            return false
        }


        // ========================================================
        // INTERNATIONAL NUMBER
        //
        // Examples:
        //
        // +254748443330
        // +254 748 443 330
        // +447911123456
        // ========================================================

        if (
            cleaned.startsWith("+") &&
            cleaned
                .drop(1)
                .matches(
                    Regex("[0-9]{8,15}")
                )
        ) {

            Log.d(
                TAG,
                "📞 Phone number = TRUE"
            )

            Log.d(
                TAG,
                "📌 Reason = international phone number"
            )

            return true
        }


        // ========================================================
        // KENYAN LOCAL MOBILE NUMBER
        //
        // Examples:
        //
        // 0712345678
        // 0722345678
        // 0748441330
        // 0751234567
        // 0761234567
        // 0771234567
        // 0781234567
        // 0791234567
        // ========================================================

        if (
            cleaned.matches(
                Regex("0[17][0-9]{8}")
            )
        ) {

            Log.d(
                TAG,
                "📞 Phone number = TRUE"
            )

            Log.d(
                TAG,
                "📌 Reason = Kenyan local mobile number"
            )

            return true
        }


        // ========================================================
        // NOT A PHONE NUMBER
        // ========================================================

        Log.d(
            TAG,
            "📞 Phone number = FALSE"
        )

        return false
    }
    private fun isExcludedFromNativeSearch(
        packageName: String
    ): Boolean {

        val pkg =
            packageName
                .trim()
                .lowercase()

        // ============================================================
        // YOUR OWN PARENT APP
        // ============================================================

        if (
            pkg == "com.parentalcontrol.parentapp"
        ) {
            return true
        }

        // ============================================================
        // ANDROID SYSTEM
        // ============================================================

        if (
            pkg == "android" ||
            pkg == "com.android.systemui" ||
            pkg == "com.android.settings"
        ) {
            return true
        }

        // ============================================================
        // SAMSUNG LAUNCHER
        // ============================================================

        if (
            pkg == "com.sec.android.app.launcher" ||
            pkg == "com.samsung.android.app.launcher"
        ) {
            return true
        }

        // ============================================================
        // KEYBOARDS / INPUT METHODS
        // ============================================================

        if (
            pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("honeyboard")
        ) {
            return true
        }

        // ============================================================
        // LAUNCHERS
        // ============================================================

        if (
            pkg.contains("launcher")
        ) {
            return true
        }

        // ============================================================
        // ANDROID SYSTEM PACKAGES
        // ============================================================

        if (
            pkg.startsWith("com.android.") &&
            pkg != "com.android.chrome"
        ) {
            return true
        }

        // ============================================================
        // SAMSUNG SYSTEM PACKAGES
        // ============================================================

        if (
            pkg.startsWith("com.samsung.android.") &&
            !pkg.contains("internet")
        ) {
            return true
        }

        return false
    }


    //--------filter out ui noise---
    private fun isUiNoiseSearch(
        query: String
    ): Boolean {

        val value =
            query
                .trim()
                .lowercase()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        if (value.isBlank()) {
            return true
        }

        // ============================================================
        // EXACT UI LABELS
        // ============================================================

        val exactNoise =
            setOf(

                "search",
                "searching",
                "search here",
                "search now",
                "search results",
                "search history",

                "tap to search",
                "tap to type",
                "tap here to search",
                "type to search",

                "find",
                "find in page",

                "clear",
                "clear input",
                "cancel",
                "back",
                "done",
                "close",
                "send",

                "message",
                "messages",
                "chat",
                "chats",

                "lite",

                "home",
                "one ui home",
                "system ui",
                "android system",

                "web view",
                "browser",

                "listening...",
                "loading...",
                "please wait",
                "wait",

                "google search",
                "search or type web address",
                "or type web address"
            )

        if (value in exactNoise) {
            return true
        }

        // ============================================================
        // "1 search"
        // "2 searches"
        // "6 searches"
        // ============================================================

        if (
            Regex(
                "^\\d+\\s+search(es)?$"
            ).matches(value)
        ) {
            return true
        }

        // ============================================================
        // KEYBOARD UI TEXT
        // ============================================================

        if (
            value.contains("expand toolbar") ||
            value.contains("clipboard") ||
            value.contains("keyboard") ||
            value.contains("emoji") ||
            value.contains("enter key")
        ) {
            return true
        }

        return false
    }

    //------app name filter---
    private fun isLikelyAppName(
        query: String,
        packageName: String
    ): Boolean {

        val value =
            query
                .trim()
                .lowercase()

        val pkg =
            packageName
                .trim()
                .lowercase()

        // ============================================================
        // FACEBOOK
        // ============================================================

        if (
            pkg.contains("facebook") &&
            (
                    value == "facebook" ||
                            value == "lite"
                    )
        ) {
            return true
        }

        // ============================================================
        // INSTAGRAM
        // ============================================================

        if (
            pkg.contains("instagram") &&
            value == "instagram"
        ) {
            return true
        }

        // ============================================================
        // TIKTOK
        // ============================================================

        if (
            pkg.contains("tiktok") &&
            (
                    value == "tiktok" ||
                            value == "tik tok"
                    )
        ) {
            return true
        }

        // ============================================================
        // YOUTUBE
        // ============================================================

        if (
            pkg.contains("youtube") &&
            value == "youtube"
        ) {
            return true
        }

        // ============================================================
        // TWITTER / X
        // ============================================================

        if (
            (
                    pkg.contains("twitter") ||
                            pkg.contains("x.android")
                    ) &&
            (
                    value == "twitter" ||
                            value == "x"
                    )
        ) {
            return true
        }

        // ============================================================
        // WHATSAPP
        // ============================================================

        if (
            pkg.contains("whatsapp") &&
            (
                    value == "whatsapp" ||
                            value == "message" ||
                            value == "messages" ||
                            value == "chat"
                    )
        ) {
            return true
        }

        // ============================================================
        // SYSTEM LABELS
        // ============================================================

        if (
            value == "one ui home" ||
            value == "system ui" ||
            value == "android system"
        ) {
            return true
        }

        return false
    }

    //--------package filter----------
    private fun isIgnoredSearchPackage(
        packageName: String
    ): Boolean {

        val pkg =
            packageName
                .trim()
                .lowercase()

        return when {

            // ==================================================
            // YOUR PARENT APP
            // ==================================================

            pkg == "com.parentalcontrol.parentapp" ->
                true

            // ==================================================
            // ANDROID SYSTEM UI
            // ==================================================

            pkg == "com.android.systemui" ->
                true

            // ==================================================
            // SAMSUNG LAUNCHER / ONE UI HOME
            // ==================================================

            pkg == "com.sec.android.app.launcher" ->
                true

            // ==================================================
            // SAMSUNG KEYBOARD
            // ==================================================

            pkg == "com.samsung.android.honeyboard" ->
                true

            // ==================================================
            // GOOGLE KEYBOARD
            // ==================================================

            pkg == "com.google.android.inputmethod.latin" ->
                true

            // ==================================================
            // ANDROID INPUT METHOD
            // ==================================================

            pkg == "com.android.inputmethod.latin" ->
                true

            // ==================================================
            // OTHER COMMON SYSTEM LAUNCHERS
            // ==================================================

            pkg == "com.android.launcher" ->
                true

            pkg == "com.google.android.apps.nexuslauncher" ->
                true

            // ==================================================
            // ACCESSIBILITY / SETTINGS
            // ==================================================

            pkg == "com.android.settings" ->
                true

            pkg == "com.samsung.android.settings" ->
                true

            // ==================================================
            // OTHERWISE ALLOW IT
            // ==================================================

            else ->
                false
        }
    }

    // ========================================================
    // FOCUSED SEARCH FIELD
    // ========================================================

    private fun findFocusedSearchField(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String? {

        var bestCandidate: String? = null

        var bestScore = 0

        fun scan(
            node: AccessibilityNodeInfo?,
            depth: Int
        ) {

            if (
                node == null ||
                depth > MAX_TREE_DEPTH
            ) {
                return
            }

            try {

                val text =
                    node.text
                        ?.toString()
                        ?.trim()
                        ?: ""

                val hint =
                    node.hintText
                        ?.toString()
                        ?.trim()
                        ?: ""

                val description =
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?: ""

                val viewId =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                val className =
                    node.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""

                val isEditable =
                    node.isEditable ||
                            className.contains(
                                "edittext"
                            ) ||
                            className.contains(
                                "textfield"
                            ) ||
                            className.contains(
                                "autocompletetext"
                            ) ||
                            className.contains(
                                "textinput"
                            )

                if (
                    isEditable &&
                    node.isFocused &&
                    text.isNotBlank()
                ) {

                    val score =
                        calculateSearchScore(
                            node = node,
                            packageName = packageName,
                            text = text,
                            hint = hint,
                            description = description,
                            viewId = viewId,
                            className = className
                        )

                    if (
                        score > bestScore &&
                        isValidSearchQuery(
                            cleanSearchQuery(text)
                        )
                    ) {

                        bestScore =
                            score

                        bestCandidate =
                            text

                        Log.d(
                            TAG,
                            "🎯 Focused search candidate"
                        )

                        Log.d(
                            TAG,
                            "Package = $packageName"
                        )

                        Log.d(
                            TAG,
                            "Score = $score"
                        )

                        Log.d(
                            TAG,
                            "ID = $viewId"
                        )

                        Log.d(
                            TAG,
                            "Hint = $hint"
                        )

                        Log.d(
                            TAG,
                            "Text = $text"
                        )
                    }
                }

                for (
                i in 0 until node.childCount
                ) {

                    scan(
                        node.getChild(i),
                        depth + 1
                    )
                }

            } catch (
                _: Exception
            ) {
                // Accessibility node may disappear.
            }
        }

        scan(
            root,
            0
        )

        return if (
            bestScore >= MIN_SEARCH_SCORE
        ) {

            bestCandidate

        } else {

            null
        }
    }

    // ========================================================
    // EVENT QUERY EXTRACTION
    // ========================================================

    private fun extractQueryFromEvent(
        event: AccessibilityEvent,
        packageName: String,
        root: AccessibilityNodeInfo
    ): String? {

        try {

            // ============================================================
            // 1. NORMALIZE PACKAGE
            // ============================================================

            val normalizedPackage =
                packageName
                    .trim()
                    .lowercase()


            // ============================================================
            // 2. NEVER EXTRACT FROM SYSTEM PACKAGES
            // ============================================================

            if (
                normalizedPackage == "android" ||
                normalizedPackage == "com.android.systemui"
            ) {

                Log.d(
                    TAG,
                    "🚫 Event query ignored from system package"
                )

                Log.d(
                    TAG,
                    "📦 Package = $normalizedPackage"
                )

                return null
            }


            // ============================================================
            // 3. EVENT TYPE
            // ============================================================

            val eventType =
                event.eventType


            if (
                eventType !=
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                eventType !=
                AccessibilityEvent.TYPE_VIEW_FOCUSED &&
                eventType !=
                AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {

                return null
            }


            // ============================================================
            // 4. EVENT SOURCE
            // ============================================================

            val source =
                try {

                    event.source

                } catch (
                    _: Exception
                ) {

                    null
                }


            // ============================================================
            // 5. SOURCE INFORMATION
            // ============================================================

            val sourceClass =
                source
                    ?.className
                    ?.toString()
                    ?.lowercase()
                    ?: ""


            val sourceText =
                source
                    ?.text
                    ?.toString()
                    ?.trim()
                    ?: ""


            val sourceDescription =
                source
                    ?.contentDescription
                    ?.toString()
                    ?.trim()
                    ?.lowercase()
                    ?: ""


            val sourceViewId =
                source
                    ?.viewIdResourceName
                    ?.lowercase()
                    ?: ""


            val editable =
                source?.isEditable == true


            // ============================================================
            // 6. DOES SOURCE LOOK LIKE AN EDITABLE INPUT?
            // ============================================================

            val editTextClass =
                sourceClass.contains("edittext") ||
                        sourceClass.contains("textinput") ||
                        sourceClass.contains("autocompletetext") ||
                        sourceClass.contains("textinputedittext") ||
                        sourceClass.contains("searchview")


            val searchIdentifier =
                sourceViewId.contains("search") ||
                        sourceViewId.contains("query") ||
                        sourceDescription.contains("search") ||
                        sourceDescription.contains("query")


            val searchField =
                editable &&
                        (
                                editTextClass ||
                                        searchIdentifier
                                )


            // ============================================================
            // 7. TEXT_CHANGED
            //
            // Only accept actual text changes from an editable/search
            // field.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            ) {

                if (source == null) {

                    Log.d(
                        TAG,
                        "⏭ TEXT_CHANGED has no source"
                    )

                    return null
                }


                if (!searchField) {

                    Log.d(
                        TAG,
                        "⏭ TEXT_CHANGED source is not search field"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $normalizedPackage"
                    )

                    Log.d(
                        TAG,
                        "🏷 Class = $sourceClass"
                    )

                    Log.d(
                        TAG,
                        "📝 Source text = [$sourceText]"
                    )

                    Log.d(
                        TAG,
                        "🆔 ViewId = [$sourceViewId]"
                    )

                    Log.d(
                        TAG,
                        "✏️ Editable = $editable"
                    )

                    return null
                }


                // --------------------------------------------------------
                // Get the actual text.
                // --------------------------------------------------------

                val eventText =
                    event.text
                        ?.joinToString(" ")
                        ?.trim()
                        ?: ""


                val candidate =
                    if (sourceText.isNotBlank()) {

                        sourceText

                    } else {

                        eventText
                    }


                if (candidate.isBlank()) {
                    return null
                }


                // --------------------------------------------------------
                // CLEAN
                // --------------------------------------------------------

                val cleanQuery =
                    cleanSearchQuery(
                        candidate
                    )


                if (cleanQuery.isBlank()) {
                    return null
                }


                // --------------------------------------------------------
                // VALIDATE
                // --------------------------------------------------------

                if (
                    !isValidSearchQuery(
                        cleanQuery
                    )
                ) {

                    Log.d(
                        TAG,
                        "🚫 Invalid TEXT_CHANGED query"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                    return null
                }


                // --------------------------------------------------------
                // URL
                // --------------------------------------------------------

                if (
                    looksLikeUrl(
                        cleanQuery
                    )
                ) {

                    Log.d(
                        TAG,
                        "🚫 TEXT_CHANGED query looks like URL"
                    )

                    return null
                }


                // --------------------------------------------------------
                // UI NOISE
                // --------------------------------------------------------

                if (
                    isUiNoiseSearchQuery(
                        cleanQuery
                    )
                ) {

                    Log.d(
                        TAG,
                        "🚫 TEXT_CHANGED query is UI noise"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                    return null
                }


                // --------------------------------------------------------
                // FINAL REAL SEARCH CHECK
                // --------------------------------------------------------

                if (
                    !isLikelyRealSearchText(
                        text = cleanQuery,
                        packageName = normalizedPackage,
                        root = root
                    )
                ) {

                    Log.d(
                        TAG,
                        "🚫 TEXT_CHANGED query failed real-search check"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $normalizedPackage"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                    return null
                }


                // --------------------------------------------------------
                // ACCEPT
                // --------------------------------------------------------

                Log.d(
                    TAG,
                    "=========================================="
                )

                Log.d(
                    TAG,
                    "📝 EVENT SEARCH CANDIDATE"
                )

                Log.d(
                    TAG,
                    "📦 Package = $normalizedPackage"
                )

                Log.d(
                    TAG,
                    "🔎 Query = [$cleanQuery]"
                )

                Log.d(
                    TAG,
                    "🏷 Class = $sourceClass"
                )

                Log.d(
                    TAG,
                    "🆔 ViewId = $sourceViewId"
                )

                Log.d(
                    TAG,
                    "✏️ Editable = $editable"
                )

                Log.d(
                    TAG,
                    "=========================================="
                )

                return cleanQuery
            }


            // ============================================================
            // 8. FOCUS / CLICK
            //
            // Do not blindly use event.text.
            // Look for an actual editable search field.
            // ============================================================

            if (
                eventType ==
                AccessibilityEvent.TYPE_VIEW_FOCUSED ||
                eventType ==
                AccessibilityEvent.TYPE_VIEW_CLICKED
            ) {

                // --------------------------------------------------------
                // 8A. SOURCE ITSELF
                // --------------------------------------------------------

                if (
                    source != null &&
                    searchField &&
                    sourceText.isNotBlank()
                ) {

                    val cleanQuery =
                        cleanSearchQuery(
                            sourceText
                        )


                    if (
                        cleanQuery.isNotBlank() &&
                        isValidSearchQuery(cleanQuery) &&
                        !looksLikeUrl(cleanQuery) &&
                        !isUiNoiseSearchQuery(cleanQuery) &&
                        isLikelyRealSearchText(
                            text = cleanQuery,
                            packageName = normalizedPackage,
                            root = root
                        )
                    ) {

                        Log.d(
                            TAG,
                            "🎯 SEARCH QUERY FROM FOCUSED/CLICKED FIELD"
                        )

                        Log.d(
                            TAG,
                            "📦 Package = $normalizedPackage"
                        )

                        Log.d(
                            TAG,
                            "🔎 Query = [$cleanQuery]"
                        )

                        return cleanQuery
                    }
                }


                // --------------------------------------------------------
                // 8B. SEARCH EDITABLE NODE IN TREE
                // --------------------------------------------------------

                val treeQuery =
                    findEditableSearchTextInTree(
                        root = root,
                        packageName = normalizedPackage
                    )


                if (!treeQuery.isNullOrBlank()) {

                    val cleanQuery =
                        cleanSearchQuery(
                            treeQuery
                        )


                    if (
                        cleanQuery.isNotBlank() &&
                        isValidSearchQuery(cleanQuery) &&
                        !looksLikeUrl(cleanQuery) &&
                        !isUiNoiseSearchQuery(cleanQuery) &&
                        isLikelyRealSearchText(
                            text = cleanQuery,
                            packageName = normalizedPackage,
                            root = root
                        )
                    ) {

                        Log.d(
                            TAG,
                            "🎯 SEARCH QUERY RECOVERED FROM EDITABLE TREE"
                        )

                        Log.d(
                            TAG,
                            "📦 Package = $normalizedPackage"
                        )

                        Log.d(
                            TAG,
                            "🔎 Query = [$cleanQuery]"
                        )

                        return cleanQuery
                    }
                }
            }


            // ============================================================
            // 9. NOTHING FOUND
            // ============================================================

            return null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Event query extraction error",
                e
            )

            return null
        }
    }
    //==========VALIDATE NATIVE SEARCH=======

    //============SEARCH-FIELD TREE RECOVERY========
    private fun findEditableSearchTextInTree(
        root: AccessibilityNodeInfo?,
        packageName: String,
        depth: Int = 0
    ): String? {

        if (root == null) {
            return null
        }

        if (depth > MAX_TREE_DEPTH) {
            return null
        }

        try {

            // ============================================================
            // READ NODE
            // ============================================================

            val text = root.text
                ?.toString()
                ?.trim()
                ?: ""

            val description = root.contentDescription
                ?.toString()
                ?.trim()
                ?.lowercase()
                ?: ""

            val viewId = root.viewIdResourceName
                ?.lowercase()
                ?: ""

            val className = root.className
                ?.toString()
                ?.lowercase()
                ?: ""

            val editable = root.isEditable


            // ============================================================
            // IDENTIFY INPUT FIELD
            // ============================================================

            val looksLikeEditField =
                className.contains("edittext") ||
                        className.contains("textinput") ||
                        className.contains("autocompletetext") ||
                        className.contains("textinputedittext") ||
                        className.contains("searchview")


            // ============================================================
            // IDENTIFY SEARCH FIELD
            // ============================================================

            val looksLikeSearch =
                viewId.contains("search") ||
                        viewId.contains("query") ||
                        description.contains("search") ||
                        description.contains("query")


            // ============================================================
            // ONLY ACCEPT EDITABLE SEARCH/INPUT NODE
            // ============================================================

            if (
                editable &&
                (looksLikeEditField || looksLikeSearch) &&
                text.isNotBlank()
            ) {

                // ========================================================
                // CLEAN
                // ========================================================

                val cleanQuery =
                    cleanSearchQuery(text)

                if (cleanQuery.isBlank()) {
                    return null
                }


                // ========================================================
                // VALID SEARCH QUERY
                // ========================================================

                if (!isValidSearchQuery(cleanQuery)) {

                    Log.d(
                        TAG,
                        "🚫 Editable node failed search validation"
                    )

                    Log.d(
                        TAG,
                        "📦 Package = $packageName"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                } else if (looksLikeUrl(cleanQuery)) {

                    Log.d(
                        TAG,
                        "🚫 Editable node looks like URL"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                } else if (isUiNoiseSearchQuery(cleanQuery)) {

                    Log.d(
                        TAG,
                        "🚫 Editable node is UI noise"
                    )

                    Log.d(
                        TAG,
                        "🔎 Query = [$cleanQuery]"
                    )

                } else {

                    // ====================================================
                    // REAL SEARCH TEXT CHECK
                    // ====================================================

                    if (
                        isLikelyRealSearchText(
                            text = cleanQuery,
                            packageName = packageName,
                            root = root
                        )
                    ) {

                        Log.d(
                            TAG,
                            "=========================================="
                        )

                        Log.d(
                            TAG,
                            "🎯 EDITABLE SEARCH NODE FOUND"
                        )

                        Log.d(
                            TAG,
                            "📦 Package = $packageName"
                        )

                        Log.d(
                            TAG,
                            "📝 Original text = [$text]"
                        )

                        Log.d(
                            TAG,
                            "🔎 Clean query = [$cleanQuery]"
                        )

                        Log.d(
                            TAG,
                            "🏷 Class = [$className]"
                        )

                        Log.d(
                            TAG,
                            "🆔 ViewId = [$viewId]"
                        )

                        Log.d(
                            TAG,
                            "💬 Description = [$description]"
                        )

                        Log.d(
                            TAG,
                            "✏️ Editable = $editable"
                        )

                        Log.d(
                            TAG,
                            "=========================================="
                        )

                        return cleanQuery
                    }
                }
            }


            // ============================================================
            // SEARCH CHILDREN
            // ============================================================

            val childCount =
                root.childCount

            if (childCount <= 0) {
                return null
            }


            for (i in 0 until childCount) {

                val child =
                    try {

                        root.getChild(i)

                    } catch (e: Exception) {

                        null
                    }


                if (child == null) {
                    continue
                }


                val result =
                    findEditableSearchTextInTree(
                        root = child,
                        packageName = packageName,
                        depth = depth + 1
                    )


                if (!result.isNullOrBlank()) {
                    return result
                }
            }

        } catch (e: Exception) {

            Log.d(
                TAG,
                "⚠️ Search tree node inaccessible"
            )

            Log.d(
                TAG,
                "📦 Package = $packageName"
            )

            Log.d(
                TAG,
                "📏 Depth = $depth"
            )
        }

        return null
    }
    //=======================CENTRAL VALIDATION==========

    // ========================================================
    // BROAD SEARCH TREE SCAN
    // ========================================================

    private fun findSearchCandidateInTree(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String? {

        var bestCandidate: String? = null

        var bestScore = 0

        fun scan(
            node: AccessibilityNodeInfo?,
            depth: Int
        ) {

            if (
                node == null ||
                depth > MAX_TREE_DEPTH
            ) {
                return
            }

            try {

                val text =
                    node.text
                        ?.toString()
                        ?.trim()
                        ?: ""

                val hint =
                    node.hintText
                        ?.toString()
                        ?.trim()
                        ?: ""

                val description =
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?: ""

                val viewId =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                val className =
                    node.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""

                if (
                    text.length >= MIN_SEARCH_LENGTH &&
                    text.length <= MAX_SEARCH_LENGTH &&
                    !looksLikeUrl(text) &&
                    !isIgnoredSearch(text)
                ) {

                    val score =
                        calculateSearchScore(
                            node = node,
                            packageName = packageName,
                            text = text,
                            hint = hint,
                            description = description,
                            viewId = viewId,
                            className = className
                        )

                    if (
                        score > bestScore
                    ) {

                        bestScore =
                            score

                        bestCandidate =
                            text
                    }
                }

                for (
                i in 0 until node.childCount
                ) {

                    scan(
                        node.getChild(i),
                        depth + 1
                    )
                }

            } catch (
                _: Exception
            ) {
            }
        }

        scan(
            root,
            0
        )

        return if (
            bestScore >= MIN_SEARCH_SCORE
        ) {

            Log.d(
                TAG,
                "🎯 Broad search candidate"
            )

            Log.d(
                TAG,
                "Package = $packageName"
            )

            Log.d(
                TAG,
                "Score = $bestScore"
            )

            Log.d(
                TAG,
                "Query = $bestCandidate"
            )

            bestCandidate

        } else {

            null
        }
    }

    // ========================================================
    // NATIVE APP SEARCH RECOVERY
    //
    // This is specifically designed for apps where the
    // accessibility tree does NOT expose a normal EditText.
    // ========================================================

    private fun findNativeAppSearchCandidate(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String? {

        var bestCandidate: String? = null

        var bestScore = 0

        val packageLower =
            packageName.lowercase()

        val isKnownSocialApp =
            packageLower.contains("instagram") ||
                    packageLower.contains("tiktok") ||
                    packageLower.contains("facebook") ||
                    packageLower.contains("twitter") ||
                    packageLower.contains("youtube")

        if (!isKnownSocialApp) {
            return null
        }

        fun scan(
            node: AccessibilityNodeInfo?,
            depth: Int
        ) {

            if (
                node == null ||
                depth > MAX_TREE_DEPTH
            ) {
                return
            }

            try {

                val text =
                    node.text
                        ?.toString()
                        ?.trim()
                        ?: ""

                val description =
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?: ""

                val hint =
                    node.hintText
                        ?.toString()
                        ?.trim()
                        ?: ""

                val id =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                val className =
                    node.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""

                if (
                    text.length in
                    MIN_SEARCH_LENGTH..MAX_SEARCH_LENGTH &&
                    !looksLikeUrl(text) &&
                    !isIgnoredSearch(text)
                ) {

                    var score = 0

                    // Editable-like nodes
                    if (node.isEditable) {
                        score += 5
                    }

                    if (
                        className.contains(
                            "edit"
                        )
                    ) {
                        score += 3
                    }

                    if (
                        className.contains(
                            "text"
                        )
                    ) {
                        score += 1
                    }

                    // Search-related identifiers
                    if (
                        id.contains("search")
                    ) {
                        score += 6
                    }

                    if (
                        id.contains("query")
                    ) {
                        score += 4
                    }

                    // Search hints/descriptions
                    if (
                        hint.contains(
                            "search",
                            ignoreCase = true
                        )
                    ) {
                        score += 5
                    }

                    if (
                        description.contains(
                            "search",
                            ignoreCase = true
                        )
                    ) {
                        score += 5
                    }

                    // Focus
                    if (node.isFocused) {
                        score += 4
                    }

                    // Package confidence
                    score += 2

                    // Text quality
                    if (
                        text.length in 3..80
                    ) {
                        score += 1
                    }

                    if (
                        score > bestScore
                    ) {

                        bestScore =
                            score

                        bestCandidate =
                            text
                    }
                }

                for (
                i in 0 until node.childCount
                ) {

                    scan(
                        node.getChild(i),
                        depth + 1
                    )
                }

            } catch (
                _: Exception
            ) {
            }
        }

        scan(
            root,
            0
        )

        return if (
            bestScore >= 7 &&
            !bestCandidate.isNullOrBlank()
        ) {

            Log.d(
                TAG,
                "🎯 Native app recovery candidate"
            )

            Log.d(
                TAG,
                "Package = $packageName"
            )

            Log.d(
                TAG,
                "Score = $bestScore"
            )

            Log.d(
                TAG,
                "Query = $bestCandidate"
            )

            bestCandidate

        } else {

            null
        }
    }

    // ========================================================
    // REAL SEARCH TEXT CHECK
    // ========================================================

    private fun isLikelyRealSearchText(
        text: String,
        packageName: String,
        root: AccessibilityNodeInfo
    ): Boolean {

        val clean =
            cleanSearchQuery(
                text
            )

        if (
            !isValidSearchQuery(
                clean
            )
        ) {
            return false
        }

        if (
            looksLikeUrl(
                clean
            )
        ) {
            return false
        }

        if (
            isIgnoredSearch(
                clean
            )
        ) {
            return false
        }

        // ----------------------------------------------------
        // If the package is a known search/social app,
        // allow normal user text.
        // ----------------------------------------------------

        if (
            knownNativeSearchApps.contains(
                packageName
            )
        ) {

            return true
        }

        // ----------------------------------------------------
        // Otherwise require search context.
        // ----------------------------------------------------

        return isLikelyNativeSearchScreen(
            root = root,
            packageName = packageName
        )
    }

    // ========================================================
    // SEARCH SCORE
    // ========================================================

    private fun calculateSearchScore(
        node: AccessibilityNodeInfo,
        packageName: String,
        text: String,
        hint: String,
        description: String,
        viewId: String,
        className: String
    ): Int {

        var score = 0

        // ----------------------------------------------------
        // Strong IDs
        // ----------------------------------------------------

        if (
            viewId.contains("search")
        ) {
            score += 6
        }

        if (
            viewId.contains("query")
        ) {
            score += 5
        }

        if (
            viewId.contains("searchbox")
        ) {
            score += 5
        }

        if (
            viewId.contains("search_box")
        ) {
            score += 5
        }

        if (
            viewId.contains("search_bar")
        ) {
            score += 5
        }

        if (
            viewId.contains("search_field")
        ) {
            score += 5
        }

        if (
            viewId.contains("search_src_text")
        ) {
            score += 5
        }

        if (
            viewId.contains("omnibox")
        ) {
            score += 5
        }

        // ----------------------------------------------------
        // Search hint
        // ----------------------------------------------------

        if (
            hint.contains(
                "search",
                ignoreCase = true
            )
        ) {
            score += 6
        }

        if (
            hint.contains(
                "find",
                ignoreCase = true
            )
        ) {
            score += 3
        }

        if (
            hint.contains(
                "ask",
                ignoreCase = true
            )
        ) {
            score += 3
        }

        // ----------------------------------------------------
        // Accessibility description
        // ----------------------------------------------------

        if (
            description.contains(
                "search",
                ignoreCase = true
            )
        ) {
            score += 5
        }

        if (
            description.contains(
                "find",
                ignoreCase = true
            )
        ) {
            score += 3
        }

        // ----------------------------------------------------
        // Editable
        // ----------------------------------------------------

        if (
            node.isEditable
        ) {
            score += 5
        }

        if (
            className.contains(
                "edittext"
            )
        ) {
            score += 4
        }

        if (
            className.contains(
                "textfield"
            )
        ) {
            score += 3
        }

        if (
            className.contains(
                "autocompletetext"
            )
        ) {
            score += 3
        }

        if (
            className.contains(
                "textinput"
            )
        ) {
            score += 2
        }

        // ----------------------------------------------------
        // Focus
        // ----------------------------------------------------

        if (
            node.isFocused
        ) {
            score += 5
        }

        // ----------------------------------------------------
        // Selection
        // ----------------------------------------------------



        // ----------------------------------------------------
        // Known native app
        // ----------------------------------------------------

        if (
            knownNativeSearchApps.contains(
                packageName
            )
        ) {
            score += 2
        }

        // ----------------------------------------------------
        // Package hints
        // ----------------------------------------------------

        val lowerPackage =
            packageName.lowercase()

        if (
            lowerPackage.contains("instagram")
        ) {
            score += 2
        }

        if (
            lowerPackage.contains("tiktok")
        ) {
            score += 2
        }

        if (
            lowerPackage.contains("facebook")
        ) {
            score += 2
        }

        if (
            lowerPackage.contains("twitter")
        ) {
            score += 2
        }

        if (
            lowerPackage.contains("youtube")
        ) {
            score += 2
        }

        // ----------------------------------------------------
        // Text quality
        // ----------------------------------------------------

        if (
            text.length in 3..100
        ) {
            score += 1
        }

        return score
    }

    // ========================================================
    // SEARCH IDENTIFIER
    // ========================================================

    private fun isSearchIdentifier(
        viewId: String,
        hint: String,
        description: String
    ): Boolean {

        if (
            viewId.contains("search") ||
            viewId.contains("query") ||
            viewId.contains("searchbox") ||
            viewId.contains("search_box") ||
            viewId.contains("search_bar") ||
            viewId.contains("search_field") ||
            viewId.contains("search_src_text") ||
            viewId.contains("omnibox")
        ) {

            return true
        }

        if (
            hint.contains(
                "search",
                ignoreCase = true
            )
        ) {
            return true
        }

        if (
            description.contains(
                "search",
                ignoreCase = true
            )
        ) {
            return true
        }

        return false
    }

    // ========================================================
    // DETECT SEARCH SCREEN
    // ========================================================

    private fun isLikelyNativeSearchScreen(
        root: AccessibilityNodeInfo,
        packageName: String
    ): Boolean {

        var foundSearchIndicator = false

        fun scan(
            node: AccessibilityNodeInfo?,
            depth: Int
        ) {

            if (
                node == null ||
                foundSearchIndicator ||
                depth > MAX_TREE_DEPTH
            ) {
                return
            }

            try {

                val text =
                    node.text
                        ?.toString()
                        ?.lowercase()
                        ?.trim()
                        ?: ""

                val hint =
                    node.hintText
                        ?.toString()
                        ?.lowercase()
                        ?.trim()
                        ?: ""

                val description =
                    node.contentDescription
                        ?.toString()
                        ?.lowercase()
                        ?.trim()
                        ?: ""

                val id =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                // ------------------------------------------------
                // IDs
                // ------------------------------------------------

                if (
                    id.contains("search") ||
                    id.contains("query") ||
                    id.contains("omnibox")
                ) {

                    foundSearchIndicator = true
                    return
                }

                // ------------------------------------------------
                // Hints
                // ------------------------------------------------

                if (
                    hint.contains("search") ||
                    hint.contains("find") ||
                    hint.contains("ask")
                ) {

                    foundSearchIndicator = true
                    return
                }

                // ------------------------------------------------
                // Descriptions
                // ------------------------------------------------

                if (
                    description.contains("search") ||
                    description.contains("find")
                ) {

                    foundSearchIndicator = true
                    return
                }

                // ------------------------------------------------
                // Visible labels
                // ------------------------------------------------

                if (
                    text == "search" ||
                    text == "searching" ||
                    text == "find"
                ) {

                    foundSearchIndicator = true
                    return
                }

                for (
                i in 0 until node.childCount
                ) {

                    scan(
                        node.getChild(i),
                        depth + 1
                    )

                    if (
                        foundSearchIndicator
                    ) {
                        return
                    }
                }

            } catch (
                _: Exception
            ) {
            }
        }

        scan(
            root,
            0
        )

        return foundSearchIndicator
    }



    // ========================================================
    // SEARCH VALIDATION
    // ========================================================

    private fun isValidSearchQuery(
        query: String
    ): Boolean {

        if (
            query.isBlank()
        ) {
            return false
        }

        if (
            query.length <
            MIN_SEARCH_LENGTH
        ) {
            return false
        }

        if (
            query.length >
            MAX_SEARCH_LENGTH
        ) {
            return false
        }

        if (
            isIgnoredSearch(
                query
            )
        ) {
            return false
        }

        return true
    }

    // ========================================================
    // CLEAN SEARCH
    // ========================================================

    private fun cleanSearchQuery(
        query: String
    ): String {

        return try {

            Uri.decode(
                query
            )
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        } catch (
            _: Exception
        ) {

            query
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )
        }
    }

    // ========================================================
    // IGNORED SEARCHES
    // ========================================================

    private fun isIgnoredSearch(
        query: String
    ): Boolean {

        val value =
            query
                .trim()
                .lowercase()

        if (
            value.isBlank()
        ) {
            return true
        }

        if (
            value.length < 3
        ) {
            return true
        }

        return value in setOf(

            "search",

            "search youtube",

            "search facebook",

            "search facebook lite",

            "search instagram",

            "search tiktok",

            "search x",

            "search twitter",

            "search or type web address",

            "tap to search",

            "google search",

            "clear input",

            "listening...",

            "youtube",

            "web view",

            "youtube video player",

            "2 open tabs, tap to switch tabs",

            "cancel",

            "back",

            "done",

            "next",

            "send",

            "close",

            "menu",

            "home",

            "explore",

            "reels",

            "shorts",

            "following",

            "for you",

            "notifications",

            "messages",

            "settings"
        )
    }

    // ========================================================
    // URL CHECK
    // ========================================================

    private fun looksLikeUrl(
        text: String
    ): Boolean {

        val value =
            text
                .trim()
                .lowercase()

        if (
            value.startsWith("http://")
        ) {
            return true
        }

        if (
            value.startsWith("https://")
        ) {
            return true
        }

        if (
            value.startsWith("www.")
        ) {
            return true
        }

        if (
            value.contains("youtube.com/")
        ) {
            return true
        }

        if (
            value.contains("google.com/search")
        ) {
            return true
        }

        if (
            value.contains("youtu.be/")
        ) {
            return true
        }

        if (
            Regex(
                "^[a-zA-Z0-9-]+\\.[a-zA-Z]{2,}(\\/.*)?$"
            ).matches(value)
        ) {
            return true
        }

        return false
    }

    // ========================================================
    // BLOCKED DOMAIN
    // ========================================================

    private fun isBlockedDomainUrl(
        url: String
    ): Boolean {

        val host =
            try {

                Uri.parse(url)
                    .host
                    ?: return false

            } catch (
                _: Exception
            ) {

                return false
            }

        val cleanHost =
            host
                .replace(
                    "www.",
                    ""
                )
                .lowercase()

        return listOf(

            "pornhub.com",

            "xbet.com",

            "betika.com"

        ).any {

            cleanHost == it ||
                    cleanHost.endsWith(
                        ".$it"
                    )
        }
    }

    // ========================================================
    // NAVIGATION KEY
    // ========================================================

    private fun buildDispatchKey(
        url: String
    ): String {

        return try {

            val uri =
                Uri.parse(
                    url
                )

            val host =
                uri.host
                    ?.replace(
                        "www.",
                        ""
                    )
                    ?.replace(
                        "m.",
                        ""
                    )
                    ?.lowercase()
                    ?: return url

            // ------------------------------------------------
            // YouTube video
            // ------------------------------------------------

            val videoId =
                uri.getQueryParameter(
                    "v"
                )

            if (
                !videoId.isNullOrBlank()
            ) {

                return "youtube_watch_$videoId"
            }

            // ------------------------------------------------
            // YouTube shorts
            // ------------------------------------------------

            if (
                url.contains(
                    "/shorts/",
                    ignoreCase = true
                )
            ) {

                val shortsId =
                    uri.pathSegments
                        .lastOrNull()

                if (
                    !shortsId.isNullOrBlank()
                ) {

                    return "youtube_shorts_$shortsId"
                }
            }

            // ------------------------------------------------
            // Normal page
            // ------------------------------------------------

            val path =
                uri.path
                    ?.substringBefore("?")
                    ?.substringBefore("#")
                    ?.removeSuffix("/")
                    ?: ""

            "$host$path"

        } catch (
            _: Exception
        ) {

            url.lowercase()
        }
    }

    // ========================================================
    // NAVIGATION DISPATCH
    // ========================================================

    private fun dispatchNavigation(
        url: String,
        packageName: String,
        root: AccessibilityNodeInfo?,
        title: String?
    ) {

        if (root == null) {
            return
        }

        val uri =
            try {

                Uri.parse(
                    url
                )

            } catch (
                _: Exception
            ) {

                return
            }

        // ====================================================
        // YOUTUBE SEARCH
        // ====================================================

        val isYouTubeSearch =
            url.contains(
                "youtube.com/results",
                ignoreCase = true
            ) &&
                    url.contains(
                        "search_query=",
                        ignoreCase = true
                    )

        val searchQueryFromUrl =
            if (
                isYouTubeSearch
            ) {

                uri.getQueryParameter(
                    "search_query"
                )

            } else {

                null
            }

        // ====================================================
        // GOOGLE SEARCH
        // ====================================================

        val googleSearchQuery =
            if (
                url.contains(
                    "google.",
                    ignoreCase = true
                ) &&
                url.contains(
                    "/search",
                    ignoreCase = true
                )
            ) {

                uri.getQueryParameter(
                    "q"
                )

            } else {

                null
            }

        val searchQueryFromUrlFinal =
            searchQueryFromUrl
                ?: googleSearchQuery

        // ====================================================
        // METADATA
        // ====================================================

        val metadata =
            try {

                metadataResolver.extractMetadata(
                    root = root,
                    packageName = packageName,
                    url = url
                )

            } catch (
                _: Exception
            ) {

                null
            }

        val rawMetadataTitle =
            metadata?.title

        val visibleText =
            metadata?.visibleText

        // ====================================================
        // TITLE
        // ====================================================

        val sanitizedInputTitle =
            TitleSanitizer.cleanTitle(
                title
            )

        val sanitizedMetadataTitle =
            TitleSanitizer.cleanTitle(
                rawMetadataTitle
            )

        val finalTitle =
            when {

                !sanitizedInputTitle
                    .isNullOrBlank() ->
                    sanitizedInputTitle

                !sanitizedMetadataTitle
                    .isNullOrBlank() ->
                    sanitizedMetadataTitle

                else ->
                    rawMetadataTitle
            }

        // ====================================================
        // SEARCH FALLBACK
        // ====================================================

        val searchQuery =
            searchQueryFromUrlFinal
                ?: metadata?.searchQuery
                ?: UrlQueryExtractor.extractQueryFromUrl(
                    url
                )

        // ====================================================
        // TYPE
        // ====================================================

        val lowerPackage =
            packageName.lowercase()

        val type =
            when {

                lowerPackage.contains(
                    "youtube"
                ) &&
                        url.contains(
                            "watch?v=",
                            ignoreCase = true
                        ) ->
                    "youtube_video"

                lowerPackage.contains(
                    "youtube"
                ) &&
                        url.contains(
                            "/shorts/",
                            ignoreCase = true
                        ) ->
                    "youtube_shorts"

                lowerPackage.contains(
                    "youtube"
                ) &&
                        searchQueryFromUrlFinal != null ->
                    "youtube_search"

                lowerPackage.contains(
                    "instagram"
                ) ->
                    "instagram"

                lowerPackage.contains(
                    "tiktok"
                ) ->
                    "tiktok"

                lowerPackage.contains(
                    "facebook"
                ) ->
                    "facebook"

                lowerPackage.contains(
                    "twitter"
                ) ->
                    "x"

                else ->
                    "web"
            }

        // ====================================================
        // LOG
        // ====================================================

        Log.d(
            TAG,
            "=========================================="
        )

        Log.d(
            TAG,
            "🌐 NAVIGATION"
        )

        Log.d(
            TAG,
            "Package = $packageName"
        )

        Log.d(
            TAG,
            "URL = $url"
        )

        Log.d(
            TAG,
            "Title = $finalTitle"
        )

        Log.d(
            TAG,
            "Search = $searchQuery"
        )

        Log.d(
            TAG,
            "Type = $type"
        )

        Log.d(
            TAG,
            "=========================================="
        )

        // ====================================================
        // SEND TO TRACKER
        // ====================================================

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
                timestamp =
                    System.currentTimeMillis()
            )
        )
    }

    // ========================================================
    // EXTRACT BROWSER URL
    // ========================================================

    private fun extractBrowserUrl(
        root: AccessibilityNodeInfo,
        packageName: String
    ): String? {

        if (!isBrowserPackage(packageName)) {
            return null
        }

        var bestUrl: String? = null
        var bestScore = 0

        fun normalizeUrl(
            value: String
        ): String {

            return value
                .trim()
                .replace(
                    Regex("\\s+"),
                    ""
                )
        }

        fun isValidBrowserUrl(
            value: String
        ): Boolean {

            val text =
                value
                    .trim()

            if (text.length < 5) {
                return false
            }

            val lower =
                text.lowercase()

            // ---------------------------------------------
            // Explicit URL
            // ---------------------------------------------

            if (
                lower.startsWith("https://") ||
                lower.startsWith("http://")
            ) {
                return true
            }

            // ---------------------------------------------
            // www.example.com
            // ---------------------------------------------

            if (
                lower.startsWith("www.")
            ) {
                return true
            }

            // ---------------------------------------------
            // domain/path
            // ---------------------------------------------

            return Regex(
                "^[a-zA-Z0-9-]+(\\.[a-zA-Z0-9-]+)+(/.*)?$"
            ).matches(text)
        }

        fun scan(
            node: AccessibilityNodeInfo?,
            depth: Int
        ) {

            if (
                node == null ||
                depth > MAX_TREE_DEPTH
            ) {
                return
            }

            try {

                val text =
                    node.text
                        ?.toString()
                        ?.trim()
                        ?: ""

                val hint =
                    node.hintText
                        ?.toString()
                        ?.trim()
                        ?: ""

                val description =
                    node.contentDescription
                        ?.toString()
                        ?.trim()
                        ?: ""

                val viewId =
                    node.viewIdResourceName
                        ?.lowercase()
                        ?: ""

                val className =
                    node.className
                        ?.toString()
                        ?.lowercase()
                        ?: ""

                // =================================================
                // Candidate values
                // =================================================

                val candidates =
                    listOf(
                        text,
                        hint,
                        description
                    )

                for (candidate in candidates) {

                    if (candidate.isBlank()) {
                        continue
                    }

                    val normalized =
                        normalizeUrl(candidate)

                    if (!isValidBrowserUrl(normalized)) {
                        continue
                    }

                    var score = 0

                    // =================================================
                    // 1. Strong URL evidence
                    // =================================================

                    val lower =
                        normalized.lowercase()

                    if (
                        lower.startsWith("https://") ||
                        lower.startsWith("http://")
                    ) {
                        score += 10
                    }

                    if (
                        lower.startsWith("www.")
                    ) {
                        score += 8
                    }

                    // =================================================
                    // 2. URL-bar identifiers
                    // =================================================

                    if (
                        viewId.contains("url")
                    ) {
                        score += 8
                    }

                    if (
                        viewId.contains("address")
                    ) {
                        score += 8
                    }

                    if (
                        viewId.contains("omnibox")
                    ) {
                        score += 8
                    }

                    if (
                        viewId.contains("location")
                    ) {
                        score += 7
                    }

                    if (
                        viewId.contains("search_box")
                    ) {
                        score += 5
                    }

                    // =================================================
                    // 3. Search/address hints
                    // =================================================

                    if (
                        hint.contains(
                            "address",
                            ignoreCase = true
                        )
                    ) {
                        score += 6
                    }

                    if (
                        hint.contains(
                            "search",
                            ignoreCase = true
                        )
                    ) {
                        score += 4
                    }

                    if (
                        hint.contains(
                            "web",
                            ignoreCase = true
                        )
                    ) {
                        score += 3
                    }

                    // =================================================
                    // 4. Accessibility description
                    // =================================================

                    if (
                        description.contains(
                            "address",
                            ignoreCase = true
                        )
                    ) {
                        score += 6
                    }

                    if (
                        description.contains(
                            "url",
                            ignoreCase = true
                        )
                    ) {
                        score += 6
                    }

                    if (
                        description.contains(
                            "search",
                            ignoreCase = true
                        )
                    ) {
                        score += 3
                    }

                    // =================================================
                    // 5. Editable field
                    // =================================================

                    if (node.isEditable) {
                        score += 6
                    }

                    if (
                        className.contains("edittext")
                    ) {
                        score += 4
                    }

                    if (
                        className.contains("textfield")
                    ) {
                        score += 3
                    }

                    // =================================================
                    // 6. Focus
                    // =================================================

                    if (node.isFocused) {
                        score += 5
                    }

                    // =================================================
                    // 7. URL characteristics
                    // =================================================

                    if (
                        lower.contains("/")
                    ) {
                        score += 2
                    }

                    if (
                        lower.contains("?")
                    ) {
                        score += 2
                    }

                    if (
                        lower.contains("#")
                    ) {
                        score += 1
                    }

                    // =================================================
                    // 8. Avoid obvious non-address nodes
                    // =================================================

                    if (
                        className.contains("button")
                    ) {
                        score -= 5
                    }

                    if (
                        className.contains("image")
                    ) {
                        score -= 3
                    }

                    // =================================================
                    // Save strongest candidate
                    // =================================================

                    if (
                        score > bestScore
                    ) {

                        bestScore =
                            score

                        bestUrl =
                            normalized

                        Log.d(
                            TAG,
                            "🌐 URL candidate"
                        )

                        Log.d(
                            TAG,
                            "Package = $packageName"
                        )

                        Log.d(
                            TAG,
                            "Score = $score"
                        )

                        Log.d(
                            TAG,
                            "ID = $viewId"
                        )

                        Log.d(
                            TAG,
                            "Text = $normalized"
                        )
                    }
                }

                // =================================================
                // Children
                // =================================================

                for (
                i in 0 until node.childCount
                ) {

                    scan(
                        node.getChild(i),
                        depth + 1
                    )
                }

            } catch (
                _: Exception
            ) {
                // Accessibility node disappeared.
            }
        }

        scan(
            root,
            0
        )

        // =====================================================
        // Minimum confidence
        // =====================================================

        return if (
            bestScore >= 10
        ) {

            Log.d(
                TAG,
                "✅ Browser URL accepted"
            )

            Log.d(
                TAG,
                "Package = $packageName"
            )

            Log.d(
                TAG,
                "Score = $bestScore"
            )

            Log.d(
                TAG,
                "URL = $bestUrl"
            )

            bestUrl

        } else {

            Log.d(
                TAG,
                "❌ No confident browser URL"
            )

            null
        }
    }
    // ========================================================
    // INTERRUPT
    // ========================================================

    override fun onInterrupt() {

        Log.e(
            TAG,
            "⚠ Accessibility service interrupted"
        )
    }

    // ========================================================
    // DESTROY
    // ========================================================

    override fun onDestroy() {

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "🛑 DESTROYING BrowserAccessibilityService"
        )


        // ========================================================
        // 1. STOP PENDING SEARCH CALLBACK
        // ========================================================

        try {

            pendingSearchRunnable?.let { runnable ->

                searchHandler.removeCallbacks(
                    runnable
                )

            }

            pendingSearchRunnable = null

            searchHandler.removeCallbacksAndMessages(
                null
            )

            Log.d(
                TAG,
                "✅ Search callbacks removed"

            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "⚠️ Failed removing search callbacks",
                e
            )
        }


        // ========================================================
        // 2. DESTROY BROWSING TRACKER
        //
        // IMPORTANT:
        //
        // BrowserAccessibilityService owns this tracker.
        //
        // Do NOT leave the tracker alive after the accessibility
        // service is destroyed.
        // ========================================================

        try {

            val tracker =
                browsingTracker

            if (tracker != null) {

                Log.d(
                    TAG,
                    "🧹 Destroying BrowsingTracker"
                )

                tracker.destroy()

                Log.d(
                    TAG,
                    "✅ BrowsingTracker destroyed"

                )

            } else {

                Log.d(
                    TAG,
                    "ℹ️ BrowsingTracker already null"
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ BrowsingTracker destroy failed",
                e
            )

        } finally {

            browsingTracker = null
        }


        // ========================================================
        // 3. CLEAR CHILD ID
        // ========================================================

        childId = ""

        Log.d(
            TAG,
            "🧹 childId cleared"
        )


        // ========================================================
        // 4. CLEAR SEARCH DISPATCH STATE
        // ========================================================

        lastDispatchedKey = null

        lastDispatchTime = 0L


        // ========================================================
        // 5. CLEAR LAST SAVED NATIVE SEARCH
        // ========================================================

        lastSavedNativeSearchQuery = null

        lastSavedNativeSearchPackage = null

        lastSavedNativeSearchTime = 0L


        // ========================================================
        // 6. CLEAR SEARCH EVENT MEMORY
        // ========================================================

        lastSearchQuery = ""

        lastSearchPackage = ""

        lastSearchSavedTime = 0L


        // ========================================================
        // 7. CLEAR EVENT QUERY MEMORY
        // ========================================================

        lastEventQuery = ""

        lastEventPackage = ""

        lastEventQueryTime = 0L


        // ========================================================
        // 8. CLEAR ACCESSIBILITY SERVICE STATE
        // ========================================================

        try {

            rootInActiveWindow?.let { root ->

                root.recycle()

            }

        } catch (e: Exception) {

            // Some Android versions/nodes may already have been
            // recycled. Do not allow cleanup to crash the service.

            Log.d(
                TAG,
                "ℹ️ Accessibility root cleanup skipped"
            )
        }


        // ========================================================
        // 9. SUPER
        // ========================================================

        super.onDestroy()


        // ========================================================
        // 10. FINAL LOG
        // ========================================================

        Log.e(
            TAG,
            "=================================================="
        )

        Log.e(
            TAG,
            "❌ BrowserAccessibilityService DESTROYED"
        )

        Log.e(
            TAG,
            "=================================================="
        )
    }
}