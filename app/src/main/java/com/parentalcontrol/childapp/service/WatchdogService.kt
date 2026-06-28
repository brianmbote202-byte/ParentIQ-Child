package com.parentalcontrol.childapp.service

import android.app.*
import android.content.*
import android.os.*
import androidx.core.content.ContextCompat
import com.parentalcontrol.childapp.vpn.DnsVpnService

class WatchdogService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val watchdog = object : Runnable {
        override fun run() {
            ContextCompat.startForegroundService(
                this@WatchdogService,
                Intent(this@WatchdogService, DnsVpnService::class.java)
            )
            handler.postDelayed(this, 15_000) // every 15 sec
        }
    }

    override fun onCreate() {
        super.onCreate()
        handler.post(watchdog)
    }

    override fun onDestroy() {
        handler.post(watchdog)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
