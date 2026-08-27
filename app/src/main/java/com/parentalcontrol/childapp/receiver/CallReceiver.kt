package com.parentalcontrol.childapp.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CallLog
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat

@Suppress("DEPRECATION")
class CallReceiver : BroadcastReceiver() {

    companion object {

        private const val TAG = "CALL_DEBUG"

        private var lastState =
            TelephonyManager.CALL_STATE_IDLE

        private var callStartTime: Long = 0L

        private var currentNumber: String = ""

        private var isOutgoing = false

        // =====================================================
        // IMPORTANT
        // =====================================================

        private var currentSubscriptionId =
            SubscriptionManager.INVALID_SUBSCRIPTION_ID

        private var currentSimSlot = -1
    }


    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {

        if (intent == null) return

        val prefs =
            context.getSharedPreferences(
                "child_prefs",
                Context.MODE_PRIVATE
            )

        val childId =
            prefs.getString(
                "child_id",
                null
            ) ?: return


        Log.d(
            TAG,
            "📞 Action received: ${intent.action}"
        )


        if (
            intent.action ==
            TelephonyManager.ACTION_PHONE_STATE_CHANGED
        ) {

            handlePhoneStateChanged(
                context,
                intent,
                childId
            )
        }
    }


    // =========================================================
    // PHONE STATE
    // =========================================================

    private fun handlePhoneStateChanged(
        context: Context,
        intent: Intent,
        childId: String
    ) {

        val stateStr =
            intent.getStringExtra(
                TelephonyManager.EXTRA_STATE
            )


        val state =
            when (stateStr) {

                TelephonyManager.EXTRA_STATE_RINGING ->
                    TelephonyManager.CALL_STATE_RINGING

                TelephonyManager.EXTRA_STATE_OFFHOOK ->
                    TelephonyManager.CALL_STATE_OFFHOOK

                TelephonyManager.EXTRA_STATE_IDLE ->
                    TelephonyManager.CALL_STATE_IDLE

                else -> return
            }


        val incomingNumber =
            intent.getStringExtra(
                TelephonyManager.EXTRA_INCOMING_NUMBER
            ) ?: ""


        // =====================================================
        // TRY TO GET SUBSCRIPTION FROM BROADCAST
        // =====================================================

        val broadcastSubscriptionId =
            getSubscriptionIdFromIntent(intent)


        if (
            SubscriptionManager.isValidSubscriptionId(
                broadcastSubscriptionId
            )
        ) {

            currentSubscriptionId =
                broadcastSubscriptionId


            currentSimSlot =
                getSimSlot(
                    context,
                    currentSubscriptionId
                )


            Log.d(
                TAG,
                """
                📱 SUBSCRIPTION FROM BROADCAST
                subscriptionId=$currentSubscriptionId
                simSlot=$currentSimSlot
                """.trimIndent()
            )
        }


        Log.d(
            TAG,
            """
            📡 STATE
            state=$state
            lastState=$lastState
            incoming=$incomingNumber
            subscriptionId=$currentSubscriptionId
            simSlot=$currentSimSlot
            """.trimIndent()
        )


        when (state) {

            // =================================================
            // INCOMING RINGING
            // =================================================

            TelephonyManager.CALL_STATE_RINGING -> {

                currentNumber =
                    incomingNumber
                        .ifBlank {
                            "UNKNOWN"
                        }

                isOutgoing = false


                Log.d(
                    TAG,
                    "📲 Incoming ringing → $currentNumber"
                )
            }


            // =================================================
            // CALL CONNECTED / OUTGOING STARTED
            // =================================================

            TelephonyManager.CALL_STATE_OFFHOOK -> {

                isOutgoing =
                    lastState !=
                            TelephonyManager.CALL_STATE_RINGING


                if (
                    isOutgoing &&
                    currentNumber.isBlank()
                ) {

                    currentNumber =
                        getLastCallNumber(context)
                }


                callStartTime =
                    System.currentTimeMillis()


                // If broadcast did not provide subscription,
                // try to determine the current voice subscription.
                if (
                    !SubscriptionManager
                        .isValidSubscriptionId(
                            currentSubscriptionId
                        )
                ) {

                    currentSubscriptionId =
                        getCurrentVoiceSubscriptionId()


                    currentSimSlot =
                        getSimSlot(
                            context,
                            currentSubscriptionId
                        )
                }


                Log.d(
                    TAG,
                    """
                    📞 CALL STARTED
                    outgoing=$isOutgoing
                    number=$currentNumber
                    subscriptionId=$currentSubscriptionId
                    simSlot=$currentSimSlot
                    """.trimIndent()
                )
            }


            // =================================================
            // CALL ENDED
            // =================================================

            TelephonyManager.CALL_STATE_IDLE -> {

                if (
                    lastState ==
                    TelephonyManager.CALL_STATE_OFFHOOK
                ) {

                    handleCallEnd(
                        context,
                        childId
                    )
                }


                currentNumber = ""

                isOutgoing = false

                currentSubscriptionId =
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID

                currentSimSlot = -1
            }
        }


        lastState = state
    }


