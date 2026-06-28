package com.parentalcontrol.childapp.ui.helpers

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView

class InstructionOverlayHelper(private val activity: Activity) {

    private var instructionView: TextView? = null
    private var currentStep = 0.0
    private val handler = Handler(Looper.getMainLooper())

    fun show(step: Double, message: String) {
        if (currentStep == step) return
        currentStep = step

        if (instructionView == null) {
            instructionView = TextView(activity).apply {
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 16f
                setPadding(40, 40, 40, 40)
                gravity = Gravity.CENTER
            }
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            params.gravity = Gravity.BOTTOM
            activity.addContentView(instructionView, params)
        }

        instructionView?.apply {
            text = message
            bringToFront()
            visibility = View.VISIBLE
        }
    }

    fun hide() {
        instructionView?.visibility = View.GONE
        currentStep = 0.0
    }

    fun postDelayed(delayMs: Long, block: () -> Unit) {
        handler.postDelayed(block, delayMs)
    }

    fun removeCallbacks() {
        handler.removeCallbacksAndMessages(null)
    }
}
