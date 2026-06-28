package com.parentalcontrol.childapp.admin

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.app.admin.DeviceAdminReceiver
import com.parentalcontrol.childapp.receiver.MessageService

class ChildAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.d("ChildAdmin", "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Log.w("ChildAdmin", "Device Admin Disabled")

        notifyParentServer(context)
    }

    private fun notifyParentServer(context: Context) {

        try {

            val prefs = context.getSharedPreferences(
                "pc_parent_child_prefs",
                Context.MODE_PRIVATE
            )

            val childId = prefs.getString("child_id", "unknown_child") ?: "unknown_child"

            val intent = Intent(context, MessageService::class.java).apply {

                putExtra("childId", childId)
                putExtra("type", "SECURITY")
                putExtra("event", "ADMIN_DISABLED")
                putExtra("timestamp", System.currentTimeMillis())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }

        } catch (e: Exception) {
            Log.e("ChildAdmin", "Failed to notify parent", e)
        }
    }
}