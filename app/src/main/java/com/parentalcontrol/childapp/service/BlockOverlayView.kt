package com.parentalcontrol.childapp.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.parentalcontrol.childapp.R

object BlockOverlayView {

    private const val TAG = "BLOCK_OVERLAY"

    private val activeOverlays = mutableMapOf<String, View>()

    fun show(
        context: Context,
        key: String,
        message: String
    ) {

        try {

            Log.d(TAG, "show() called for: $key")

            if (activeOverlays.containsKey(key)) {
                Log.d(TAG, "Overlay already exists")
                return
            }

            val wm =
                context.getSystemService(Context.WINDOW_SERVICE)
                        as WindowManager

            val overlayView =
                LayoutInflater.from(context)
                    .inflate(
                        R.layout.overlay_block,
                        null,
                        false
                    )

            // Message
            overlayView.findViewById<TextView>(R.id.blockMessage)?.text =
                message

            // Title
            overlayView.findViewById<TextView>(R.id.tvOverlayTitle)?.text =
                "Website Blocked"

            // App/domain label
            overlayView.findViewById<TextView>(R.id.tvAppName)?.text =
                key

            // Hide countdown
            overlayView.findViewById<TextView>(R.id.tvCountdown)?.visibility =
                View.GONE

            // Hide usage
            overlayView.findViewById<TextView>(R.id.tvUsage)?.visibility =
                View.GONE

            // Close button
            overlayView.findViewById<Button>(R.id.closeOverlay)
                ?.setOnClickListener {

                    Log.d(TAG, "Close clicked")

                    remove(key)
                }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

            params.gravity = Gravity.CENTER

            wm.addView(
                overlayView,
                params
            )

            activeOverlays[key] = overlayView

            Log.d(
                TAG,
                "Overlay added successfully"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to show overlay",
                e
            )
        }
    }

    fun remove(key: String) {

        try {

            val overlay =
                activeOverlays[key]
                    ?: return

            val wm =
                overlay.context.getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager

            wm.removeViewImmediate(overlay)

            activeOverlays.remove(key)

            Log.d(
                TAG,
                "Overlay removed: $key"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Remove failed",
                e
            )
        }
    }

    fun removeAll() {

        try {

            val copy =
                activeOverlays.toMap()

            copy.forEach { (_, overlay) ->

                try {

                    val wm =
                        overlay.context.getSystemService(
                            Context.WINDOW_SERVICE
                        ) as WindowManager

                    wm.removeViewImmediate(overlay)

                } catch (_: Exception) {
                }
            }

            activeOverlays.clear()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "removeAll failed",
                e
            )
        }
    }

    fun isAnyOverlayShowing(): Boolean {
        return activeOverlays.isNotEmpty()
    }

    fun isShowing(key: String): Boolean {
        return activeOverlays.containsKey(key)
    }
}