    // =========================================================
    // GET SUBSCRIPTION ID FROM BROADCAST
    // =========================================================

    private fun getSubscriptionIdFromIntent(
        intent: Intent
    ): Int {

        var subscriptionId =
            intent.getIntExtra(
                TelephonyManager.EXTRA_SUBSCRIPTION_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )


        if (
            !SubscriptionManager
                .isValidSubscriptionId(
                    subscriptionId
                )
        ) {

            subscriptionId =
                intent.getIntExtra(
                    SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )
        }


        return subscriptionId
    }


    // =========================================================
    // GET SIM SLOT
    // =========================================================

    private fun getSimSlot(
        context: Context,
        subscriptionId: Int
    ): Int {

        if (
            !SubscriptionManager
                .isValidSubscriptionId(
                    subscriptionId
                )
        ) {
            return -1
        }


        return try {

            val subscriptionManager =
                context.getSystemService(
                    SubscriptionManager::class.java
                )


            val info =
                subscriptionManager
                    ?.getActiveSubscriptionInfo(
                        subscriptionId
                    )


            info?.simSlotIndex ?: -1

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "❌ Cannot read SIM slot",
                e
            )

            -1
        }
    }


    // =========================================================
    // CURRENT VOICE SUBSCRIPTION
    // =========================================================

    private fun getCurrentVoiceSubscriptionId(): Int {

        return try {

            SubscriptionManager
                .getDefaultVoiceSubscriptionId()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Cannot get default voice subscription",
                e
            )

            SubscriptionManager.INVALID_SUBSCRIPTION_ID
        }
    }


    // =========================================================
    // CALL LOG TYPE
    // =========================================================

    private fun getLastCallType(
        context: Context
    ): Int {

        return try {

            val cursor =
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    null,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )


            cursor?.use {

                if (it.moveToFirst()) {

                    val typeIndex =
                        it.getColumnIndex(
                            CallLog.Calls.TYPE
                        )


                    if (typeIndex != -1) {

                        return it.getInt(
                            typeIndex
                        )
                    }
                }
            }


            CallLog.Calls.MISSED_TYPE

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ CallLog TYPE error",
                e
            )

            CallLog.Calls.MISSED_TYPE
        }
    }


    // =========================================================
    // CALL END
    // =========================================================

    private fun handleCallEnd(
        context: Context,
        childId: String
    ) {

        val prefs =
            context.getSharedPreferences(
                "child_prefs",
                Context.MODE_PRIVATE
            )


        val childName =
            prefs.getString(
                "child_name",
                null
            ) ?: "DEVICE_${
                prefs.getString(
                    "child_id",
                    "XXXX"
                )?.takeLast(4)
            }"


        // =====================================================
        // NUMBER
        // =====================================================

        val rawNumber =
            currentNumber
                .ifBlank {
                    getLastCallNumber(context)
                }


        val cleanNumber =
            when {

                rawNumber.isBlank() ->
                    "UNKNOWN"

                rawNumber.equals(
                    "PRIVATE",
                    true
                ) ->
                    "PRIVATE"

                else ->
                    rawNumber
            }


        // =====================================================
        // DURATION
        // =====================================================

        val duration =
            if (callStartTime > 0L) {

                System.currentTimeMillis() -
                        callStartTime

            } else {
                0L
            }


        // =====================================================
        // TYPE
        // =====================================================

        val callType =
            getLastCallType(context)


        val direction =
            when (callType) {

                CallLog.Calls.OUTGOING_TYPE ->
                    "OUTGOING"

                CallLog.Calls.INCOMING_TYPE ->
                    "INCOMING"

                else ->
                    "MISSED"
            }


        // =====================================================
        // FROM / TO
        // =====================================================

        val from =
            if (isOutgoing) {
                childName
            } else {
                cleanNumber
            }


        val to =
            if (isOutgoing) {
                cleanNumber
            } else {
                childName
            }


        // =====================================================
        // VALIDATION
        // =====================================================

        if (
            duration < 2000L ||
            cleanNumber == "UNKNOWN"
        ) {

            Log.d(
                TAG,
                "⚠️ Ignoring short or invalid call"
            )

            return
        }


        // =====================================================
        // LOG FINAL SIM
        // =====================================================

        Log.d(
            TAG,
            """
            📞 FINAL CALL
            -----------------------------
            number=$cleanNumber
            direction=$direction
            duration=$duration
            subscriptionId=$currentSubscriptionId
            simSlot=$currentSimSlot
            -----------------------------
            """.trimIndent()
        )


        // =====================================================
        // SEND TO SERVICE
        // =====================================================

        sendToService(
            context = context,
            childId = childId,
            direction = direction,
            number = cleanNumber,
            from = from,
            to = to,
            timestamp = callStartTime,
            duration = duration,
            subscriptionId = currentSubscriptionId
        )
    }


    // =========================================================
    // SEND TO SERVICE
    // =========================================================

    private fun sendToService(
        context: Context,
        childId: String,
        direction: String,
        number: String,
        from: String,
        to: String,
        timestamp: Long,
        duration: Long,
        subscriptionId: Int
    ) {

        try {

            val intent =
                Intent(
                    context,
                    CallService::class.java
                ).apply {

                    putExtra(
                        "childId",
                        childId
                    )

                    putExtra(
                        "direction",
                        direction
                    )

                    putExtra(
                        "number",
                        number
                    )

                    putExtra(
                        "from",
                        from
                    )

                    putExtra(
                        "to",
                        to
                    )

                    putExtra(
                        "timestamp",
                        timestamp
                    )

                    putExtra(
                        "duration",
                        duration
                    )

                    // ⭐ IMPORTANT
                    putExtra(
                        CallService.EXTRA_SUBSCRIPTION_ID,
                        subscriptionId
                    )
                }


            ContextCompat.startForegroundService(
                context,
                intent
            )


            Log.d(
                TAG,
                """
                🚀 Sent call to service
                direction=$direction
                number=$number
                subscriptionId=$subscriptionId
                """.trimIndent()
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Service start failed",
                e
            )
        }
    }


    // =========================================================
    // LAST CALL NUMBER
    // =========================================================

    private fun getLastCallNumber(
        context: Context
    ): String {

        return try {

            val cursor =
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    null,
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )


            cursor?.use {

                if (it.moveToFirst()) {

                    val index =
                        it.getColumnIndex(
                            CallLog.Calls.NUMBER
                        )


                    if (index != -1) {

                        return it.getString(index)
                            ?: "UNKNOWN"
                    }
                }
            }


            "UNKNOWN"

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ CallLog error",
                e
            )

            "UNKNOWN"
        }
    }
}