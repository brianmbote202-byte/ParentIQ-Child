package com.parentalcontrol.childapp.overlay

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.service.BrowsingTracker

class BlockWebsiteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        )

        setContentView(R.layout.activity_block_website)

        val blockedUrl =
            intent.getStringExtra("blocked_url")
                ?: "Blocked Website"

        findViewById<TextView>(R.id.tvBlockedUrl).text =
            blockedUrl

        /*findViewById<Button>(R.id.btnDismissOverlay)
            .setOnClickListener {

                BrowsingTracker.blockedScreenShowing = false
                finish()
            }

        enableLockTask()*/
    }

    private fun enableLockTask() {
        try {

            val am =
                getSystemService(Context.ACTIVITY_SERVICE)
                        as ActivityManager

            if (
                am.lockTaskModeState ==
                ActivityManager.LOCK_TASK_MODE_NONE
            ) {
                startLockTask()
            }

        } catch (_: Exception) {
        }
    }

    override fun onBackPressed() {
        // Block back button
    }

    override fun onDestroy() {

        BrowsingTracker.blockedScreenShowing = false

        try {
            stopLockTask()
        } catch (_: Exception) {
        }

        super.onDestroy()
    }
}