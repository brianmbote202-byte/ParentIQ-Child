package com.parentalcontrol.childapp.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.parentalcontrol.childapp.R

class ScreenTimeOverlayActivity : AppCompatActivity() {

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_CLOSE_OVERLAY) finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Only exit on unlock
        if (intent?.action == ACTION_UNLOCK) {
            finish()
            return
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        setContentView(R.layout.activity_screen_time_overlay)
        setFinishOnTouchOutside(false)

        val remainingMillis = intent.getLongExtra(EXTRA_REMAINING_MILLIS, -1L)
        val blockedSite = intent.getBooleanExtra(EXTRA_BLOCKED_SITE, false)
        val infoText = findViewById<TextView>(R.id.lockMessage)

        infoText.text = when {
            blockedSite -> "This website is blocked"
            remainingMillis > 0 -> "Screen time remaining: ${remainingMillis / 60000} minutes"
            else -> "Screen time limit reached"
        }
    }

    override fun onBackPressed() {}

    override fun onStart() {
        super.onStart()
        registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE_OVERLAY))
    }

    override fun onStop() {
        unregisterReceiver(closeReceiver)
        super.onStop()
    }

    companion object {
        const val ACTION_SHOW_OVERLAY = "com.parentalcontrol.childapp.ACTION_SHOW_OVERLAY"
        const val ACTION_UNLOCK = "com.parentalcontrol.childapp.ACTION_UNLOCK"
        const val ACTION_CLOSE_OVERLAY = "com.parentalcontrol.childapp.ACTION_CLOSE_OVERLAY"
        const val EXTRA_REMAINING_MILLIS = "com.parentalcontrol.childapp.EXTRA_REMAINING_MILLIS"
        const val EXTRA_BLOCKED_SITE = "com.parentalcontrol.childapp.EXTRA_BLOCKED_SITE"
    }
}
