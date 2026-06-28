package com.parentalcontrol.childapp.service

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.TextView
import com.parentalcontrol.childapp.R

class ScreenBlockOverlayActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🔒 Force overlay behavior
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        setContentView(R.layout.activity_screen_block)

        val reason = intent.getStringExtra("reason") ?: "This website is blocked"
        findViewById<TextView>(R.id.block_reason).text = reason

        enableLockTask() // 🔥 blocks Home + Recents
    }

    private fun enableLockTask() {
        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (am.lockTaskModeState == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask()
            }
        } catch (e: Exception) {
            // Some devices restrict this – ignore safely
        }
    }

    override fun onBackPressed() {
        // ❌ Disable back button completely
    }

    override fun onDestroy() {
        try {
            stopLockTask()
        } catch (_: Exception) {}
        super.onDestroy()
    }
}
