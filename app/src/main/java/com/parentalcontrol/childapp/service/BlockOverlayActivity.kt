package com.parentalcontrol.childapp.service

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.util.Locale
import java.util.Date
import com.parentalcontrol.childapp.R
import java.text.SimpleDateFormat

@Suppress("DEPRECATION")
class BlockOverlayActivity : Activity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setFinishOnTouchOutside(false)

        BrowsingTracker.blockedScreenShowing = true

        setContentView(R.layout.activity_block_overlay)

        //----------animate the card------
        val card = findViewById<View>(R.id.blockCard)

        card.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(350)
            .start()

        val domain =
            intent.getStringExtra("domain")
                ?: "Unknown Website"

        //--------animate the lock--------
        val lock = findViewById<ImageView>(R.id.imgLock)

        lock.animate()
            .scaleX(1.15f)
            .scaleY(1.15f)
            .setDuration(900)
            .withEndAction {

                lock.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(900)
                    .withEndAction {

                        lock.animate()
                            .scaleX(1.15f)
                            .scaleY(1.15f)
                            .setDuration(900)
                            .start()

                    }
                    .start()

            }
            .start()

        val reason =
            intent.getStringExtra("reason")
                ?: "Blocked by Parent"

        findViewById<TextView>(R.id.txtBlockedDomain)
            .text = domain
            .removePrefix("www.")

        findViewById<TextView>(R.id.txtBlockedReason)
            .text = reason

        findViewById<Button>(R.id.btnClose)
            ?.setOnClickListener {
                finish()
            }

        //--------------animate current time dynamically
        val currentTime =
            SimpleDateFormat(
                "hh:mm a",
                Locale.getDefault()
            ).format(Date())

        findViewById<TextView>(R.id.txtTime)
            .text = "Blocked at $currentTime"
    }

    override fun onDestroy() {
        BrowsingTracker.blockedScreenShowing = false
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

}
