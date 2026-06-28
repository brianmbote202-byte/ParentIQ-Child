package com.parentalcontrol.childapp.ui

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.parentalcontrol.childapp.R

class SmartPointerGuide(
    private val activity: Activity
) {

    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pulseRunnable: Runnable? = null

    enum class Position {
        CENTER, TOP_RIGHT, MIDDLE_RIGHT, BOTTOM_CENTER
    }

    fun show(message: String, position: Position = Position.CENTER) {

        hide() // always reset first

        val root = activity.window.decorView as ViewGroup
        val view = LayoutInflater.from(activity)
            .inflate(R.layout.overlay_pointer, root, false)

        val text = view.findViewById<TextView>(R.id.hintText)
        val arrow = view.findViewById<View>(R.id.arrow)

        text.text = message

        applyPosition(arrow, position)

        root.addView(view)
        overlayView = view

        startPulse(arrow)
    }

    private fun applyPosition(view: View, position: Position) {
        when (position) {

            Position.CENTER -> {
                view.translationX = 0f
                view.translationY = 0f
            }

            Position.TOP_RIGHT -> {
                view.translationX = 250f
                view.translationY = -400f
            }

            Position.MIDDLE_RIGHT -> {
                view.translationX = 300f
                view.translationY = 0f
            }

            Position.BOTTOM_CENTER -> {
                view.translationY = 500f
            }
        }
    }

    private fun startPulse(view: View) {

        val runnable = object : Runnable {
            override fun run() {

                view.animate()
                    .scaleX(1.3f)
                    .scaleY(1.3f)
                    .alpha(0.5f)
                    .setDuration(500)
                    .withEndAction {
                        view.scaleX = 1f
                        view.scaleY = 1f
                        view.alpha = 1f
                    }

                handler.postDelayed(this, 900)
            }
        }

        pulseRunnable = runnable
        handler.post(runnable)
    }

    fun hide() {
        pulseRunnable?.let { handler.removeCallbacks(it) }
        pulseRunnable = null

        overlayView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
        }

        overlayView = null
    }
}