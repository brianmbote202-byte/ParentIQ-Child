package com.parentalcontrol.childapp.vpn

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.parentalcontrol.childapp.R
import java.io.FileInputStream
import com.parentalcontrol.childapp.service.BrowsingTracker

@SuppressLint("VpnServicePolicy")
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpnService"
        private const val CHANNEL_ID = "child_vpn_channel"
        private const val NOTIFICATION_ID = 1001

        private const val RESTART_COOLDOWN = 3000L

        @Volatile var isRunning = false
        @Volatile var isStarting = false
        @Volatile var lastHeartbeat = 0L
        @Volatile var lastStartTime = 0L

        private val eventQueue = java.util.concurrent.ArrayBlockingQueue<String>(300)
        //private const val DEBUG = false   // ✅ ADD THIS
        private const val DEBUG = true
    }
    // =====================================================
    // VPN
    // =====================================================

    private var readerThread: Thread? = null
    private var workerThread: Thread? = null
    private var watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunning = false

    private var vpnInterface:
            ParcelFileDescriptor? = null

    private var browsingTracker: BrowsingTracker? = null

    private val handler =
        Handler(Looper.getMainLooper())

    // =====================================================
    // DOMAIN THROTTLING
    // =====================================================

    private var lastDomain = ""
    private var lastDomainTime = 0L

    // =====================================================
    // CREATE
    // =====================================================

    override fun onCreate() {

        super.onCreate()

        Log.e(TAG, "🔥 VPN onCreate")

        val childId = getChildId()

        browsingTracker =
            BrowsingTracker(
                context = applicationContext,
                childId = childId
            )

        startForegroundSafe()
        //startEventWorker()

        //isRunning = true

        log("🔥 SERVICE CREATED + FOREGROUND STARTED")
    }

    // =====================================================
    // START
    // =====================================================
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        Log.e(TAG, "🚀 VPN onStartCommand")

        val now = System.currentTimeMillis()

        // =====================================================
        // STEP 1 - HARD RESTART COOLDOWN (IMPORTANT FIX)
        // =====================================================
        if (now - lastStartTime < RESTART_COOLDOWN) {
            log("⛔ Start blocked (cooldown)")
            return START_STICKY
        }

        // =====================================================
        // STEP 2 - DOUBLE START PROTECTION
        // =====================================================
        if (isStarting) return START_STICKY
        isStarting = true

        try {

            if (vpnInterface != null) {
                isStarting = false
                return START_STICKY
            }

            if (VpnService.prepare(this) != null) {

                Log.e(TAG, "❌ VPN permission missing")

                stopSelf()

                return START_NOT_STICKY
            }

            Log.e(TAG, "VPN permission confirmed")

            val builder = Builder()
                .setSession("Child Protection")
                .addAddress("10.0.0.2", 24)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")

            Log.e(TAG, "Calling builder.establish()")

            builder.allowFamily(android.system.OsConstants.AF_INET)
            builder.allowFamily(android.system.OsConstants.AF_INET6)

            vpnInterface = builder.establish()

            Log.e(TAG, "builder.establish() result = $vpnInterface")

            if (vpnInterface == null) {

                Log.e(TAG, "❌ builder.establish() returned null")

                isStarting = false
                stopSelf()

                return START_NOT_STICKY
            }
            isRunning = true

            startTunnelReader()
            startEventWorker()
            startWatchdog()

            // =================================================
            // STEP 3 - MARK SUCCESSFUL START
            // =================================================
            isRunning = true
            lastStartTime = now
            lastHeartbeat = now
            isStarting = false

            /*startTunnelReader()
            startEventWorker()
            startWatchdog()*/

            log("VPN STARTED STABLE")

        } catch (e: Exception) {
            Log.e(TAG, "START ERROR", e)
            isStarting = false
            cleanup()
        }

        return START_STICKY
    }
    // =====================================================
    // TUNNEL READER
    // =====================================================
    private fun startTunnelReader() {

        readerThread?.interrupt()
        readerThread = Thread {

            val buffer = ByteArray(32767)

            try {
                val fd = vpnInterface?.fileDescriptor ?: return@Thread
                val inputStream = FileInputStream(fd)

                log("DNS MONITOR STARTED")

                while (isRunning && !Thread.currentThread().isInterrupted) {

                    try {
                        lastHeartbeat = System.currentTimeMillis()

                        val length = try {
                            inputStream.read(buffer)
                        } catch (e: Exception) {
                            Log.e(TAG, "READ ERROR", e)
                            break
                        }

                        if (length <= 0) {
                            Thread.sleep(5)
                            continue
                        }

                        if (length < 28) continue

                        val packet = buffer.copyOf(length)

                        val version = (packet[0].toInt() and 0xFF) shr 4
                        if (version != 4) continue

                        val protocol = packet[9].toInt() and 0xFF
                        if (protocol != 17) continue

                        val ipHeaderLen = (packet[0].toInt() and 0x0F) * 4
                        if (ipHeaderLen < 20 || ipHeaderLen + 8 >= packet.size) continue

                        val srcPort =
                            ((packet[ipHeaderLen].toInt() and 0xFF) shl 8) or
                                    (packet[ipHeaderLen + 1].toInt() and 0xFF)

                        val dstPort =
                            ((packet[ipHeaderLen + 2].toInt() and 0xFF) shl 8) or
                                    (packet[ipHeaderLen + 3].toInt() and 0xFF)

                        if (srcPort != 53 && dstPort != 53) continue

                        val domain = try {
                            parseDnsSafe(packet, ipHeaderLen)
                        } catch (_: Exception) {
                            null
                        } ?: continue

                        if (!isValidDomain(domain)) continue

                        val now = System.currentTimeMillis()

                        val shouldSkip: Boolean

                        synchronized(this) {
                            shouldSkip =
                                domain == lastDomain &&
                                        now - lastDomainTime < 10000

                            if (!shouldSkip) {
                                lastDomain = domain
                                lastDomainTime = now
                            }
                        }

                        if (shouldSkip) continue
                        val url = "https://$domain"

                        if (!eventQueue.offer(url)) {

                            Log.w(
                                TAG,
                                "Queue full. Dropping URL."
                            )
                        }

                        Thread.sleep(2) // CPU protection

                    } catch (e: Exception) {
                        Log.e(TAG, "LOOP ERROR", e)
                        Thread.sleep(20)
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "TUNNEL CRASH", e)

            } finally {
                log("DNS MONITOR STOPPED")
            }
        }

        readerThread?.priority = Thread.MIN_PRIORITY
        readerThread?.start()
    }
    //-----------------validate domain----------
    private fun isValidDomain(domain: String): Boolean {

        val blocked = setOf(
            "in-addr.arpa",
            "googleapis.com",
            "gstatic.com",
            "connectivitycheck",
            "doubleclick.net"
        )

        if (domain.length < 4) return false

        blocked.forEach {
            if (domain.contains(it)) return false
        }

        return domain.contains(".")
    }

    // =====================================================
    // SAFE DNS PARSER
    // =====================================================

    private fun parseDnsSafe(
        data: ByteArray,
        ipHeaderLength: Int
    ): String? {

        return try {

            val dnsStart =
                ipHeaderLength + 8

            if (dnsStart + 12 >= data.size) {
                return null
            }

            var position =
                dnsStart + 12

            val domain =
                StringBuilder()

            while (position < data.size) {

                val len =
                    data[position].toInt() and 0xFF

                if (len == 0) {
                    break
                }

                position++

                if (
                    position + len >= data.size
                ) {
                    return null
                }

                for (i in 0 until len) {

                    val c =
                        data[position + i].toInt() and 0xFF

                    if (c in 32..126) {
                        domain.append(c.toChar())
                    }
                }

                position += len

                domain.append(".")
            }

            val result =
                domain.toString()
                    .removeSuffix(".")
                    .lowercase()

            // garbage filters
            if (result.length < 4) {
                return null
            }

            if (!result.contains(".")) {
                return null
            }

            if (result.contains("in-addr.arpa")) {
                return null
            }

            result

        } catch (_: Exception) {

            null
        }
    }

    // =====================================================
    // FOREGROUND
    // =====================================================

    private fun startForegroundSafe() {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                    CHANNEL_ID,
                "Child Internet Protection",
                NotificationManager.IMPORTANCE_LOW
            )

        channel.description =
            "VPN protection service"

        manager.createNotificationChannel(
            channel
        )

        val notification: Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentTitle(
                    "Child Protection Active"
                )
                .setContentText(
                    "Monitoring internet activity"
                )
                .setOngoing(true)
                .setPriority(
                    NotificationCompat.PRIORITY_LOW
                )
                .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    // =====================================================
    // CHILD ID
    // =====================================================

    private fun getChildId(): String {

        val prefs =
            getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            )

        return prefs.getString(
            "child_id",
            ""
        ) ?: ""
    }

    // =====================================================
    // CLEANUP
    // =====================================================

    private fun cleanup() {

        isRunning = false
        workerRunning = false
        watchdogRunning = false
        isStarting = false

        try {
            readerThread?.interrupt()
            readerThread = null
        } catch (_: Exception) {}

        try {
            workerThread?.interrupt()
            workerThread = null
        } catch (_: Exception) {}

        try {
            vpnInterface?.close()
            vpnInterface = null
        } catch (_: Exception) {}
    }

    // =====================================================
    // TASK REMOVED
    // =====================================================

    override fun onTaskRemoved(rootIntent: Intent?) {

        log("⚠ Task removed")

        handler.removeCallbacksAndMessages(null)

        cleanup()

        stopSelf()

        super.onTaskRemoved(rootIntent)
    }

    // =====================================================
    // REVOKE
    // =====================================================


    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {
        super.onDestroy()

        isRunning = false

        browsingTracker?.destroy()
        browsingTracker = null

        log("💀 VPN destroyed")

        cleanup()
    }
    // =====================================================
    // LOW MEMORY
    // =====================================================

    override fun onLowMemory() {
        super.onLowMemory()

        log("⚠ LOW MEMORY")
    }

    // =====================================================
    // TRIM MEMORY
    // =====================================================

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        log("⚠ Trim memory = $level")
    }

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return super.onBind(intent)
    }

    // =====================================================
    // LOGGER
    // =====================================================

    private fun log(message: String) {

        if (DEBUG) {
            Log.e(TAG, message)
        }
    }

    private fun startWatchdog() {

        if (watchdogRunning) return
        watchdogRunning = true

        watchdogHandler.post(object : Runnable {
            override fun run() {

                if (!isRunning) return

                val dead = System.currentTimeMillis() - lastHeartbeat > 120000

                if (dead) {
                    Log.e(TAG, "VPN STUCK → SAFE RESTART")

                    cleanup()
                    stopSelf()
                    return
                }

                watchdogHandler.postDelayed(this, 10000)
            }
        })
    }

    //----------------event worker-------
    private var workerRunning = true

    private fun startEventWorker() {

        workerThread?.interrupt()
        workerRunning = true

        workerThread = Thread {

            while (workerRunning && isRunning) {

                try {

                    val url = eventQueue.take()

                    handler.post {
                        try {
                            try {

                                browsingTracker?.onUrlDetected(
                                    url,
                                    "vpn"
                                )

                            } catch (e: Exception) {

                                Log.e(
                                    TAG,
                                    "BrowsingTracker crash",
                                    e
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "TRACKER ERROR", e)
                        }
                    }

                } catch (e: InterruptedException) {

                    Log.d(TAG, "Worker stopped")
                    break

                } catch (e: Exception) {

                    Log.e(TAG, "WORKER ERROR", e)
                }
            }
        }

        workerThread?.start()
    }
    override fun onRevoke() {

        Log.e(TAG, "⚠ VPN permission revoked")

        isRunning = false

        cleanup()

        stopSelf()

        super.onRevoke()
    }
}