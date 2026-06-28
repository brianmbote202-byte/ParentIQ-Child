package com.parentalcontrol.childapp.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.graphics.*
import android.view.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.interpolator.view.animation.FastOutSlowInInterpolator

class InstructionGuideOverlay(private val activity: Activity) {

    private var overlayView: GuideView? = null

    fun show(
        targetView: View,
        title: String,
        message: String,
        onNext: (() -> Unit)? = null
    ) {
        hide()

        val root = activity.window.decorView as ViewGroup

        val location = IntArray(2)
        targetView.getLocationOnScreen(location)

        val rect = Rect(
            location[0],
            location[1],
            location[0] + targetView.width,
            location[1] + targetView.height
        )

        overlayView = GuideView(activity, rect, title, message, onNext)

        root.addView(
            overlayView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun hide() {
        val root = activity.window.decorView as ViewGroup
        overlayView?.let {
            root.removeView(it)
            overlayView = null
        }
    }

    // ==========================================================
    // INTERNAL VIEW
    // ==========================================================
    private class GuideView(
        activity: Activity,
        private val targetRect: Rect,
        private val title: String,
        private val message: String,
        private val onNext: (() -> Unit)?
    ) : FrameLayout(activity) {

        private val paintScrim = Paint().apply {
            color = Color.parseColor("#B3000000") // dark overlay
        }

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }

        private val pulsePaint = Paint().apply {
            color = Color.parseColor("#4DFFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        private var pulseRadius = 0f
        private var pulseAnimator: ValueAnimator? = null

        private val textContainer: View

        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_HARDWARE, null)

            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )

            // Inflate simple text UI
            textContainer = createTextContainer(activity)
            addView(textContainer)

            startPulseAnimation()

            setOnClickListener {
                onNext?.invoke()
                (parent as? ViewGroup)?.removeView(this)
            }
        }

        private fun createTextContainer(activity: Activity): View {
            val layout = FrameLayout(activity).apply {
                setPadding(40, 40, 40, 40)
            }

            val titleView = TextView(activity).apply {
                text = this@GuideView.title
                setTextColor(Color.WHITE)
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            }

            val messageView = TextView(activity).apply {
                text = this@GuideView.message
                setTextColor(Color.LTGRAY)
                textSize = 14f
                setPadding(0, 20, 0, 0)
            }

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(titleView)
                addView(messageView)
                setBackgroundColor(Color.parseColor("#CC000000"))
                setPadding(30, 30, 30, 30)
            }

            layout.addView(container)

            // Position below target
            layout.post {
                val y = targetRect.bottom + 40
                layout.x = 60f
                layout.y = y.toFloat()
            }

            return layout
        }

        private fun startPulseAnimation() {
            pulseAnimator = ValueAnimator.ofFloat(0f, 40f).apply {
                duration = 1000
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                interpolator = FastOutSlowInInterpolator()

                addUpdateListener {
                    pulseRadius = it.animatedValue as Float
                    invalidate()
                }

                start()
            }
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            pulseAnimator?.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)

            // draw scrim
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paintScrim)

            // clear focus area
            val radius = 25f
            canvas.drawRoundRect(
                targetRect.left.toFloat() - 20,
                targetRect.top.toFloat() - 20,
                targetRect.right.toFloat() + 20,
                targetRect.bottom.toFloat() + 20,
                radius,
                radius,
                clearPaint
            )

            // pulse ring
            val cx = targetRect.exactCenterX()
            val cy = targetRect.exactCenterY()

            canvas.drawCircle(cx, cy, (targetRect.width() / 2) + pulseRadius, pulsePaint)

            canvas.restoreToCount(layer)
        }
    }
}