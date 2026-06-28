package com.parentalcontrol.childapp.service

import android.content.Context
import android.content.Intent
import android.provider.Settings

object BlockOverlayLauncher {

    private var isShowing = false

    fun show(context: Context, domain: String, reason: String = "Blocked by Parent") {
        if (isShowing) return

        if (!Settings.canDrawOverlays(context)) return

        val intent = Intent(context, BlockOverlayActivity::class.java).apply {
            putExtra("domain", domain)
            putExtra("reason", reason)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        context.startActivity(intent)
        isShowing = true
    }

    fun remove(context: Context) {
        if (!isShowing) return

        val intent = Intent("CLOSE_BLOCK_OVERLAY")
        context.sendBroadcast(intent)

        isShowing = false
    }
}