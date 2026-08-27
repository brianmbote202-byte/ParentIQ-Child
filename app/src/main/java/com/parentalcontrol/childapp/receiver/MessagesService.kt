package com.parentalcontrol.childapp.receiver

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.service.MessageAlertEngine
import com.parentalcontrol.childapp.service.MessageAlertStateManager
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.LinkedHashMap
import java.util.Locale
import java.util.UUID
import kotlin.math.abs

class MessageService : Service() {

    companion object {

        private const val TAG = "MessageService"

        // ========================================================
        // FOREGROUND SERVICE
        // ========================================================

        private const val CHANNEL_ID = "message_service_channel"
        private const val NOTIFICATION_ID = 101

        // ========================================================
        // INTENT EXTRAS
        // ========================================================

        const val EXTRA_SUBSCRIPTION_ID = "subscriptionId"
        const val EXTRA_CARRIER_RAW = "carrierRaw"

        // ========================================================
        // SMS
        // ========================================================

        private const val TYPE_SMS = "SMS"
        private const val TYPE_SECURITY = "SECURITY"

        private const val DIRECTION_INCOMING = "INCOMING"
        private const val DIRECTION_OUTGOING = "OUTGOING"

        // ========================================================
        // DUPLICATE PROTECTION
        // ========================================================

        private const val DUPLICATE_EVENT_WINDOW_MS = 10_000L
        private const val MAX_RECENT_EVENTS = 100

        // ========================================================
        // RESPONSE CACHE
        // ========================================================

        private const val MAX_CONVERSATION_CACHE = 100

        // ========================================================
        // FIREBASE
        // ========================================================

        private const val FIREBASE_MESSAGES_NODE = "messages"
        private const val FIREBASE_CONVERSATIONS_NODE = "conversations"
        private const val FIREBASE_MESSAGES_LIST_NODE = "messages"
        private const val FIREBASE_LAST_MESSAGE_NODE = "lastMessage"
        private const val FIREBASE_SECURITY_NODE = "security"

        // ========================================================
        // SHARED PREFS
        // ========================================================

        private const val PREFS_CHILD = "child_prefs"
        private const val PREF_CHILD_ID = "child_id"

        // ========================================================
        // FALLBACK VALUES
        // ========================================================

        private const val UNKNOWN_NUMBER = "UNKNOWN_NUMBER"
        private const val DEVICE = "DEVICE"
        private const val UNKNOWN = "Unknown"
    }

    // ============================================================
    // FIREBASE
    // ============================================================

    private lateinit var database: FirebaseDatabase

    // ============================================================
    // SMS OBSERVER
    // ============================================================

    private var smsObserver: SmsObserver? = null

    // ============================================================
    // CHILD
    // ============================================================

    @Volatile
    private var childId: String = ""

    // ============================================================
    // DUPLICATE CACHE
    // ============================================================

    private val recentEvents =
        LinkedHashMap<String, Long>()

    // ============================================================
    // LATEST INCOMING MESSAGE CACHE
    // ============================================================

    private val latestIncomingMessageIds =
        LinkedHashMap<String, String>()

    private val latestIncomingTimestamps =
        LinkedHashMap<String, Long>()

    // ============================================================
    // SIM INFO
    // ============================================================

    private data class SimInfo(
        val carrier: String,
        val country: String,
        val simSlot: Int,
        val displayName: String
    )

    // ============================================================
    // SERVICE CREATE
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        database = FirebaseDatabase.getInstance()

        Log.d(TAG, "========================================")
        Log.d(TAG, "MessageService CREATED")
        Log.d(TAG, "FirebaseDatabase initialized")
        Log.d(TAG, "========================================")

