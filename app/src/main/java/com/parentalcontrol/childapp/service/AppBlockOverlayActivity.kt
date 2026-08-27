package com.parentalcontrol.childapp.service

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
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
        Log.e("OVERLAY_ACTIVITY", "onCreate() called")
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

        val reason = intent.getStringExtra("reason") ?: "LIMIT"

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }

        findViewById<TextView>(R.id.tvAppName).text = appName

        //findViewById<TextView>(R.id.blockMessage).text =
           // "$appName is blocked. Daily limit reached!"

        val used = AppUsageTracker(this).getUsedMinutes(packageName)

        findViewById<TextView>(R.id.tvUsage).text =
            "Today used • ${used} min"

        findViewById<TextView>(R.id.tvAppName).text = appName

        //findViewById<TextView>(R.id.blockMessage).text =
            //"$appName is blocked by parental controls"

       // val textView = findViewById<TextView>(R.id.blockMessage)
        //textView.text = "$appName is blocked. Daily limit reached!"

        val blockMessage = findViewById<TextView>(R.id.blockMessage)

        when (reason) {

            "PARENT_BLOCK" -> {
                blockMessage.text =
                    "$appName has been blocked by your parent."
            }

            "LIMIT" -> {
                blockMessage.text =
                    "$appName has reached today's usage limit."
            }

            "NIGHT" -> {
                blockMessage.text =
                    "$appName is unavailable after 9 PM."
            }

            "SCHEDULE" -> {
                blockMessage.text =
                    "$appName is unavailable during this scheduled time."
            }

            else -> {
                blockMessage.text =
                    "$appName is blocked."
            }
        }

        val btn = findViewById<Button>(R.id.closeOverlay)
        btn.setOnClickListener {

            val home = Intent(Intent.ACTION_MAIN)
            home.addCategory(Intent.CATEGORY_HOME)
            home.flags = Intent.FLAG_ACTIVITY_NEW_TASK

            startActivity(home)

            finish()
        }

        Log.d("BLOCK_DEBUG", "Received blockedApp = $packageName")

        //---------app timer logic---------
        val endTime = intent.getLongExtra("endTime", 0L)

        val countdown = findViewById<TextView>(R.id.tvCountdown)

        val remaining = endTime - System.currentTimeMillis()

        if (endTime > 0 && remaining > 0) {

            countdown.visibility = TextView.VISIBLE

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
        else {

            countdown.visibility = TextView.GONE
        }
    }
}