package com.parentalcontrol.childapp.overlay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.parentalcontrol.childapp.R

object CountdownOverlayView {

    private val activeOverlays = mutableMapOf<String, View>()
    private val handlers = mutableMapOf<String, Handler>()

    fun show(context: Context, key: String, totalSeconds: Int) {

        // If already showing for this app, do nothing
        //if (activeOverlays.containsKey(key)) return
        if (isAnyOverlayShowing()) return

        // 🔥 Remove other overlays before showing countdown
        BlockOverlayView.removeAll()
        removeAll()

        val inflater = LayoutInflater.from(context)
        val overlayView = inflater.inflate(R.layout.overlay_countdown, null)
        val tvCountdown = overlayView.findViewById<TextView>(R.id.countdownMessage)

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            format = android.graphics.PixelFormat.TRANSLUCENT
        }

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        wm.addView(overlayView, params)
        activeOverlays[key] = overlayView

        val handler = Handler(Looper.getMainLooper())
        handlers[key] = handler

        var remaining = totalSeconds

        handler.post(object : Runnable {
            override fun run() {

                // If overlay removed externally, stop timer
                if (!activeOverlays.containsKey(key)) {
                    handler.removeCallbacks(this)
                    return
                }

                // Update timer text
                val minutes = remaining / 60
                val seconds = remaining % 60
                tvCountdown.text = String.format(
                    "Available in %02d:%02d",
                    minutes,
                    seconds
                )

                if (remaining <= 0) {
                    remove(key)
                    return
                }

                remaining--
                handler.postDelayed(this, 1000)
            }
        })
    }

    fun removeAll() {
        val overlays = activeOverlays.toMap()

        for ((key, overlay) in overlays) {
            handlers[key]?.removeCallbacksAndMessages(null)
            handlers.remove(key)

            val wm = overlay.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                wm.removeView(overlay)
            } catch (_: Exception) {}
        }

        activeOverlays.clear()
    }

    fun updateMessage(key: String, message: String) {
        val overlay = activeOverlays[key] ?: return
        val tvCountdown = overlay.findViewById<TextView>(R.id.countdownMessage)
        tvCountdown.text = message
    }

    fun remove(key: String) {
        handlers[key]?.removeCallbacksAndMessages(null)
        handlers.remove(key)

        val overlay = activeOverlays[key] ?: return
        val wm = overlay.context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        try {
            wm.removeView(overlay)
        } catch (_: Exception) {}
        activeOverlays.remove(key)
    }

    fun isAnyOverlayShowing(): Boolean {
        return activeOverlays.isNotEmpty()
    }
}