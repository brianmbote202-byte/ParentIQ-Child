package com.parentalcontrol.childapp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.parentalcontrol.childapp.R

class AppBlockOverlayActivity : Activity() {



    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.overlay_block) // <-- use new layout name


        val packageName = intent.getStringExtra("blockedApp") ?: "App"

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        findViewById<TextView>(R.id.tvAppName).text = appName

        findViewById<TextView>(R.id.blockMessage).text =
            "$appName is blocked. Daily limit reached!"

        val used = AppUsageTracker(this).getUsedMinutes(packageName)

        findViewById<TextView>(R.id.tvUsage).text =
            "Today used • ${used} min"

        findViewById<TextView>(R.id.tvAppName).text = appName

        findViewById<TextView>(R.id.blockMessage).text =
            "$appName is blocked by parental controls"

        val textView = findViewById<TextView>(R.id.blockMessage)
        textView.text = "$appName is blocked. Daily limit reached!"

        val btn = findViewById<Button>(R.id.closeOverlay)
        btn.setOnClickListener { finish() }

        Log.d("BLOCK_DEBUG", "Received blockedApp = $packageName")

        //---------app timer logic---------
        val endTime = intent.getLongExtra("endTime", 0L)

        val remaining = endTime - System.currentTimeMillis()

        if (remaining > 0) {

            object : CountDownTimer(remaining, 1000) {

                override fun onTick(millisUntilFinished: Long) {

                    val hours = millisUntilFinished / 3600000
                    val minutes = (millisUntilFinished / 60000) % 60
                    val seconds = (millisUntilFinished / 1000) % 60

                    findViewById<TextView>(R.id.tvCountdown).text =
                        String.format("Unlocks in %02d:%02d:%02d", hours, minutes, seconds)
                }

                override fun onFinish() {
                    finish()
                }

            }.start()
        }
    }
}