        startForegroundSafely()
    }

    // ============================================================
    // START COMMAND
    // ============================================================

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.d(TAG, "========================================")
        Log.d(TAG, "MessageService onStartCommand()")
        Log.d(TAG, "intent=$intent")
        Log.d(TAG, "startId=$startId")
        Log.d(TAG, "========================================")

        try {

            resolveChildId(intent)

            if (childId.isBlank()) {

                Log.e(
                    TAG,
                    "Cannot process event: childId is empty"
                )

                return START_STICKY
            }

            startSmsObserverIfNeeded()

            if (intent == null) {

                Log.d(
                    TAG,
                    "Service restarted without Intent"
                )

                return START_STICKY
            }

            val type =
                intent
                    .getStringExtra("type")
                    ?.trim()
                    ?.uppercase(Locale.ROOT)
                    ?: TYPE_SMS

            Log.d(
                TAG,
                "Processing service event type=$type"
            )

            when (type) {

                TYPE_SMS -> {
                    handleSms(intent)
                }

                TYPE_SECURITY -> {
                    handleSecurity(intent)
                }

                else -> {

                    Log.w(
                        TAG,
                        "Unknown MessageService event type=$type"
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "MessageService onStartCommand FAILED",
                e
            )
        }

        return START_STICKY
    }

    // ============================================================
    // RESOLVE CHILD ID
    // ============================================================

    private fun resolveChildId(intent: Intent?) {

        val prefs =
            getSharedPreferences(
                PREFS_CHILD,
                Context.MODE_PRIVATE
            )

        val savedChildId =
            prefs
                .getString(
                    PREF_CHILD_ID,
                    ""
                )
                ?.trim()
                ?: ""

        val intentChildId =
            intent
                ?.getStringExtra("childId")
                ?.trim()
                ?: ""

        childId =
            intentChildId
                .takeIf { it.isNotBlank() }
                ?: savedChildId

        Log.d(
            TAG,
            "Resolved childId=$childId"
        )
    }

    // ============================================================
    // SMS OBSERVER
    // ============================================================

    private fun startSmsObserverIfNeeded() {

        if (smsObserver != null) {
            return
        }

        try {

            smsObserver =
                SmsObserver(this)

            smsObserver?.start()

            Log.d(
                TAG,
                "SmsObserver STARTED"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start SmsObserver",
                e
            )

            smsObserver = null
        }
    }

    // ============================================================
    // FIREBASE ROOT
    // ============================================================

    private fun messagesRef(
        id: String
    ): DatabaseReference {

        return database
            .getReference(FIREBASE_MESSAGES_NODE)
            .child(id)
    }

    // ============================================================
    // NORMALIZE NUMBER / SENDER
    // ============================================================

    private fun normalizeNumber(
        number: String?,
        fallback: String = UNKNOWN_NUMBER
    ): String {

        if (number.isNullOrBlank()) {
            return fallback
        }

        val cleaned =
            number
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        if (cleaned.isBlank()) {
            return fallback
        }

        // --------------------------------------------------------
        // Alphanumeric sender
        // --------------------------------------------------------

        if (cleaned.any { it.isLetter() }) {

            return cleaned
                .uppercase(Locale.ROOT)
                .replace(
                    Regex("[^A-Z0-9_+.-]"),
                    ""
                )
                .ifBlank {
                    fallback
                }
        }

        // --------------------------------------------------------
        // Phone number
        // --------------------------------------------------------

        return cleaned
            .replace(
                Regex("[^\\d+]"),
                ""
            )
            .ifBlank {
                fallback
            }
    }

    // ============================================================
    // CONVERSATION ID
    // ============================================================

    private fun createConversationId(
        direction: String,
        from: String,
        to: String
    ): String {

        val otherParty =
            if (direction == DIRECTION_INCOMING) {
                from
            } else {
                to
            }

        return normalizeNumber(
            otherParty,
            UNKNOWN_NUMBER
        )
    }

    // ============================================================
    // SUBSCRIPTION INFO
    // ============================================================

    private fun getSubscriptionInfo(
        subscriptionId: Int
    ): SubscriptionInfo? {

        if (
            subscriptionId ==
            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        ) {

            return null
        }

        return try {

            val manager =
                getSystemService(
                    Context.TELEPHONY_SUBSCRIPTION_SERVICE
                ) as SubscriptionManager

            manager.getActiveSubscriptionInfo(
                subscriptionId
            )

        } catch (e: SecurityException) {

            Log.w(
                TAG,
                "SubscriptionInfo permission unavailable",
                e
            )

            null

        } catch (e: Exception) {

            Log.w(
                TAG,
                "SubscriptionInfo lookup failed",
                e
            )

            null
        }
    }

    // ============================================================
    // RESOLVE SIM INFO
    // ============================================================

    private fun resolveSimInfo(
        subscriptionId: Int
    ): SimInfo {

        if (
            subscriptionId ==
            SubscriptionManager.INVALID_SUBSCRIPTION_ID ||
            subscriptionId < 0
        ) {

            return SimInfo(
                carrier = UNKNOWN,
                country = "",
                simSlot = -1,
                displayName = ""
            )
        }

        val info =
            getSubscriptionInfo(
                subscriptionId
            )

        if (info == null) {

            return SimInfo(
                carrier = UNKNOWN,
                country = "",
                simSlot = -1,
                displayName = ""
            )
        }

        val carrier =
            info.carrierName
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: UNKNOWN

        val country =
            info.countryIso
                ?.trim()
                ?.uppercase(Locale.ROOT)
                ?.takeIf { it.isNotBlank() }
                ?: ""

        @Suppress("DEPRECATION")
        val simSlot =
            info.simSlotIndex

        val displayName =
            info.displayName
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: carrier

        Log.d(
            TAG,
            "SIM -> " +
                    "subscriptionId=$subscriptionId, " +
                    "carrier=$carrier, " +
                    "country=$country, " +
                    "simSlot=$simSlot, " +
                    "displayName=$displayName"
        )

        return SimInfo(
            carrier = carrier,
            country = country,
            simSlot = simSlot,
            displayName = displayName
        )
    }

    // ============================================================
    // HANDLE SMS
    // ============================================================

    @SuppressLint("RestrictedApi")
    private fun handleSms(
        intent: Intent
    ) {

        try {

            // ====================================================
            // DIRECTION
            // ====================================================

            val direction =
                normalizeDirection(
                    intent.getStringExtra("direction")
                )

            // ====================================================
            // TIMESTAMP
            // ====================================================

            val timestamp =
                intent.getLongExtra(
                    "timestamp",
                    System.currentTimeMillis()
                )

            // ====================================================
            // CONTENT
            // ====================================================

            val content =
                intent
                    .getStringExtra("content")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "(No Content)"



            // ====================================================
            // RAW FROM
            // ====================================================

            val fromRaw =
                intent
                    .getStringExtra("fromRaw")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: UNKNOWN_NUMBER

            // ====================================================
            // RAW TO
            // ====================================================

            val toRaw =
                intent
                    .getStringExtra("toRaw")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: DEVICE

            // ====================================================
            // FROM
            // ====================================================

            val from =
                normalizeNumber(
                    intent.getStringExtra("from"),
                    normalizeNumber(
                        fromRaw,
                        UNKNOWN_NUMBER
                    )
                )

            // ====================================================
            // TO
            // ====================================================

            val to =
                normalizeNumber(
                    intent.getStringExtra("to"),
                    normalizeNumber(
                        toRaw,
                        DEVICE
                    )
                )

            // ====================================================
            // CONVERSATION
            // ====================================================

            val conversationId =
                createConversationId(
                    direction = direction,
                    from = from,
                    to = to
                )

            // ====================================================
            // SUBSCRIPTION
            // ====================================================

            val subscriptionId =
                intent.getIntExtra(
                    EXTRA_SUBSCRIPTION_ID,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )

            // ====================================================
            // SIM
            // ====================================================

            val simInfo =
                try {

                    resolveSimInfo(
                        subscriptionId
                    )

                } catch (e: Exception) {

                    Log.w(
                        TAG,
                        "SIM resolution failed",
                        e
                    )

                    SimInfo(
                        carrier = UNKNOWN,
                        country = "",
                        simSlot = -1,
                        displayName = ""
                    )
                }

            // ====================================================
            // CARRIER
            // ====================================================

            val carrier =
                simInfo.carrier

            val country =
                simInfo.country

            val simSlot =
                simInfo.simSlot

            val simDisplayName =
                simInfo.displayName

            val intentCarrierRaw =
                intent
                    .getStringExtra(EXTRA_CARRIER_RAW)
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank() &&
                                !it.equals(
                                    DEVICE,
                                    ignoreCase = true
                                )
                    }

            val carrierRaw =
                if (
                    !carrier.equals(
                        UNKNOWN,
                        ignoreCase = true
                    )
                ) {
                    carrier
                } else {
                    intentCarrierRaw ?: UNKNOWN
                }

            // ====================================================
            // DATE
            // ====================================================

            val date =
                SimpleDateFormat(
                    "yyyy-MM-dd",
                    Locale.getDefault()
                ).format(timestamp)

            val month =
                SimpleDateFormat(
                    "yyyy-MM",
                    Locale.getDefault()
                ).format(timestamp)

            val year =
                Calendar
                    .getInstance()
                    .apply {
                        timeInMillis = timestamp
                    }
                    .get(Calendar.YEAR)

            // ====================================================
            // DEBUG
            // ====================================================

            Log.d(TAG, "========================================")
            Log.d(TAG, "PROCESSING SMS")
            Log.d(TAG, "childId=$childId")
            Log.d(TAG, "direction=$direction")
            Log.d(TAG, "fromRaw=$fromRaw")
            Log.d(TAG, "from=$from")
            Log.d(TAG, "toRaw=$toRaw")
            Log.d(TAG, "to=$to")
            Log.d(TAG, "conversationId=$conversationId")
            Log.d(TAG, "content=$content")
            Log.d(TAG, "timestamp=$timestamp")
            Log.d(TAG, "subscriptionId=$subscriptionId")
            Log.d(TAG, "carrier=$carrier")
            Log.d(TAG, "carrierRaw=$carrierRaw")
            Log.d(TAG, "country=$country")
            Log.d(TAG, "simSlot=$simSlot")
            Log.d(TAG, "simDisplayName=$simDisplayName")
            Log.d(TAG, "========================================")

            // ====================================================
            // VALIDATION
            // ====================================================

            if (childId.isBlank()) {

                Log.e(
                    TAG,
                    "SMS rejected: childId is empty"
                )

                return
            }

            // ====================================================
            // DUPLICATE FINGERPRINT
            // ====================================================

            val fingerprint =
                createEventFingerprint(
                    conversationKey = conversationId,
                    direction = direction,
                    timestamp = timestamp,
                    content = content,
                    subscriptionId = subscriptionId
                )

            if (
                isRecentDuplicate(
                    fingerprint,
                    timestamp
                )
            ) {

                Log.w(
                    TAG,
                    "Duplicate SMS ignored"
                )

                return
            }

            rememberEvent(
                fingerprint,
                timestamp
            )

            // ====================================================
// MESSAGE ALERT ANALYSIS
// ====================================================
//
// IMPORTANT:
// The SMS is analyzed locally.
// The complete message is NOT uploaded by the
// alert engine.
//
// Only compact alert state is sent to Firebase
// when a rule matches.
// ====================================================

            // ====================================================
// MESSAGE ALERT ANALYSIS
// ====================================================
//
// The SMS is analyzed locally.
// Only compact alert state is sent to Firebase.
// ====================================================

            try {

                val analysis =
                    MessageAlertEngine.analyze(
                        content
                    )

                if (analysis.matched) {

                    // The conversation participant:
                    // INCOMING  -> sender
                    // OUTGOING  -> recipient
                    val phoneNumber =
                        if (direction == DIRECTION_INCOMING) {
                            from
                        } else {
                            to
                        }

                    Log.w(
                        TAG,
                        "MESSAGE ALERT MATCHED -> " +
                                "direction=$direction, " +
                                "phoneNumber=$phoneNumber, " +
                                "conversationId=$conversationId, " +
                                "severity=${analysis.highestSeverity()}, " +
                                "categories=${analysis.categories()}"
                    )

                    MessageAlertStateManager.processMatches(
                        childId = childId,
                        direction = direction,
                        phoneNumber = phoneNumber,
                        conversationId = conversationId,
                        result = analysis
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Message alert analysis failed",
                    e
                )
            }

            // ====================================================
            // FIREBASE REFERENCES
            // ====================================================

            val conversationRef =
                messagesRef(childId)
                    .child(FIREBASE_CONVERSATIONS_NODE)
                    .child(conversationId)

            val messagesListRef =
                conversationRef
                    .child(FIREBASE_MESSAGES_LIST_NODE)

            val messageRef =
                messagesListRef.push()

            val messageId =
                messageRef.key
                    ?: UUID.randomUUID().toString()

            // ====================================================
            // RESPONSE DETECTION
            // ====================================================

            val responseInfo =
                detectResponse(
                    direction = direction,
                    conversationId = conversationId,
                    timestamp = timestamp
                )

            // ====================================================
            // MESSAGE DATA
            // ====================================================

            val messageData =
                createMessageData(
                    messageId = messageId,
                    conversationId = conversationId,
                    content = content,
                    direction = direction,
                    from = from,
                    to = to,
                    fromRaw = fromRaw,
                    toRaw = toRaw,
                    timestamp = timestamp,
                    date = date,
                    month = month,
                    year = year,
                    carrier = carrier,
                    carrierRaw = carrierRaw,
                    country = country,
                    subscriptionId = subscriptionId,
                    simSlot = simSlot,
                    simDisplayName = simDisplayName,
                    isResponse = responseInfo.isResponse,
                    responseToMessageId =
                        responseInfo.responseToMessageId,
                    responseToTimestamp =
                        responseInfo.responseToTimestamp
                )

            // ====================================================
            // FIREBASE MESSAGE WRITE
            // ====================================================

            Log.d(
                TAG,
                "Writing message -> ${messageRef.path}"
            )

            messageRef
                .setValue(messageData)
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        "SMS SAVED -> ${messageRef.path}"
                    )

                    // ------------------------------------------------
                    // Only update conversation preview after the
                    // actual message has been written.
                    // ------------------------------------------------

                    updateLastMessage(
                        conversationRef = conversationRef,
                        messageData = messageData,
                        timestamp = timestamp
                    )
                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "SMS FIREBASE WRITE FAILED",
                        error
                    )
                }

            // ====================================================
            // REMEMBER INCOMING
            // ====================================================

            if (direction == DIRECTION_INCOMING) {

                rememberLatestIncomingMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    timestamp = timestamp
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "handleSms FAILED",
                e
            )
        }
    }

    // ============================================================
    // DIRECTION
    // ============================================================

    private fun normalizeDirection(
        value: String?
    ): String {

        return when (
            value
                ?.trim()
                ?.uppercase(Locale.ROOT)
        ) {

            DIRECTION_OUTGOING ->
                DIRECTION_OUTGOING

            DIRECTION_INCOMING ->
                DIRECTION_INCOMING

            else ->
                DIRECTION_INCOMING
        }
    }

    // ============================================================
    // RESPONSE INFO
    // ============================================================

    private data class ResponseInfo(
        val isResponse: Boolean,
        val responseToMessageId: String?,
        val responseToTimestamp: Long?
    )

    // ============================================================
    // DETECT RESPONSE
    // ============================================================

    @Synchronized
    private fun detectResponse(
        direction: String,
        conversationId: String,
        timestamp: Long
    ): ResponseInfo {

        if (direction != DIRECTION_OUTGOING) {

            return ResponseInfo(
                isResponse = false,
                responseToMessageId = null,
                responseToTimestamp = null
            )
        }

        val previousId =
            latestIncomingMessageIds[
                conversationId
            ]

        val previousTimestamp =
            latestIncomingTimestamps[
                conversationId
            ]

        if (
            previousId == null ||
            previousTimestamp == null ||
            previousTimestamp >= timestamp
        ) {

            return ResponseInfo(
                isResponse = false,
                responseToMessageId = null,
                responseToTimestamp = null
            )
        }

        Log.d(
            TAG,
            "RESPONSE DETECTED -> " +
                    "conversation=$conversationId " +
                    "responseTo=$previousId"
        )

        return ResponseInfo(
            isResponse = true,
            responseToMessageId = previousId,
            responseToTimestamp = previousTimestamp
        )
    }

    // ============================================================
    // CREATE MESSAGE DATA
    // ============================================================

    private fun createMessageData(
        messageId: String,
        conversationId: String,
        content: String,
        direction: String,
        from: String,
        to: String,
        fromRaw: String,
        toRaw: String,
        timestamp: Long,
        date: String,
        month: String,
        year: Int,
        carrier: String,
        carrierRaw: String,
        country: String,
        subscriptionId: Int,
        simSlot: Int,
        simDisplayName: String,
        isResponse: Boolean,
        responseToMessageId: String?,
        responseToTimestamp: Long?
    ): HashMap<String, Any?> {

        return hashMapOf(
            "messageId" to messageId,
            "conversationId" to conversationId,
            "content" to content,

            "direction" to direction,

            "from" to from,
            "to" to to,

            "fromRaw" to fromRaw,
            "toRaw" to toRaw,

            "timestamp" to timestamp,

            "date" to date,
            "month" to month,
            "year" to year,

            "carrier" to carrier,
            "carrierRaw" to carrierRaw,

            "country" to country,

            "subscriptionId" to subscriptionId,
            "simSlot" to simSlot,
            "simDisplayName" to simDisplayName,

            "isResponse" to isResponse,

            "responseToMessageId" to
                    responseToMessageId,

            "responseToTimestamp" to
                    responseToTimestamp
        )
    }

    // ============================================================
    // UPDATE LAST MESSAGE
    // ============================================================

    @SuppressLint("RestrictedApi")
    private fun updateLastMessage(
        conversationRef: DatabaseReference,
        messageData: HashMap<String, Any?>,
        timestamp: Long
    ) {

        val lastMessageRef =
            conversationRef
                .child(FIREBASE_LAST_MESSAGE_NODE)

        /*
         * Use a Firebase transaction instead of:
         *
         * get()
         * +
         * setValue()
         *
         * This prevents two messages arriving almost
         * simultaneously from overwriting each other
         * with an older message.
         */

        lastMessageRef
            .runTransaction(
                object :
                    com.google.firebase.database.Transaction.Handler {

                    override fun doTransaction(
                        currentData:
                        com.google.firebase.database.MutableData
                    ):
                            com.google.firebase.database.Transaction.Result {

                        val existingTimestamp =
                            currentData
                                .child("timestamp")
                                .getValue(Long::class.java)
                                ?: 0L

                        if (
                            existingTimestamp >
                            timestamp
                        ) {

                            return com.google.firebase.database.Transaction
                                .abort()
                        }

                        currentData.value =
                            messageData

                        return com.google.firebase.database.Transaction
                            .success(
                                currentData
                            )
                    }

                    override fun onComplete(
                        error:
                        com.google.firebase.database.DatabaseError?,
                        committed: Boolean,
                        currentData:
                        com.google.firebase.database.DataSnapshot?
                    ) {

                        if (error != null) {

                            Log.e(
                                TAG,
                                "lastMessage transaction failed",
                                error.toException()
                            )

                            return
                        }

                        if (committed) {

                            Log.d(
                                TAG,
                                "LAST MESSAGE UPDATED -> " +
                                        "${lastMessageRef.path}"
                            )

                        } else {

                            Log.d(
                                TAG,
                                "lastMessage not changed " +
                                        "(existing message is newer)"
                            )
                        }
                    }
                }
            )
    }

    // ============================================================
    // REMEMBER LATEST INCOMING
    // ============================================================

    @Synchronized
    private fun rememberLatestIncomingMessage(
        conversationId: String,
        messageId: String,
        timestamp: Long
    ) {

        val existingTimestamp =
            latestIncomingTimestamps[
                conversationId
            ]

        if (
            existingTimestamp != null &&
            existingTimestamp > timestamp
        ) {
            return
        }

        latestIncomingMessageIds[
            conversationId
        ] = messageId

        latestIncomingTimestamps[
            conversationId
        ] = timestamp

        while (
            latestIncomingMessageIds.size >
            MAX_CONVERSATION_CACHE
        ) {

            val firstKey =
                latestIncomingMessageIds
                    .entries
                    .firstOrNull()
                    ?.key
                    ?: break

            latestIncomingMessageIds.remove(
                firstKey
            )

            latestIncomingTimestamps.remove(
                firstKey
            )
        }

        Log.d(
            TAG,
            "Latest incoming cached -> " +
                    "conversation=$conversationId " +
                    "messageId=$messageId"
        )
    }

    // ============================================================
    // EVENT FINGERPRINT
    // ============================================================

    private fun createEventFingerprint(
        conversationKey: String,
        direction: String,
        timestamp: Long,
        content: String,
        subscriptionId: Int
    ): String {

        val normalizedContent =
            content
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .lowercase(Locale.ROOT)

        val raw =
            "$conversationKey|" +
                    "$direction|" +
                    "$timestamp|" +
                    "$subscriptionId|" +
                    normalizedContent

        return sha256(raw)
    }

    // ============================================================
    // SHA-256
    // ============================================================

    private fun sha256(
        value: String
    ): String {

        return try {

            val digest =
                MessageDigest.getInstance(
                    "SHA-256"
                )

            digest
                .digest(
                    value.toByteArray(
                        Charsets.UTF_8
                    )
                )
                .joinToString("") {
                    "%02x".format(it)
                }

        } catch (e: Exception) {

            value.hashCode().toString()
        }
    }

    // ============================================================
    // DUPLICATE CHECK
    // ============================================================

    @Synchronized
    private fun isRecentDuplicate(
        fingerprint: String,
        timestamp: Long
    ): Boolean {

        cleanupRecentEvents(timestamp)

        val previous =
            recentEvents[fingerprint]
                ?: return false

        return abs(
            timestamp - previous
        ) <= DUPLICATE_EVENT_WINDOW_MS
    }

    // ============================================================
    // REMEMBER EVENT
    // ============================================================

    @Synchronized
    private fun rememberEvent(
        fingerprint: String,
        timestamp: Long
    ) {

        cleanupRecentEvents(timestamp)

        recentEvents[fingerprint] =
            timestamp

        while (
            recentEvents.size >
            MAX_RECENT_EVENTS
        ) {

            val firstKey =
                recentEvents
                    .entries
                    .firstOrNull()
                    ?.key
                    ?: break

            recentEvents.remove(firstKey)
        }
    }

    // ============================================================
    // CLEAN DUPLICATES
    // ============================================================

    @Synchronized
    private fun cleanupRecentEvents(
        currentTimestamp: Long
    ) {

        val iterator =
            recentEvents.iterator()

        while (iterator.hasNext()) {

            val entry =
                iterator.next()

            val age =
                abs(
                    currentTimestamp -
                            entry.value
                )

            if (
                age >
                DUPLICATE_EVENT_WINDOW_MS
            ) {

                iterator.remove()
            }
        }
    }

    // ============================================================
    // SECURITY EVENT
    // ============================================================

    @SuppressLint("RestrictedApi")
    private fun handleSecurity(
        intent: Intent
    ) {

        try {

            if (childId.isBlank()) {

                Log.e(
                    TAG,
                    "Security event rejected: childId empty"
                )

                return
            }

            val event =
                intent
                    .getStringExtra("event")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "UNKNOWN"

            val securityRef =
                messagesRef(childId)
                    .child(FIREBASE_SECURITY_NODE)
                    .push()

            val data =
                hashMapOf<String, Any?>(
                    "type" to TYPE_SECURITY,
                    "event" to event,
                    "timestamp" to
                            System.currentTimeMillis()
                )

            Log.d(
                TAG,
                "Writing security event -> " +
                        securityRef.path
            )

            securityRef
                .setValue(data)
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        "Security event saved -> " +
                                securityRef.path
                    )
                }
                .addOnFailureListener { error ->

                    Log.e(
                        TAG,
                        "Security event failed",
                        error
                    )
                }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "handleSecurity FAILED",
                e
            )
        }
    }

    // ============================================================
    // FOREGROUND SERVICE
    // ============================================================

    private fun startForegroundSafely() {

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                val channel =
                    NotificationChannel(
                        CHANNEL_ID,
                        "Messages Monitoring",
                        NotificationManager
                            .IMPORTANCE_LOW
                    )

                channel.setShowBadge(false)

                val manager =
                    getSystemService(
                        NotificationManager::class.java
                    )

                manager?.createNotificationChannel(
                    channel
                )
            }

            val notification =
                NotificationCompat
                    .Builder(
                        this,
                        CHANNEL_ID
                    )
                    .setContentTitle(
                        "Parental Control Active"
                    )
                    .setContentText(
                        "Monitoring SMS messages"
                    )
                    .setSmallIcon(
                        R.mipmap.ic_launcher
                    )
                    .setOngoing(true)
                    .setCategory(
                        NotificationCompat.CATEGORY_SERVICE
                    )
                    .setForegroundServiceBehavior(
                        NotificationCompat
                            .FOREGROUND_SERVICE_IMMEDIATE
                    )
                    .build()

            startForeground(
                NOTIFICATION_ID,
                notification
            )

            Log.d(
                TAG,
                "Foreground service started"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Foreground service start FAILED",
                e
            )

            stopSelf()
        }
    }

    // ============================================================
    // DESTROY
    // ============================================================

    override fun onDestroy() {

        try {

            smsObserver?.stop()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed stopping SmsObserver",
                e
            )
        }

        smsObserver = null

        synchronized(recentEvents) {
            recentEvents.clear()
        }

        synchronized(latestIncomingMessageIds) {

            latestIncomingMessageIds.clear()
            latestIncomingTimestamps.clear()
        }

        Log.d(
            TAG,
            "MessageService DESTROYED"
        )

        super.onDestroy()
    }

    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? {

        return null
    }
}