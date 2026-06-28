package com.parentalcontrol.childapp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.parentalcontrol.childapp.R

object NotificationHelper {

    const val CHANNEL_ID = "monitoring_channel"

    fun build(context: Context): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Child Monitoring",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Child protection active")
            .setContentText("Monitoring screen usage")
            .setSmallIcon(R.drawable.ic_lock) // ensure this exists
            .setOngoing(true)
            .build()
    }
}
