package com.parentalcontrol.childapp.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.parentalcontrol.childapp.overlay.BlockOverlayView
import com.parentalcontrol.childapp.overlay.CountdownOverlayView

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
    }

    private val overlayStateMap =
        mutableMapOf<String, OverlayState>()

    fun showOverlay(
        appPackage: String,
        type: OverlayType,
        message: String,
        countdownSeconds: Int? = null
    ) {

        val existing = overlayStateMap[appPackage]

        // Avoid duplicate overlays
        if (existing?.type == type) {

            if (
                type == OverlayType.BLOCK ||
                existing.remainingSeconds == countdownSeconds
            ) {

                Log.d(
                    TAG,
                    "Overlay already showing for $appPackage"
                )

                return
            }
        }

        removeOverlay(appPackage)

        val state =
            OverlayState(
                type = type,
                remainingSeconds = countdownSeconds
            )

        overlayStateMap[appPackage] = state

        when (type) {

            OverlayType.BLOCK -> {

                Log.d(
                    TAG,
                    "Showing BLOCK overlay for $appPackage"
                )

                BlockOverlayView.show(
                    context,
                    appPackage,
                    message
                )

                // IMPORTANT:
                // DO NOT auto remove after 5 seconds
                // Parent must explicitly unblock app
            }

            OverlayType.COUNTDOWN -> {

                var remaining =
                    countdownSeconds ?: 0

                Log.d(
                    TAG,
                    "Showing COUNTDOWN overlay for $appPackage : $remaining sec"
                )

                CountdownOverlayView.show(
                    context,
                    appPackage,
                    remaining
                )

                state.runnable =
                    object : Runnable {

                        override fun run() {

                            if (
                                !overlayStateMap.containsKey(appPackage)
                            ) {
                                return
                            }

                            if (remaining <= 0) {

                                Log.d(
                                    TAG,
                                    "Countdown finished for $appPackage"
                                )

                                removeOverlay(appPackage)

                                return
                            }

                            CountdownOverlayView.updateMessage(
                                appPackage,
                                String.format(
                                    "Available in %02d:%02d",
                                    remaining / 60,
                                    remaining % 60
                                )
                            )

                            remaining--

                            state.remainingSeconds =
                                remaining

                            state.handler.postDelayed(
                                this,
                                1000
                            )
                        }
                    }

                state.handler.post(
                    state.runnable!!
                )
            }
        }
    }

    fun removeOverlay(
        appPackage: String
    ) {

        val state =
            overlayStateMap[appPackage]
                ?: return

        Log.d(
            TAG,
            "Removing overlay for $appPackage"
        )

        try {

            state.handler.removeCallbacksAndMessages(
                null
            )

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
                "Overlay removal failed",
                e
            )

        } finally {

            overlayStateMap.remove(
                appPackage
            )
        }
    }

    fun removeAllOverlays() {

        val packages =
            overlayStateMap.keys.toList()

        packages.forEach {

            removeOverlay(it)
        }

        Log.d(
            TAG,
            "All overlays removed"
        )
    }

    fun isOverlayShowing(
        appPackage: String
    ): Boolean {

        return overlayStateMap.containsKey(
            appPackage
        )
    }
}