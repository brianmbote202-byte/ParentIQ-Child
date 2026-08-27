package com.parentalcontrol.childapp.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parentalcontrol.childapp.overlay.BlockOverlayView
import com.parentalcontrol.childapp.overlay.CountdownOverlayView
import kotlin.math.abs

enum class OverlayType {
    COUNTDOWN,
    BLOCK
}

data class OverlayState(
    val type: OverlayType,
    var remainingSeconds: Int? = null,
    val handler: Handler = Handler(Looper.getMainLooper()),
    var runnable: Runnable? = null
)

class OverlayManager(
    private val context: Context
) {

    companion object {

        private const val TAG = "OverlayManager"

        private const val COUNTDOWN_INTERVAL = 1000L

        /*
         * Small tolerance prevents the tracker from
         * constantly rebuilding the countdown overlay.
         */
        private const val COUNTDOWN_TOLERANCE = 1
    }

    /*
     * ---------------------------------------------------------
     * STATE
     * ---------------------------------------------------------
     */

    private val overlayStateMap =
        mutableMapOf<String, OverlayState>()

    private val mainHandler =
        Handler(Looper.getMainLooper())

    @Volatile
    private var destroyed = false


    /*
     * =========================================================
     * SHOW OVERLAY
     * =========================================================
     */

    fun showOverlay(
        appPackage: String,
        type: OverlayType,
        message: String,
        countdownSeconds: Int? = null
    ) {

        if (appPackage.isBlank()) {
            Log.w(
                TAG,
                "showOverlay ignored: empty package"
            )
            return
        }

        if (destroyed) {
            Log.d(
                TAG,
                "showOverlay ignored: manager destroyed"
            )
            return
        }

        mainHandler.post {

            if (destroyed) {
                return@post
            }

            try {

                showOverlayInternal(
                    appPackage = appPackage,
                    type = type,
                    message = message,
                    countdownSeconds = countdownSeconds
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "showOverlay failed: $appPackage",
                    e
                )
            }
        }
    }


    /*
     * =========================================================
     * INTERNAL SHOW
     * =========================================================
     */

    private fun showOverlayInternal(
        appPackage: String,
        type: OverlayType,
        message: String,
        countdownSeconds: Int?
    ) {

        if (destroyed) {
            return
        }

        val existing =
            overlayStateMap[appPackage]


        /*
         * -----------------------------------------------------
         * BLOCK → BLOCK
         * -----------------------------------------------------
         *
         * Don't recreate the same block overlay repeatedly.
         */

        if (
            existing?.type == OverlayType.BLOCK &&
            type == OverlayType.BLOCK
        ) {

            Log.d(
                TAG,
                "BLOCK already active: $appPackage"
            )

            return
        }


        /*
         * -----------------------------------------------------
         * COUNTDOWN → COUNTDOWN
         * -----------------------------------------------------
         */

        if (
            existing?.type == OverlayType.COUNTDOWN &&
            type == OverlayType.COUNTDOWN
        ) {

            val current =
                existing.remainingSeconds ?: 0

            val requested =
                countdownSeconds ?: 0

            /*
             * The tracker can call this every time an
             * accessibility event occurs.
             *
             * Don't restart the countdown if it is
             * already essentially at the same value.
             */

            if (
                abs(current - requested) <=
                COUNTDOWN_TOLERANCE
            ) {

                Log.d(
                    TAG,
                    "Countdown already active: " +
                            "$appPackage " +
                            "current=$current " +
                            "requested=$requested"
                )

                return
            }

            /*
             * Countdown changed.
             *
             * Replace the old one.
             */

            Log.d(
                TAG,
                "Updating countdown: " +
                        "$appPackage " +
                        "current=$current " +
                        "requested=$requested"
            )

            removeOverlayInternal(
                appPackage
            )
        }


        /*
         * -----------------------------------------------------
         * TYPE CHANGED
         * -----------------------------------------------------
         *
         * Example:
         *
         * COUNTDOWN
         *      ↓
         * BLOCK
         *
         * BLOCK must replace countdown.
         */

        val currentAfterCheck =
            overlayStateMap[appPackage]

        if (
            currentAfterCheck != null &&
            currentAfterCheck.type != type
        ) {

            Log.d(
                TAG,
                "Replacing ${currentAfterCheck.type} " +
                        "with $type for $appPackage"
            )

            removeOverlayInternal(
                appPackage
            )
        }


        /*
         * -----------------------------------------------------
         * CREATE STATE
         * -----------------------------------------------------
         */

        val initialSeconds =
            if (type == OverlayType.COUNTDOWN) {
                (countdownSeconds ?: 0)
                    .coerceAtLeast(0)
            } else {
                null
            }

        val state =
            OverlayState(
                type = type,
                remainingSeconds = initialSeconds
            )

        overlayStateMap[appPackage] =
            state


        /*
         * =====================================================
         * BLOCK
         * =====================================================
         */

        if (type == OverlayType.BLOCK) {

            Log.d(
                TAG,
                "================================"
            )

            Log.d(
                TAG,
                "SHOW BLOCK OVERLAY"
            )

            Log.d(
                TAG,
                "Package: $appPackage"
            )

            Log.d(
                TAG,
                "Message: $message"
            )

            Log.d(
                TAG,
                "================================"
            )

            try {

                BlockOverlayView.show(
                    context,
                    appPackage,
                    message
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "BlockOverlayView.show failed",
                    e
                )

                overlayStateMap.remove(
                    appPackage
                )
            }

            /*
             * IMPORTANT:
             *
             * BLOCK is persistent.
             *
             * It remains until BrowsingTracker or another
             * component explicitly calls:
             *
             * removeOverlay(appPackage)
             */

            return
        }


        /*
         * =====================================================
         * COUNTDOWN
         * =====================================================
         */

        if (initialSeconds == null || initialSeconds <= 0) {

            Log.d(
                TAG,
                "Countdown <= 0, not showing: $appPackage"
            )

            overlayStateMap.remove(
                appPackage
            )

            return
        }

        Log.d(
            TAG,
            "================================"
        )

        Log.d(
            TAG,
            "SHOW COUNTDOWN OVERLAY"
        )

        Log.d(
            TAG,
            "Package: $appPackage"
        )

        Log.d(
            TAG,
            "Seconds: $initialSeconds"
        )

        Log.d(
            TAG,
            "================================"
        )

        try {

            CountdownOverlayView.show(
                context,
                appPackage,
                initialSeconds
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "CountdownOverlayView.show failed",
                e
            )

            overlayStateMap.remove(
                appPackage
            )

            return
        }


        /*
         * Start countdown.
         */

        startCountdown(
            appPackage = appPackage,
            state = state,
            initialSeconds = initialSeconds
        )
    }


    /*
     * =========================================================
     * COUNTDOWN ENGINE
     * =========================================================
     */

    private fun startCountdown(
        appPackage: String,
        state: OverlayState,
        initialSeconds: Int
    ) {

        var remaining =
            initialSeconds


        /*
         * Immediately show initial value.
         */

        updateCountdownDisplay(
            appPackage,
            remaining
        )


        val runnable =
            object : Runnable {

                override fun run() {

                    /*
                     * Check that this is still the
                     * currently active state.
                     */

                    val currentState =
                        overlayStateMap[appPackage]

                    if (currentState !== state) {

                        Log.d(
                            TAG,
                            "Countdown cancelled: $appPackage"
                        )

                        return
                    }


                    /*
                     * Manager destroyed.
                     */

                    if (destroyed) {
                        return
                    }


                    /*
                     * Countdown finished.
                     */

                    if (remaining <= 0) {

                        Log.d(
                            TAG,
                            "Countdown finished: $appPackage"
                        )

                        removeOverlayInternal(
                            appPackage
                        )

                        return
                    }


                    /*
                     * Decrease.
                     */

                    remaining--

                    state.remainingSeconds =
                        remaining


                    /*
                     * Update display.
                     */

                    updateCountdownDisplay(
                        appPackage,
                        remaining
                    )


                    /*
                     * Continue.
                     */

                    if (
                        overlayStateMap[appPackage] === state &&
                        !destroyed &&
                        remaining > 0
                    ) {

                        state.handler.postDelayed(
                            this,
                            COUNTDOWN_INTERVAL
                        )
                    }
                }
            }


        state.runnable =
            runnable


        /*
         * First decrement occurs after 1 second.
         */

        state.handler.postDelayed(
            runnable,
            COUNTDOWN_INTERVAL
        )
    }


    /*
     * =========================================================
     * UPDATE COUNTDOWN DISPLAY
     * =========================================================
     */

    private fun updateCountdownDisplay(
        appPackage: String,
        remainingSeconds: Int
    ) {

        val safeSeconds =
            remainingSeconds.coerceAtLeast(0)

        val minutes =
            safeSeconds / 60

        val seconds =
            safeSeconds % 60

        val display =
            String.format(
                "Available in %02d:%02d",
                minutes,
                seconds
            )

        try {

            CountdownOverlayView.updateMessage(
                appPackage,
                display
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Countdown UI update failed: $appPackage",
                e
            )
        }
    }


    /*
     * =========================================================
     * REMOVE OVERLAY
     * =========================================================
     */

    fun removeOverlay(
        appPackage: String
    ) {

        if (appPackage.isBlank()) {
            return
        }

        mainHandler.post {

            try {

                removeOverlayInternal(
                    appPackage
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "removeOverlay failed: $appPackage",
                    e
                )
            }
        }
    }


    /*
     * =========================================================
     * INTERNAL REMOVE
     * =========================================================
     */

    private fun removeOverlayInternal(
        appPackage: String
    ) {

        val state =
            overlayStateMap.remove(
                appPackage
            )


        /*
         * -----------------------------------------------------
         * No manager state.
         *
         * Still clean actual views because a previous crash
         * could have left a view behind.
         * -----------------------------------------------------
         */

        if (state == null) {

            Log.d(
                TAG,
                "No manager state: $appPackage"
            )

            try {
                BlockOverlayView.remove(
                    appPackage
                )
            } catch (_: Exception) {
            }

            try {
                CountdownOverlayView.remove(
                    appPackage
                )
            } catch (_: Exception) {
            }

            return
        }


        Log.d(
            TAG,
            "Removing ${state.type}: $appPackage"
        )


        /*
         * -----------------------------------------------------
         * Stop countdown
         * -----------------------------------------------------
         */

        try {

            state.runnable?.let {
                state.handler.removeCallbacks(
                    it
                )
            }

            state.handler.removeCallbacksAndMessages(
                null
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed stopping handler: $appPackage",
                e
            )
        }


        /*
         * -----------------------------------------------------
         * Remove correct view
         * -----------------------------------------------------
         */

        try {

            when (state.type) {

                OverlayType.BLOCK -> {

                    BlockOverlayView.remove(
                        appPackage
                    )
                }

                OverlayType.COUNTDOWN -> {

                    CountdownOverlayView.remove(
                        appPackage
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed removing view: $appPackage",
                e
            )
        }


        /*
         * -----------------------------------------------------
         * Extra safety cleanup
         * -----------------------------------------------------
         *
         * If the actual view and manager state became
         * inconsistent, remove both types.
         */

        try {

            BlockOverlayView.remove(
                appPackage
            )

        } catch (_: Exception) {
        }

        try {

            CountdownOverlayView.remove(
                appPackage
            )

        } catch (_: Exception) {
        }
    }


    /*
     * =========================================================
     * REMOVE ALL
     * =========================================================
     */

    fun removeAllOverlays() {

        mainHandler.post {

            val packages =
                overlayStateMap.keys.toList()

            Log.d(
                TAG,
                "Removing ${packages.size} overlays"
            )

            packages.forEach { appPackage ->

                try {

                    removeOverlayInternal(
                        appPackage
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Failed removing: $appPackage",
                        e
                    )
                }
            }

            overlayStateMap.clear()

            Log.d(
                TAG,
                "All overlays removed"
            )
        }
    }


    /*
     * =========================================================
     * IS SHOWING
     * =========================================================
     */

    fun isOverlayShowing(
        appPackage: String
    ): Boolean {

        if (appPackage.isBlank()) {
            return false
        }

        /*
         * This method is normally called from the
         * AccessibilityService/main thread.
         *
         * Volatile destroyed protects lifecycle checks.
         */
        return overlayStateMap.containsKey(
            appPackage
        )
    }


    /*
     * =========================================================
     * GET TYPE
     * =========================================================
     */

    fun getOverlayType(
        appPackage: String
    ): OverlayType? {

        return overlayStateMap[
            appPackage
        ]?.type
    }


    /*
     * =========================================================
     * GET REMAINING
     * =========================================================
     */

    fun getRemainingSeconds(
        appPackage: String
    ): Int? {

        return overlayStateMap[
            appPackage
        ]?.remainingSeconds
    }


    /*
     * =========================================================
     * CONVENIENCE: BLOCK
     * =========================================================
     */

    fun showBlock(
        appPackage: String,
        message: String
    ) {

        showOverlay(
            appPackage = appPackage,
            type = OverlayType.BLOCK,
            message = message
        )
    }


    /*
     * =========================================================
     * CONVENIENCE: COUNTDOWN
     * =========================================================
     */

    fun showCountdown(
        appPackage: String,
        seconds: Int,
        message: String = "Available soon"
    ) {

        if (seconds <= 0) {

            Log.d(
                TAG,
                "Ignoring countdown <= 0: $appPackage"
            )

            return
        }

        showOverlay(
            appPackage = appPackage,
            type = OverlayType.COUNTDOWN,
            message = message,
            countdownSeconds = seconds
        )
    }


    /*
     * =========================================================
     * CLEAR APP
     * =========================================================
     */

    fun clearApp(
        appPackage: String
    ) {

        removeOverlay(
            appPackage
        )
    }


    /*
     * =========================================================
     * DESTROY
     * =========================================================
     */

    fun destroy() {

        if (destroyed) {
            return
        }

        destroyed = true

        Log.d(
            TAG,
            "Destroying OverlayManager"
        )

        mainHandler.post {

            val packages =
                overlayStateMap.keys.toList()

            packages.forEach { appPackage ->

                try {

                    removeOverlayInternal(
                        appPackage
                    )

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "Destroy cleanup failed: $appPackage",
                        e
                    )
                }
            }

            overlayStateMap.clear()

            mainHandler.removeCallbacksAndMessages(
                null
            )

            Log.d(
                TAG,
                "OverlayManager destroyed"
            )
        }
    }
}