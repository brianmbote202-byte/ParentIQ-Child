package com.parentalcontrol.childapp.receiver

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Watches the Android SMS database for OUTGOING / SENT SMS.
 *
 * OUTGOING:
 *
 * Android SMS database
 *        ↓
 * SmsObserver
 *        ↓
 * MessageService
 *        ↓
 * Firebase
 *
 * IMPORTANT:
 *
 * Incoming SMS are NOT processed here.
 * Incoming SMS should continue to be handled by SmsReceiver.
 *
 * This observer is specifically responsible for messages that
 * the child phone sends.
 */
class SmsObserver(
    private val context: Context
) {

    companion object {

        private const val TAG = "SmsObserver"

        // ============================================================
        // SMS URI
        // ============================================================

        private val SMS_URI: Uri =
            Telephony.Sms.CONTENT_URI

        // ============================================================
        // CACHE
        // ============================================================

        /**
         * Number of SMS database IDs kept in memory.
         *
         * This is only a runtime duplicate-protection cache.
         * It is intentionally not persisted.
         */
        private const val MAX_CACHE = 500

        /**
         * Maximum number of rows inspected during one scan.
         */
        private const val MAX_QUERY_ROWS = 200

        /**
         * Delay after an SMS database notification.
         *
         * Some SMS providers notify before the row is completely
         * committed.
         */
        private const val DEFAULT_SCAN_DELAY_MS = 350L

        // ============================================================
        // PROJECTION
        // ============================================================

        private val PROJECTION = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        // ============================================================
        // PROCESSED IDS
        // ============================================================

        /**
         * Recently processed Android SMS database IDs.
         *
         * _ID is much safer than using:
         *
         * address + timestamp + body
         *
         * because two messages can have identical content.
         */
        private val processedSmsIds =
            LinkedHashSet<Long>()
    }

    // ================================================================
    // OBSERVER
    // ================================================================

    private var contentObserver: ContentObserver? = null

    @Volatile
    private var started = false

    /**
     * Prevent overlapping SMS database scans.
     */
    @Volatile
    private var queryRunning = false

    /**
     * Main thread handler.
     */
    private val handler =
        Handler(Looper.getMainLooper())

    // ================================================================
    // START
    // ================================================================

    fun start() {

        if (started) {

            Log.d(
                TAG,
                "SmsObserver already started"
            )

            return
        }

        try {

            Log.d(
                TAG,
                "========================================"
            )

            Log.d(
                TAG,
                "STARTING SmsObserver"
            )

            Log.d(
                TAG,
                "SMS URI=$SMS_URI"
            )

            Log.d(
                TAG,
                "========================================"
            )

            contentObserver =
                object : ContentObserver(handler) {

                    override fun onChange(
                        selfChange: Boolean
                    ) {

                        super.onChange(
                            selfChange
                        )

                        Log.d(
                            TAG,
                            "SMS database changed"
                        )

                        /*
                         * Do not scan immediately.
                         *
                         * The SMS provider may notify us before the
                         * SENT row is completely committed.
                         */
                        scheduleScan()
                    }
                }

            context.contentResolver.registerContentObserver(
                SMS_URI,
                true,
                contentObserver!!
            )

            started = true

            Log.d(
                TAG,
                "SmsObserver registered successfully"
            )

            /*
             * Scan immediately as well.
             *
             * This catches an outgoing SMS that was inserted shortly
             * before the observer started.
             */
            scheduleScan(
                delayMs = 300L
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "Unable to register SmsObserver. " +
                        "READ_SMS permission may be missing.",
                e
            )

            contentObserver = null
            started = false

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start SmsObserver",
                e
            )

            contentObserver = null
            started = false
        }
    }

    // ================================================================
    // STOP
    // ================================================================

    fun stop() {

        try {

            contentObserver?.let { observer ->

                context.contentResolver
                    .unregisterContentObserver(
                        observer
                    )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to unregister SmsObserver",
                e
            )
        }

        contentObserver = null
        started = false

        /*
         * Cancel any pending scan.
         */
        handler.removeCallbacks(
            scanRunnable
        )

        queryRunning = false

        Log.d(
            TAG,
            "SmsObserver stopped"
        )
    }

    // ================================================================
    // SCHEDULE SCAN
    // ================================================================

    private fun scheduleScan(
        delayMs: Long = DEFAULT_SCAN_DELAY_MS
    ) {

        /*
         * Multiple SMS database notifications can happen for one SMS.
         *
         * Remove the previous scheduled scan and schedule one new scan.
         */
        handler.removeCallbacks(
            scanRunnable
        )

        handler.postDelayed(
            scanRunnable,
            delayMs
        )
    }

    private val scanRunnable =
        Runnable {
            scanOutgoingSms()
        }

    // ================================================================
    // SCAN OUTGOING SMS
    // ================================================================

    private fun scanOutgoingSms() {

        /*
         * Prevent two ContentObserver callbacks from querying the
         * SMS database simultaneously.
         */
        if (queryRunning) {

            Log.d(
                TAG,
                "SMS scan already running"
            )

            return
        }

        queryRunning = true

        var cursor: Cursor? = null

        try {

            Log.d(
                TAG,
                "========================================"
            )

            Log.d(
                TAG,
                "SCANNING SMS DATABASE FOR OUTGOING SMS"
            )

            Log.d(
                TAG,
                "========================================"
            )

            // ========================================================
            // QUERY
            // ========================================================

            cursor =
                context.contentResolver.query(
                    SMS_URI,
                    PROJECTION,
                    null,
                    null,
                    "${Telephony.Sms.DATE} DESC"
                )

            if (cursor == null) {

                Log.w(
                    TAG,
                    "SMS query returned null cursor"
                )

                return
            }

            // ========================================================
            // COLUMN INDEXES
            // ========================================================

            val idIndex =
                cursor.getColumnIndex(
                    Telephony.Sms._ID
                )

            val addressIndex =
                cursor.getColumnIndex(
                    Telephony.Sms.ADDRESS
                )

            val bodyIndex =
                cursor.getColumnIndex(
                    Telephony.Sms.BODY
                )

            val dateIndex =
                cursor.getColumnIndex(
                    Telephony.Sms.DATE
                )

            val typeIndex =
                cursor.getColumnIndex(
                    Telephony.Sms.TYPE
                )

            val subscriptionIndex =
                cursor.getColumnIndex(
                    Telephony.Sms.SUBSCRIPTION_ID
                )

            Log.d(
                TAG,
                "SMS columns: " +
                        "id=$idIndex " +
                        "address=$addressIndex " +
                        "body=$bodyIndex " +
                        "date=$dateIndex " +
                        "type=$typeIndex " +
                        "subscription=$subscriptionIndex"
            )

            if (idIndex < 0) {

                Log.e(
                    TAG,
                    "SMS _ID column unavailable"
                )

                return
            }

            // ========================================================
            // CHILD ID
            // ========================================================

            val prefs =
                context.getSharedPreferences(
                    "child_prefs",
                    Context.MODE_PRIVATE
                )

            val childId =
                prefs.getString(
                    "child_id",
                    ""
                )
                    ?.trim()
                    ?: ""

            if (childId.isBlank()) {

                Log.e(
                    TAG,
                    "child_id is EMPTY. " +
                            "Cannot send outgoing SMS to MessageService."
                )

                /*
                 * Do not mark anything as processed.
                 *
                 * Once child_id becomes available, the next scan
                 * can process the SMS.
                 */
                return
            }

            // ========================================================
            // MOVE TO FIRST ROW
            // ========================================================

            if (!cursor.moveToFirst()) {

                Log.d(
                    TAG,
                    "SMS database is empty"
                )

                return
            }

            // ========================================================
            // PROCESS ROWS
            // ========================================================

            var scannedRows = 0
            var processedCount = 0

            /*
             * IMPORTANT:
             *
             * We intentionally use:
             *
             * while (cursor.moveToNext())
             *
             * instead of do/while + break/continue.
             *
             * This avoids the Kotlin language-version error:
             *
             * "The feature break continue in inline lambdas is only
             * available since language version 2.2"
             */
            while (
                scannedRows < MAX_QUERY_ROWS
            ) {

                scannedRows++

                // ====================================================
                // SMS ID
                // ====================================================

                val smsId =
                    cursor.getLong(
                        idIndex
                    )

                // ====================================================
                // TYPE
                // ====================================================

                val type =
                    if (typeIndex >= 0) {

                        cursor.getInt(
                            typeIndex
                        )

                    } else {

                        -1
                    }

                /*
                 * Only process SENT messages.
                 *
                 * Incoming messages are handled by SmsReceiver.
                 */
                val isOutgoing =
                    type ==
                            Telephony.Sms.MESSAGE_TYPE_SENT

                if (isOutgoing) {

                    // =================================================
                    // DUPLICATE CHECK
                    // =================================================

                    val alreadyProcessed =
                        synchronized(
                            processedSmsIds
                        ) {

                            processedSmsIds.contains(
                                smsId
                            )
                        }

                    if (!alreadyProcessed) {

                        // =============================================
                        // ADDRESS
                        // =============================================

                        val address =
                            if (
                                addressIndex >= 0
                            ) {

                                cursor
                                    .getString(
                                        addressIndex
                                    )
                                    ?.trim()
                                    ?.takeIf {
                                        it.isNotEmpty()
                                    }
                                    ?: "unknown_number"

                            } else {

                                "unknown_number"
                            }

                        // =============================================
                        // BODY
                        // =============================================

                        val body =
                            if (
                                bodyIndex >= 0
                            ) {

                                cursor
                                    .getString(
                                        bodyIndex
                                    )
                                    ?.trim()
                                    ?.takeIf {
                                        it.isNotEmpty()
                                    }
                                    ?: "(No Content)"

                            } else {

                                "(No Content)"
                            }

                        // =============================================
                        // TIMESTAMP
                        // =============================================

                        val timestamp =
                            if (
                                dateIndex >= 0 &&
                                !cursor.isNull(
                                    dateIndex
                                )
                            ) {

                                cursor.getLong(
                                    dateIndex
                                )

                            } else {

                                System.currentTimeMillis()
                            }

                        // =============================================
                        // SUBSCRIPTION ID
                        // =============================================

                        val subscriptionId =
                            if (
                                subscriptionIndex >= 0 &&
                                !cursor.isNull(
                                    subscriptionIndex
                                )
                            ) {

                                cursor.getInt(
                                    subscriptionIndex
                                )

                            } else {

                                SubscriptionManager
                                    .INVALID_SUBSCRIPTION_ID
                            }

                        // =============================================
                        // NORMALIZE RECIPIENT
                        // =============================================

                        val toRaw =
                            address

                        val to =
                            normalizeRecipient(
                                address
                            )

                        // =============================================
                        // LOG
                        // =============================================

                        Log.d(
                            TAG,
                            "========================================"
                        )

                        Log.d(
                            TAG,
                            "OUTGOING SMS DETECTED"
                        )

                        Log.d(
                            TAG,
                            "smsId=$smsId"
                        )

                        Log.d(
                            TAG,
                            "type=$type"
                        )

                        Log.d(
                            TAG,
                            "childId=$childId"
                        )

                        Log.d(
                            TAG,
                            "toRaw=$toRaw"
                        )

                        Log.d(
                            TAG,
                            "to=$to"
                        )

                        Log.d(
                            TAG,
                            "body=$body"
                        )

                        Log.d(
                            TAG,
                            "timestamp=$timestamp"
                        )

                        Log.d(
                            TAG,
                            "subscriptionId=$subscriptionId"
                        )

                        Log.d(
                            TAG,
                            "========================================"
                        )

                        // =============================================
                        // BUILD MESSAGE SERVICE INTENT
                        // =============================================

                        val serviceIntent =
                            Intent(
                                context,
                                MessageService::class.java
                            ).apply {

                                // -------------------------------------
                                // TYPE
                                // -------------------------------------

                                putExtra(
                                    "type",
                                    "SMS"
                                )

                                // -------------------------------------
                                // CHILD ID
                                // -------------------------------------

                                putExtra(
                                    "childId",
                                    childId
                                )

                                // -------------------------------------
                                // FROM
                                // -------------------------------------

                                putExtra(
                                    "fromRaw",
                                    "DEVICE"
                                )

                                putExtra(
                                    "from",
                                    "DEVICE"
                                )

                                // -------------------------------------
                                // TO
                                // -------------------------------------

                                putExtra(
                                    "toRaw",
                                    toRaw
                                )

                                putExtra(
                                    "to",
                                    to
                                )

                                // -------------------------------------
                                // CONTENT
                                // -------------------------------------

                                putExtra(
                                    "content",
                                    body
                                )

                                // -------------------------------------
                                // TIMESTAMP
                                // -------------------------------------

                                putExtra(
                                    "timestamp",
                                    timestamp
                                )

                                // -------------------------------------
                                // DIRECTION
                                // -------------------------------------

                                putExtra(
                                    "direction",
                                    "OUTGOING"
                                )

                                // -------------------------------------
                                // DATABASE SMS ID
                                // -------------------------------------

                                putExtra(
                                    "smsDatabaseId",
                                    smsId
                                )

                                // -------------------------------------
                                // SUBSCRIPTION ID
                                // -------------------------------------

                                putExtra(
                                    MessageService
                                        .EXTRA_SUBSCRIPTION_ID,
                                    subscriptionId
                                )
                            }

                        // =============================================
                        // START MESSAGE SERVICE
                        // =============================================

                        var serviceStartSucceeded =
                            false

                        try {

                            Log.d(
                                TAG,
                                "========================================"
                            )

                            Log.d(
                                TAG,
                                "STARTING MessageService"
                            )

                            Log.d(
                                TAG,
                                "smsId=$smsId"
                            )

                            Log.d(
                                TAG,
                                "childId=$childId"
                            )

                            Log.d(
                                TAG,
                                "to=$to"
                            )

                            Log.d(
                                TAG,
                                "contentLength=${body.length}"
                            )

                            Log.d(
                                TAG,
                                "subscriptionId=$subscriptionId"
                            )

                            Log.d(
                                TAG,
                                "========================================"
                            )

                            if (
                                Build.VERSION.SDK_INT >=
                                Build.VERSION_CODES.O
                            ) {

                                ContextCompat
                                    .startForegroundService(
                                        context,
                                        serviceIntent
                                    )

                            } else {

                                @Suppress(
                                    "DEPRECATION"
                                )

                                context.startService(
                                    serviceIntent
                                )
                            }

                            serviceStartSucceeded = true

                            Log.d(
                                TAG,
                                "MessageService start request accepted " +
                                        "for SMS id=$smsId"
                            )

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "FAILED to start MessageService " +
                                        "for SMS id=$smsId",
                                e
                            )

                            serviceStartSucceeded = false
                        }

                        // =============================================
                        // MARK PROCESSED
                        // =============================================

                        /*
                         * IMPORTANT:
                         *
                         * Only mark the SMS processed if Android accepted
                         * the MessageService start request.
                         *
                         * If startService/startForegroundService throws,
                         * we leave the ID unprocessed so a later scan can
                         * retry it.
                         */
                        if (serviceStartSucceeded) {

                            synchronized(
                                processedSmsIds
                            ) {

                                processedSmsIds.add(
                                    smsId
                                )

                                trimProcessedCache()
                            }

                            processedCount++

                            Log.d(
                                TAG,
                                "Outgoing SMS queued successfully: " +
                                        "smsId=$smsId"
                            )

                        } else {

                            Log.w(
                                TAG,
                                "Outgoing SMS NOT marked processed. " +
                                        "It will be retried: smsId=$smsId"
                            )
                        }

                    } else {

                        Log.d(
                            TAG,
                            "Outgoing SMS already processed: " +
                                    "smsId=$smsId"
                        )
                    }

                } else {

                    /*
                     * Ignore everything that isn't SENT.
                     *
                     * No continue needed.
                     */
                }

                // ====================================================
                // MAX PROCESSED
                // ====================================================

                if (
                    processedCount >=
                    MAX_QUERY_ROWS
                ) {

                    Log.d(
                        TAG,
                        "Reached processing limit"
                    )

                    break
                }

                // ====================================================
                // NEXT ROW
                // ====================================================

                val hasNext =
                    cursor.moveToNext()

                if (!hasNext) {
                    break
                }
            }

            Log.d(
                TAG,
                "SMS scan complete: " +
                        "scannedRows=$scannedRows, " +
                        "processed=$processedCount"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "SMS permission denied while reading SMS database",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to scan SMS database",
                e
            )

        } finally {

            try {

                cursor?.close()

            } catch (e: Exception) {

                Log.w(
                    TAG,
                    "Failed to close SMS cursor",
                    e
                )
            }

            queryRunning = false

            Log.d(
                TAG,
                "SMS database scan finished"
            )
        }
    }

    // ================================================================
    // NORMALIZE RECIPIENT
    // ================================================================

    private fun normalizeRecipient(
        value: String?
    ): String {

        if (
            value.isNullOrBlank()
        ) {

            return "unknown_number"
        }

        val cleaned =
            value.trim()

        if (cleaned.isEmpty()) {

            return "unknown_number"
        }

        /*
         * Alphanumeric destinations are technically possible.
         */
        if (
            cleaned.any {
                it.isLetter()
            }
        ) {

            return cleaned
                .uppercase()
                .replace(
                    "[^A-Z0-9_+.-]".toRegex(),
                    ""
                )
                .ifEmpty {
                    "unknown_number"
                }
        }

        /*
         * Normal phone number.
         */
        return cleaned
            .replace(
                "[^\\d+]".toRegex(),
                ""
            )
            .ifEmpty {
                "unknown_number"
            }
    }

    // ================================================================
    // CACHE
    // ================================================================

    private fun trimProcessedCache() {

        while (
            processedSmsIds.size >
            MAX_CACHE
        ) {

            val iterator =
                processedSmsIds.iterator()

            if (
                iterator.hasNext()
            ) {

                iterator.next()

                iterator.remove()

            } else {

                break
            }
        }
    }
}