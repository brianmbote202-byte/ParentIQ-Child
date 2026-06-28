package com.parentalcontrol.childapp.core

import android.app.*
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import com.parentalcontrol.childapp.R

class GuardianService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var healthManager: SystemHealthManager

    override fun onCreate() {
        super.onCreate()

        startForeground(9999, createNotification())

        healthManager = SystemHealthManager(this)

        startLoop()
    }

    private fun startLoop() {

        handler.post(object : Runnable {
            override fun run() {

                healthManager.validateAndRepair()

                handler.postDelayed(this, 60_000) // every 1 min
            }
        })
    }

    private fun createNotification(): Notification {

        val channelId = "guardian_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "System Guardian",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Protection Active")
            .setContentText("System self-healing enabled")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null
}