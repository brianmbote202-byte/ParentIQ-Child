package com.parentalcontrol.childapp.overlay

import android.content.Context
import android.content.Intent
import android.util.Log
import com.parentalcontrol.childapp.service.ScreenTimeOverlayActivity

object OverlayController {

    @Volatile
    private var isLocked = false

    fun lock(context: Context) {
        if (isLocked) return

        try {
            val intent = Intent(context, ScreenTimeOverlayActivity::class.java).apply {
                action = ScreenTimeOverlayActivity.ACTION_SHOW_OVERLAY
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
            isLocked = true
            Log.d("OverlayController", "Screen LOCKED")
        } catch (e: Exception) {
            Log.e("OverlayController", "Lock failed", e)
        }
    }

    fun unlock(context: Context) {
        if (!isLocked) return
        try {
            context.sendBroadcast(Intent(ScreenTimeOverlayActivity.ACTION_CLOSE_OVERLAY))
            isLocked = false
            Log.d("OverlayController", "Screen UNLOCKED")
        } catch (e: Exception) {
            Log.e("OverlayController", "Unlock failed", e)
        }
    }
}
