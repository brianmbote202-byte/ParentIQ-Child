package com.parentalcontrol.childapp.worker

import android.content.*
import androidx.core.content.ContextCompat
import androidx.work.*
import com.parentalcontrol.childapp.vpn.DnsVpnService

class RestartWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        ContextCompat.startForegroundService(
            applicationContext,
            Intent(applicationContext, DnsVpnService::class.java)
        )
        return Result.success()
    }
}
