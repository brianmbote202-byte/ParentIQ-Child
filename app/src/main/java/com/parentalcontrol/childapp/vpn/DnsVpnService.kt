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
import android.os.Build
import android.content.Context
import androidx.core.content.ContextCompat

@SuppressLint("VpnServicePolicy")
class DnsVpnService : VpnService() {

    companion object {

        private const val TAG = "DnsVpnService"

        private const val CHANNEL_ID =
            "child_vpn_channel"

        private const val NOTIFICATION_ID =
            1001

        private const val RESTART_COOLDOWN =
            3000L

        private const val WATCHDOG_INTERVAL =
            10_000L

        private const val WATCHDOG_TIMEOUT =
            120_000L

        private const val URL_BROADCAST =
            "com.parentalcontrol.childapp.URL_DETECTED"

        @Volatile
        var isRunning = false

        @Volatile
        var isStarting = false

        @Volatile
        var lastHeartbeat = 0L

        @Volatile
        var lastStartTime = 0L

        private val eventQueue =
            java.util.concurrent.ArrayBlockingQueue<String>(300)

        private const val DEBUG = true

        fun start(context: Context) {

            try {

                val appContext =
                    context.applicationContext

                val intent =
                    Intent(
                        appContext,
                        DnsVpnService::class.java
                    )

                Log.e(
                    TAG,
                    "🚀 REQUESTING VPN START"
                )

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

                    ContextCompat.startForegroundService(
                        appContext,
                        intent
                    )

                } else {

                    appContext.startService(intent)
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "❌ VPN START REQUEST FAILED",
                    e
                )
            }
        }
    }

    // =====================================================
    // VPN
    // =====================================================

    private var readerThread: Thread? = null

    private var workerThread: Thread? = null

    private val watchdogHandler =
        Handler(Looper.getMainLooper())

    private val handler =
        Handler(Looper.getMainLooper())

    private var watchdogRunning = false

    private var workerRunning = false

    private var vpnInterface:
            ParcelFileDescriptor? = null

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

        Log.e(
            TAG,
            "🔥🔥🔥 VPN SERVICE CREATED 🔥🔥🔥"
        )

        /*
         * IMPORTANT:
         *
         * BrowsingTracker is intentionally NOT created here.
         *
         * BrowserAccessibilityService owns the tracker.
         *
         * The VPN sends URL_DETECTED broadcasts and the
         * accessibility-side BrowsingTracker receives them.
         *
         * This prevents two BrowsingTracker instances from
         * processing the same browsing activity.
         */

        try {

            startForegroundSafe()

            log(
                "✅ VPN FOREGROUND STARTED"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Failed to start VPN foreground",
                e
            )
        }
    }

    // =====================================================
    // START
    // =====================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.e(
            TAG,
            "🚀 VPN onStartCommand"
        )

        val now =
            System.currentTimeMillis()

        // =================================================
        // HARD RESTART COOLDOWN
        // =================================================

        if (
            now - lastStartTime <
            RESTART_COOLDOWN
        ) {

            log(
                "⛔ Start blocked by cooldown"
            )

            return START_STICKY
        }

        // =================================================
        // DOUBLE START PROTECTION
        // =================================================

        if (isStarting) {

            log(
                "⛔ Start already in progress"
            )

            return START_STICKY
        }

        // =================================================
        // ALREADY RUNNING
        // =================================================

        if (
            isRunning &&
            vpnInterface != null
        ) {

            log(
                "✅ VPN already running"
            )

            return START_STICKY
        }

        isStarting = true

        try {

            // =================================================
            // CLEAR STALE STATE
            // =================================================

            if (vpnInterface != null) {

                log(
                    "⚠ Closing stale VPN interface"
                )

                try {
                    vpnInterface?.close()
                } catch (_: Exception) {}

                vpnInterface = null
            }

            // =================================================
            // VPN PERMISSION
            // =================================================

            if (
                VpnService.prepare(this) != null
            ) {

                Log.e(
                    TAG,
                    "❌ VPN permission missing"
                )

                isStarting = false

                /*
                 * Do not repeatedly restart a VPN that has
                 * no user-granted VPN permission.
                 */

                stopSelf()

                return START_NOT_STICKY
            }

            Log.e(
                TAG,
                "✅ VPN permission confirmed"
            )

            // =================================================
            // VPN BUILDER
            // =================================================

            val builder =
                Builder()
                    .setSession(
                        "Child Protection"
                    )
                    .addAddress(
                        "10.0.0.2",
                        24
                    )
                    .addDnsServer(
                        "1.1.1.1"
                    )
                    .addDnsServer(
                        "8.8.8.8"
                    )

            /*
             * Keep both IPv4 and IPv6 enabled.
             */

            builder.allowFamily(
                android.system.OsConstants.AF_INET
            )

            builder.allowFamily(
                android.system.OsConstants.AF_INET6
            )

            // =================================================
            // ESTABLISH VPN
            // =================================================

            Log.e(
                TAG,
                "🌐 Calling builder.establish()"
            )

            vpnInterface =
                builder.establish()

            Log.e(
                TAG,
                "🌐 builder.establish() result = $vpnInterface"
            )

            if (vpnInterface == null) {

                Log.e(
                    TAG,
                    "❌ builder.establish() returned null"
                )

                isStarting = false

                stopSelf()

                return START_NOT_STICKY
            }

            // =================================================
            // MARK RUNNING
            // =================================================

            isRunning = true

            lastStartTime = now

            lastHeartbeat = now

            isStarting = false

            // =================================================
            // START MONITORING
            // =================================================

            startTunnelReader()

            startEventWorker()

            startWatchdog()

            Log.e(
                TAG,
                "=========================================="
            )

            Log.e(
                TAG,
                "✅ VPN STARTED SUCCESSFULLY"
            )

            Log.e(
                TAG,
                "🌐 DNS monitoring active"
            )

            Log.e(
                TAG,
                "=========================================="
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ VPN START ERROR",
                e
            )

            isStarting = false

            cleanup()
        }

        /*
         * IMPORTANT:
         *
         * START_STICKY allows Android to recreate the service
         * if the process/service is killed.
         *
         * Boot recovery is additionally handled by
         * SystemBootOrchestrator.
         */

        return START_STICKY
    }

    // =====================================================
    // TUNNEL READER
    // =====================================================

    private fun startTunnelReader() {

        readerThread?.interrupt()

        readerThread =
            Thread {

                val buffer =
                    ByteArray(32767)

                try {

                    val fd =
                        vpnInterface
                            ?.fileDescriptor
                            ?: return@Thread

                    val inputStream =
                        FileInputStream(fd)

                    log(
                        "🌐 DNS MONITOR STARTED"
                    )

                    while (
                        isRunning &&
                        !Thread.currentThread()
                            .isInterrupted
                    ) {

                        try {

                            lastHeartbeat =
                                System.currentTimeMillis()

                            val length =
                                try {

                                    inputStream.read(
                                        buffer
                                    )

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "❌ DNS READ ERROR",
                                        e
                                    )

                                    break
                                }

                            if (length <= 0) {

                                Thread.sleep(5)

                                continue
                            }

                            if (length < 28) {
                                continue
                            }

                            val packet =
                                buffer.copyOf(
                                    length
                                )

                            // =================================================
                            // IPV4 ONLY FOR CURRENT DNS PARSER
                            // =================================================

                            val version =
                                (
                                        packet[0]
                                            .toInt() and 0xFF
                                        ) shr 4

                            if (version != 4) {
                                continue
                            }

                            val protocol =
                                packet[9]
                                    .toInt() and 0xFF

                            /*
                             * UDP = 17
                             */

                            if (protocol != 17) {
                                continue
                            }

                            val ipHeaderLen =
                                (
                                        packet[0]
                                            .toInt() and 0x0F
                                        ) * 4

                            if (
                                ipHeaderLen < 20 ||
                                ipHeaderLen + 8 >=
                                packet.size
                            ) {
                                continue
                            }

                            val srcPort =
                                (
                                        (
                                                packet[
                                                    ipHeaderLen
                                                ]
                                                    .toInt()
                                                        and 0xFF
                                                ) shl 8
                                        ) or
                                        (
                                                packet[
                                                    ipHeaderLen + 1
                                                ]
                                                    .toInt()
                                                        and 0xFF
                                                )

                            val dstPort =
                                (
                                        (
                                                packet[
                                                    ipHeaderLen + 2
                                                ]
                                                    .toInt()
                                                        and 0xFF
                                                ) shl 8
                                        ) or
                                        (
                                                packet[
                                                    ipHeaderLen + 3
                                                ]
                                                    .toInt()
                                                        and 0xFF
                                                )

                            /*
                             * Only DNS traffic.
                             */

                            if (
                                srcPort != 53 &&
                                dstPort != 53
                            ) {
                                continue
                            }

                            val domain =
                                try {

                                    parseDnsSafe(
                                        packet,
                                        ipHeaderLen
                                    )

                                } catch (_: Exception) {

                                    null
                                }

                            if (
                                domain == null ||
                                !isValidDomain(domain)
                            ) {
                                continue
                            }

                            val now =
                                System.currentTimeMillis()

                            val shouldSkip: Boolean

                            synchronized(this) {

                                shouldSkip =
                                    domain ==
                                            lastDomain &&
                                            now -
                                            lastDomainTime <
                                            10_000

                                if (!shouldSkip) {

                                    lastDomain =
                                        domain

                                    lastDomainTime =
                                        now
                                }
                            }

                            if (shouldSkip) {
                                continue
                            }

                            val url =
                                "https://$domain"

                            if (
                                !eventQueue.offer(url)
                            ) {

                                Log.w(
                                    TAG,
                                    "⚠ Queue full. Dropping URL: $url"
                                )
                            }

                            /*
                             * Small CPU protection delay.
                             */

                            Thread.sleep(2)

                        } catch (
                            e: InterruptedException
                        ) {

                            Log.d(
                                TAG,
                                "DNS reader interrupted"
                            )

                            break

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "❌ DNS LOOP ERROR",
                                e
                            )

                            try {
                                Thread.sleep(20)
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "❌ TUNNEL CRASH",
                        e
                    )

                } finally {

                    log(
                        "🛑 DNS MONITOR STOPPED"
                    )
                }
            }

        readerThread?.priority =
            Thread.MIN_PRIORITY

        readerThread?.start()
    }

    // =====================================================
    // VALIDATE DOMAIN
    // =====================================================

    private fun isValidDomain(
        domain: String
    ): Boolean {

        val blocked =
            setOf(
                "in-addr.arpa",
                "googleapis.com",
                "gstatic.com",
                "connectivitycheck",
                "doubleclick.net"
            )

        if (domain.length < 4) {
            return false
        }

        for (blockedDomain in blocked) {

            if (
                domain.contains(
                    blockedDomain
                )
            ) {
                return false
            }
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

            val dnsStart = ipHeaderLength + 8

            if (dnsStart + 12 >= data.size) {
                return null
            }

            var position = dnsStart + 12

            val domain = StringBuilder()

            while (position < data.size) {

                val len =
                    data[position].toInt() and 0xFF

                if (len == 0) {
                    break
                }

                /*
                 * DNS compression pointer.
                 *
                 * Do not interpret a compression pointer
                 * as a normal hostname label.
                 */
                if ((len and 0xC0) == 0xC0) {
                    break
                }

                /*
                 * DNS label length must be reasonable.
                 */
                if (len > 63) {
                    return null
                }

                position++

                if (position + len > data.size) {
                    return null
                }

                for (i in 0 until len) {

                    val c =
                        data[position + i].toInt() and 0xFF

                    /*
                     * Only keep printable ASCII characters.
                     */
                    if (c in 32..126) {
                        domain.append(c.toChar())
                    }
                }

                position += len

                domain.append(".")
            }

            val result =
                domain
                    .toString()
                    .removeSuffix(".")
                    .lowercase()

            // =================================================
            // VALIDATION
            // =================================================

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

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ DNS PARSER ERROR",
                e
            )

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

        val notification:
                Notification =
            NotificationCompat.Builder(
                this,
                CHANNEL_ID
            )
                .setSmallIcon(
                    R.drawable.ic_vpn
                )
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
    // CLEANUP
    // =====================================================

    private fun cleanup() {

        Log.e(
            TAG,
            "🧹 VPN CLEANUP"
        )

        isRunning = false

        isStarting = false

        workerRunning = false

        watchdogRunning = false

        // =================================================
        // STOP WATCHDOG
        // =================================================

        try {

            watchdogHandler
                .removeCallbacksAndMessages(
                    null
                )

        } catch (_: Exception) {}

        // =================================================
        // STOP HANDLER
        // =================================================

        try {

            handler
                .removeCallbacksAndMessages(
                    null
                )

        } catch (_: Exception) {}

        // =================================================
        // STOP READER
        // =================================================

        try {

            readerThread?.interrupt()

        } catch (_: Exception) {}

        readerThread = null

        // =================================================
        // STOP WORKER
        // =================================================

        try {

            workerThread?.interrupt()

        } catch (_: Exception) {}

        workerThread = null

        // =================================================
        // CLOSE VPN
        // =================================================

        try {

            vpnInterface?.close()

        } catch (_: Exception) {}

        vpnInterface = null

        /*
         * Do not clear the event queue here.
         *
         * Keeping queued events allows the next worker
         * instance to process them if the service recovers.
         */
    }

    // =====================================================
    // TASK REMOVED
    // =====================================================

    override fun onTaskRemoved(
        rootIntent: Intent?
    ) {

        /*
         * IMPORTANT:
         *
         * Removing the app from Recents must NOT destroy
         * the VPN protection service.
         *
         * The manifest already has:
         *
         * stopWithTask="false"
         *
         * Therefore we simply leave the service running.
         */

        log(
            "⚠ Task removed - VPN service remains active"
        )

        super.onTaskRemoved(
            rootIntent
        )
    }

    // =====================================================
    // DESTROY
    // =====================================================

    override fun onDestroy() {

        Log.e(
            TAG,
            "💀 VPN onDestroy"
        )

        cleanup()

        super.onDestroy()
    }

    // =====================================================
    // LOW MEMORY
    // =====================================================

    override fun onLowMemory() {

        Log.e(
            TAG,
            "⚠️ SYSTEM LOW MEMORY"
        )

        Log.e(
            TAG,
            "⚠️ DnsVpnService may be under memory pressure"
        )

        super.onLowMemory()
    }

    // =====================================================
    // TRIM MEMORY
    // =====================================================

    override fun onTrimMemory(
        level: Int
    ) {

        super.onTrimMemory(
            level
        )

        log(
            "⚠ Trim memory = $level"
        )
    }

    // =====================================================
    // BIND
    // =====================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return super.onBind(
            intent
        )
    }

    // =====================================================
    // WATCHDOG
    // =====================================================

    private fun startWatchdog() {

        if (watchdogRunning) {
            return
        }

        watchdogRunning = true

        watchdogHandler.post(
            object : Runnable {

                override fun run() {

                    if (!watchdogRunning) {
                        return
                    }

                    if (!isRunning) {
                        return
                    }

                    val heartbeatAge =
                        System.currentTimeMillis() -
                                lastHeartbeat

                    val dead =
                        heartbeatAge >
                                WATCHDOG_TIMEOUT

                    if (dead) {

                        Log.e(
                            TAG,
                            "⚠ VPN STUCK → SAFE RESTART"
                        )

                        /*
                         * Let the service lifecycle and
                         * centralized boot/self-healing
                         * architecture handle recovery.
                         */

                        cleanup()

                        stopSelf()

                        return
                    }

                    watchdogHandler.postDelayed(
                        this,
                        WATCHDOG_INTERVAL
                    )
                }
            }
        )
    }

    // =====================================================
    // EVENT WORKER
    // =====================================================

    private fun startEventWorker() {

        workerThread?.interrupt()

        workerRunning = true

        workerThread =
            Thread {

                Log.e(
                    TAG,
                    "📡 VPN EVENT WORKER STARTED"
                )

                while (
                    workerRunning &&
                    isRunning &&
                    !Thread.currentThread()
                        .isInterrupted
                ) {

                    try {

                        val url =
                            eventQueue.take()

                        /*
                         * Send the URL to the existing
                         * BrowserAccessibilityService /
                         * BrowsingTracker pipeline.
                         *
                         * This prevents DnsVpnService from
                         * creating a second BrowsingTracker.
                         */

                        val broadcast =
                            Intent(
                                URL_BROADCAST
                            ).apply {

                                setPackage(
                                    packageName
                                )

                                putExtra(
                                    "url",
                                    url
                                )

                                putExtra(
                                    "package",
                                    "vpn"
                                )
                            }

                        sendBroadcast(
                            broadcast
                        )

                        log(
                            "📡 URL_DETECTED → $url"
                        )

                    } catch (
                        e: InterruptedException
                    ) {

                        Log.d(
                            TAG,
                            "📡 VPN EVENT WORKER STOPPED"
                        )

                        break

                    } catch (e: Exception) {

                        Log.e(
                            TAG,
                            "❌ VPN EVENT WORKER ERROR",
                            e
                        )
                    }
                }

                Log.e(
                    TAG,
                    "📡 VPN EVENT WORKER EXITED"
                )
            }

        workerThread?.priority =
            Thread.MIN_PRIORITY

        workerThread?.start()
    }

    // =====================================================
    // VPN REVOKED
    // =====================================================

    override fun onRevoke() {

        Log.e(
            TAG,
            "⚠ VPN PERMISSION REVOKED"
        )

        cleanup()

        stopSelf()

        super.onRevoke()
    }

    // =====================================================
    // LOGGER
    // =====================================================

    private fun log(
        message: String
    ) {

        if (DEBUG) {

            Log.e(
                TAG,
                message
            )
        }
    }
}