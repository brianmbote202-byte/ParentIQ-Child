package com.parentalcontrol.childapp.service

import android.view.accessibility.AccessibilityEvent
import android.util.Log
import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper


/**
 * Monitors foreground app changes using AccessibilityService callbacks.
 * Does not require context cast.
 */
class ForegroundAppMonitor(
    private val accessibilityService: AccessibilityService,
    private val onAppDetected: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastPackage: String? = null
    private var running = false

    private val runnable = object : Runnable {
        override fun run() {
            if (!running) return

            val root = accessibilityService.rootInActiveWindow
            val packageName = root?.packageName?.toString()

            if (packageName != null && packageName != lastPackage) {
                lastPackage = packageName
                Log.d("ForegroundMonitor", "Foreground app: $packageName")
                onAppDetected(packageName)
            }

            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.post(runnable)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(runnable)
    }
}