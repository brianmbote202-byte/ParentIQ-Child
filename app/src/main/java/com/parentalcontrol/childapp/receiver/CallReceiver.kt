package com.parentalcontrol.childapp.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.ComponentName
import android.os.Build
import android.telecom.PhoneAccountHandle
import androidx.annotation.RequiresApi
import android.telecom.PhoneAccount
import android.telecom.TelecomManager

@Suppress("DEPRECATION")
class CallReceiver : BroadcastReceiver() {

    companion object {

        private const val TAG = "CALL_DEBUG"
        private const val TEST_TAG = "CALL_DEBUG_TEST"

        /*
         * Give Android time to commit the completed call
         * into CallLog after IDLE.
         */
        private const val CALL_LOG_INITIAL_DELAY = 800L

        /*
         * Additional retry delay.
         */
        private const val CALL_LOG_RETRY_DELAY = 700L

        /*
         * Maximum number of CallLog attempts.
         */
        private const val MAX_CALL_LOG_ATTEMPTS = 4

        /*
         * Search this many milliseconds before OFFHOOK.
         *
         * For incoming calls the CallLog DATE can represent
         * the beginning of the ringing period rather than
         * the exact OFFHOOK moment.
         */
        private const val CALL_LOG_LOOKBACK_MS = 30_000L

        /*
         * Small tolerance after the call start.
         */
        private const val CALL_LOG_LOOKAHEAD_MS = 15_000L

        /*
         * Maximum recent rows inspected per query.
         */
        private const val MAX_CALL_LOG_ROWS = 50

        /*
         * Calls shorter than this are normally not useful
         * monitoring records unless they are MISSED.
         */
        private const val MIN_VALID_CALL_DURATION_MS = 2_000L

        private var lastState =
            TelephonyManager.CALL_STATE_IDLE

        private var callStartTime = 0L

        private var currentNumber = ""

        private var isOutgoing = false

        private var currentSubscriptionId =
            SubscriptionManager.INVALID_SUBSCRIPTION_ID

        private var currentSimSlot = -1

        private var currentCarrierName = "Unknown"

        private var currentDisplayName = "Unknown"
    }

    // =========================================================
    // IMMUTABLE CALL SNAPSHOT
    // =========================================================

    /*
     * Everything needed after IDLE is copied here.
     *
     * This is important because CallLog processing is delayed.
     * Android may deliver additional PHONE_STATE broadcasts while
     * the delayed lookup is waiting.
     */
    private data class CallSnapshot(
        val number: String,
        val outgoing: Boolean,
        val callStartTime: Long,
        val subscriptionId: Int,
        val simSlot: Int,
        val carrierName: String,
        val displayName: String
    )

    // =========================================================
    // CALL LOG RESULT
    // =========================================================

    private data class CallLogResult(
        val found: Boolean = false,
        val number: String = "",
        val date: Long = 0L,
        val type: Int = -1,
        val durationSeconds: Long = 0L,
        val phoneAccountId: String = "",
        val phoneAccountComponent: String = "",
        val rowId: Long = -1L
    )

    // =========================================================
    // RESOLVED SIM
    // =========================================================

    private data class ResolvedSim(
        val subscriptionId: Int =
            SubscriptionManager.INVALID_SUBSCRIPTION_ID,

        val simSlot: Int = -1,

        val carrierName: String = "Unknown",

        val displayName: String = "Unknown",

        val countryIso: String = "Unknown",

        val source: String = "Unknown"
    )

    // =========================================================
    // CONTACT RESULT
    // =========================================================

    private data class ContactLookupResult(
        val name: String = "",
        val saved: Boolean = false
    )

    // =========================================================
    // CALLLOG CANDIDATE
    // =========================================================

    /*
     * Internal candidate used while scoring recent CallLog rows.
     *
     * We do not automatically assume row #1 is our call.
     */
    private data class CallLogCandidate(
        val number: String,
        val date: Long,
        val type: Int,
        val durationSeconds: Long,
        val phoneAccountId: String,
        val phoneAccountComponent: String,
        val rowId: Long,
        val score: Int
    )

    // =========================================================
    // RECEIVER
    // =========================================================

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onReceive(
        context: Context,
        intent: Intent?
    ) {

        Log.e(
            TEST_TAG,
            "=============================================="
        )

        Log.e(
            TEST_TAG,
            "ðŸ”¥ðŸ”¥ðŸ”¥ CALL RECEIVER FIRED ðŸ”¥ðŸ”¥ðŸ”¥"
        )

        Log.e(
            TEST_TAG,
            "ðŸ“¦ Package = ${context.packageName}"
        )

        Log.e(
            TEST_TAG,
            "ðŸ“¨ Intent = $intent"
        )

        Log.e(
            TEST_TAG,
            "ðŸŽ¯ Action = ${intent?.action}"
        )

        Log.e(
            TEST_TAG,
            "=============================================="
        )

        if (intent == null) {

            Log.e(
                TEST_TAG,
                "âŒ NULL INTENT"
            )

            return
        }

        val phoneState =
            intent.getStringExtra(
                TelephonyManager.EXTRA_STATE
            )

        val incomingNumber =
            intent.getStringExtra(
                TelephonyManager.EXTRA_INCOMING_NUMBER
            )

        val broadcastSubscriptionId =
            intent.getIntExtra(
                TelephonyManager.EXTRA_SUBSCRIPTION_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )

        Log.e(
            TEST_TAG,
            "ðŸ“¡ PHONE STATE = $phoneState"
        )

        Log.e(
            TEST_TAG,
            "ðŸ“ž INCOMING NUMBER = $incomingNumber"
        )

        Log.e(
            TEST_TAG,
            "ðŸ“± BROADCAST SUBSCRIPTION ID = $broadcastSubscriptionId"
        )

        Log.e(
            TEST_TAG,
            "ðŸ“± VALID SUBSCRIPTION = ${
                SubscriptionManager.isValidSubscriptionId(
                    broadcastSubscriptionId
                )
            }"
        )

        // =====================================================
        // CHILD ID
        // =====================================================

        val prefs =
            context.getSharedPreferences(
                "child_prefs",
                Context.MODE_PRIVATE
            )

        val childId =
            prefs.getString(
                "child_id",
                null
            )

        Log.e(
            TEST_TAG,
            "ðŸ‘¶ CHILD ID = $childId"
        )

        if (childId.isNullOrBlank()) {

            Log.e(
                TEST_TAG,
                "âŒ NO CHILD ID FOUND"
            )

            return
        }

        // =====================================================
        // PHONE STATE
        // =====================================================

        if (
            intent.action ==
            TelephonyManager.ACTION_PHONE_STATE_CHANGED
        ) {

            Log.e(
                TEST_TAG,
                "ðŸ“ž PHONE_STATE broadcast detected"
            )

            val pendingResult =
                goAsync()

            var finished = false

            val finishBroadcast = {

                if (!finished) {

                    finished = true

                    try {

                        pendingResult.finish()

                    } catch (e: Exception) {

                        Log.w(
                            TAG,
                            "âš ï¸ Broadcast finish failed: ${e.message}"
                        )
                    }
                }
            }

            try {

                handlePhoneStateChanged(
                    context = context,
                    intent = intent,
                    childId = childId,
                    finishBroadcast = finishBroadcast
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "âŒ PHONE_STATE processing failed",
                    e
                )

                finishBroadcast()
            }

            Log.e(
                TEST_TAG,
                "âœ… handlePhoneStateChanged() scheduled/returned"
            )

            return
        }

        // =====================================================
        // LEGACY OUTGOING
        // =====================================================

        if (
            intent.action ==
            Intent.ACTION_NEW_OUTGOING_CALL
        ) {

            Log.e(
                TEST_TAG,
                "ðŸ“¤ NEW_OUTGOING_CALL detected"
            )

            /*
             * Modern Android may not deliver this broadcast.
             *
             * We intentionally do not depend on it.
             *
             * The completed CallLog is the authoritative
             * fallback for outgoing numbers.
             */

            val number =
                intent.getStringExtra(
                    Intent.EXTRA_PHONE_NUMBER
                )

            if (!number.isNullOrBlank()) {

                currentNumber =
                    number.trim()

                isOutgoing = true

                Log.d(
                    TAG,
                    "ðŸ“¤ Legacy outgoing number captured: $currentNumber"
                )
            }

            return
        }

        Log.e(
            TEST_TAG,
            "â„¹ï¸ UNHANDLED ACTION = ${intent.action}"
        )
    }

    // =========================================================
    // PHONE STATE
    // =========================================================

    private fun handlePhoneStateChanged(
        context: Context,
        intent: Intent,
        childId: String,
        finishBroadcast: () -> Unit
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

                else -> {

                    finishBroadcast()
                    return
                }
            }

        val incomingNumber =
            intent.getStringExtra(
                TelephonyManager.EXTRA_INCOMING_NUMBER
            ) ?: ""

        // =====================================================
        // BROADCAST SUBSCRIPTION
        // =====================================================

        val broadcastSubscriptionId =
            getSubscriptionIdFromIntent(intent)

        if (
            SubscriptionManager.isValidSubscriptionId(
                broadcastSubscriptionId
            )
        ) {

            setCurrentSubscription(
                context = context,
                subscriptionId = broadcastSubscriptionId,
                source = "PHONE_STATE broadcast"
            )
        }

        Log.d(
            TAG,
            """
            ðŸ“¡ CURRENT CALL STATE
            --------------------------------
            state=$state
            lastState=$lastState
            incoming=$incomingNumber
            subscriptionId=$currentSubscriptionId
            simSlot=$currentSimSlot
            carrier=$currentCarrierName
            displayName=$currentDisplayName
            --------------------------------
            """.trimIndent()
        )

        when (state) {

            // =================================================
            // RINGING
            // =================================================

            TelephonyManager.CALL_STATE_RINGING -> {

                currentNumber =
                    incomingNumber
                        .trim()
                        .ifBlank {
                            "UNKNOWN"
                        }

                isOutgoing = false

                callStartTime = 0L

                Log.d(
                    TAG,
                    "ðŸ“² INCOMING RINGING â†’ $currentNumber"
                )

                lastState = state

                finishBroadcast()
            }

            // =================================================
            // OFFHOOK
            // =================================================

            TelephonyManager.CALL_STATE_OFFHOOK -> {

                /*
                 * Do not overwrite the original OFFHOOK timestamp
                 * if Android repeats OFFHOOK.
                 */
                if (
                    lastState !=
                    TelephonyManager.CALL_STATE_OFFHOOK
                ) {

                    callStartTime =
                        System.currentTimeMillis()
                }

                /*
                 * If previous state was RINGING, this is an
                 * answered incoming call.
                 *
                 * Otherwise it is an outgoing call.
                 */
                isOutgoing =
                    lastState !=
                            TelephonyManager.CALL_STATE_RINGING

                /*
                 * For outgoing calls PHONE_STATE commonly does
                 * not provide the dialed number.
                 *
                 * We deliberately keep the number as UNKNOWN
                 * rather than guessing.
                 *
                 * readLatestMatchingCall() will recover it from
                 * CallLog after IDLE.
                 */

                if (
                    isOutgoing &&
                    (
                            currentNumber.isBlank() ||
                                    currentNumber.equals(
                                        "UNKNOWN",
                                        true
                                    )
                            )
                ) {

                    currentNumber = "UNKNOWN"
                }

                Log.d(
                    TAG,
                    """
                    ðŸ“ž CALL STARTED
                    --------------------------------
                    outgoing=$isOutgoing
                    number=$currentNumber
                    callStartTime=$callStartTime
                    subscriptionId=$currentSubscriptionId
                    simSlot=$currentSimSlot
                    carrier=$currentCarrierName
                    displayName=$currentDisplayName
                    --------------------------------
                    """.trimIndent()
                )

                lastState = state

                finishBroadcast()
            }

            // =================================================
            // IDLE
            // =================================================

            TelephonyManager.CALL_STATE_IDLE -> {

                /*
                 * Only process:
                 *
                 * OFFHOOK -> IDLE
                 *
                 * This prevents repeated IDLE broadcasts from
                 * producing duplicate records.
                 */
                if (
                    lastState ==
                    TelephonyManager.CALL_STATE_OFFHOOK
                ) {

                    val snapshot =
                        CallSnapshot(
                            number = currentNumber,
                            outgoing = isOutgoing,
                            callStartTime = callStartTime,
                            subscriptionId =
                                currentSubscriptionId,
                            simSlot =
                                currentSimSlot,
                            carrierName =
                                currentCarrierName,
                            displayName =
                                currentDisplayName
                        )

                    Log.d(
                        TAG,
                        """
                        ðŸ“¸ CALL SNAPSHOT
                        =================================
                        number          = ${snapshot.number}
                        outgoing        = ${snapshot.outgoing}
                        callStart       = ${snapshot.callStartTime}
                        subscriptionId  = ${snapshot.subscriptionId}
                        simSlot         = ${snapshot.simSlot}
                        carrier         = ${snapshot.carrierName}
                        displayName     = ${snapshot.displayName}
                        =================================
                        """.trimIndent()
                    )

                    handleCallEnd(
                        context = context,
                        childId = childId,
                        snapshot = snapshot,
                        finishBroadcast = finishBroadcast
                    )

                } else {

                    finishBroadcast()
                }

                /*
                 * Safe because all delayed processing uses the
                 * immutable CallSnapshot.
                 */
                resetCallState()

                lastState = state
            }
        }
    }

    // =========================================================
    // RESET
    // =========================================================

    private fun resetCallState() {

        currentNumber = ""

        isOutgoing = false

        callStartTime = 0L

        currentSubscriptionId =
            SubscriptionManager.INVALID_SUBSCRIPTION_ID

        currentSimSlot = -1

        currentCarrierName = "Unknown"

        currentDisplayName = "Unknown"
    }

    // =========================================================
    // GET SUBSCRIPTION ID
    // =========================================================

    private fun getSubscriptionIdFromIntent(
        intent: Intent
    ): Int {

        val subscriptionId =
            intent.getIntExtra(
                TelephonyManager.EXTRA_SUBSCRIPTION_ID,
                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            )

        if (
            SubscriptionManager.isValidSubscriptionId(
                subscriptionId
            )
        ) {

            Log.d(
                TAG,
                "ðŸ“± PHONE_STATE subscriptionId=$subscriptionId"
            )

            return subscriptionId
        }

        Log.d(
            TAG,
            "âš ï¸ PHONE_STATE subscriptionId is INVALID"
        )

        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    // =========================================================
    // SET CURRENT SUBSCRIPTION
    // =========================================================

    private fun setCurrentSubscription(
        context: Context,
        subscriptionId: Int,
        source: String
    ) {

        if (
            !SubscriptionManager.isValidSubscriptionId(
                subscriptionId
            )
        ) {
            return
        }

        try {

            val info =
                getSubscriptionInfo(
                    context = context,
                    subscriptionId = subscriptionId
                )

            if (info == null) {

                Log.w(
                    TAG,
                    """
                    âš ï¸ SUBSCRIPTION NOT FOUND
                    source=$source
                    subscriptionId=$subscriptionId
                    """.trimIndent()
                )

                return
            }

            applySubscriptionInfo(
                info = info,
                source = source
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ Cannot read subscription information",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ Subscription resolution failed",
                e
            )
        }
    }

    // =========================================================
    // APPLY SUBSCRIPTION INFO
    // =========================================================

    private fun applySubscriptionInfo(
        info: SubscriptionInfo,
        source: String
    ) {

        currentSubscriptionId =
            info.subscriptionId

        currentSimSlot =
            info.simSlotIndex

        currentCarrierName =
            info.carrierName
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Unknown"

        currentDisplayName =
            info.displayName
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: currentCarrierName

        Log.d(
            TAG,
            """
            ðŸ“¡ SUBSCRIPTION INFO APPLIED
            source=$source
            subscriptionId=$currentSubscriptionId
            simSlot=$currentSimSlot
            carrier=$currentCarrierName
            displayName=$currentDisplayName
            countryIso=${info.countryIso}
            mcc=${info.mcc}
            mnc=${info.mnc}
            """.trimIndent()
        )
    }

    // =========================================================
    // DYNAMIC CARRIER NAME
    // =========================================================

    private fun getDynamicCarrierName(
        info: SubscriptionInfo,
        context: Context
    ): String {

        val carrierName =
            info.carrierName
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }

        if (!carrierName.isNullOrBlank()) {
            return carrierName
        }

        return try {

            val telephonyManager =
                context.getSystemService(
                    TelephonyManager::class.java
                )

            if (telephonyManager == null) {

                "Unknown"

            } else {

                telephonyManager
                    .simOperatorName
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "Unknown"
            }

        } catch (e: SecurityException) {

            Log.w(
                TAG,
                "âš ï¸ Cannot read dynamic carrier name: ${e.message}"
            )

            "Unknown"

        } catch (e: Exception) {

            Log.w(
                TAG,
                "âš ï¸ Dynamic carrier lookup failed: ${e.message}"
            )

            "Unknown"
        }
    }

    // =========================================================
    // CALL END
    // =========================================================

    private fun handleCallEnd(
        context: Context,
        childId: String,
        snapshot: CallSnapshot,
        finishBroadcast: () -> Unit
    ) {

        Log.d(
            TAG,
            "â³ Waiting for CallLog to commit completed call..."
        )

        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            processCallLog(
                context = context,
                childId = childId,
                snapshot = snapshot,
                attempt = 1,
                finishBroadcast = finishBroadcast
            )

        }, CALL_LOG_INITIAL_DELAY)
    }

    // =========================================================
    // PROCESS CALL LOG
    // =========================================================

    private fun processCallLog(
        context: Context,
        childId: String,
        snapshot: CallSnapshot,
        attempt: Int,
        finishBroadcast: () -> Unit
    ) {

        Log.d(
            TAG,
            """
            ðŸ”Ž CALLLOG RESOLUTION
            =================================
            attempt=$attempt/$MAX_CALL_LOG_ATTEMPTS
            snapshotNumber=${snapshot.number}
            snapshotStart=${snapshot.callStartTime}
            expectedType=${
                if (snapshot.outgoing) {
                    "OUTGOING"
                } else {
                    "INCOMING"
                }
            }
            =================================
            """.trimIndent()
        )

        val result =
            readLatestMatchingCall(
                context = context,
                snapshot = snapshot
            )

        if (!result.found) {

            if (
                attempt <
                MAX_CALL_LOG_ATTEMPTS
            ) {

                Log.w(
                    TAG,
                    "âš ï¸ Matching CallLog not ready/matched. Retrying..."
                )

                Handler(
                    Looper.getMainLooper()
                ).postDelayed({

                    processCallLog(
                        context = context,
                        childId = childId,
                        snapshot = snapshot,
                        attempt = attempt + 1,
                        finishBroadcast = finishBroadcast
                    )

                }, CALL_LOG_RETRY_DELAY)

                return
            }

            Log.e(
                TAG,
                "âŒ Could not find matching CallLog after retries"
            )

            /*
             * We only reach this path when CallLog did not
             * provide enough information.
             */
            finishCall(
                context = context,
                childId = childId,
                snapshot = snapshot,
                callLog = null,
                resolvedSim =
                    resolveFallbackSim(
                        context = context,
                        snapshot = snapshot
                    ),
                finishBroadcast = finishBroadcast
            )

            return
        }

        Log.d(
            TAG,
            """
            âœ… MATCHING CALLLOG FOUND
            =================================
            rowId           = ${result.rowId}
            number          = ${result.number}
            date            = ${result.date}
            type            = ${result.type}
            duration        = ${result.durationSeconds}s
            phoneAccountId  = ${result.phoneAccountId}
            component       = ${result.phoneAccountComponent}
            =================================
            """.trimIndent()
        )

        val resolvedSim =
            resolveActualSim(
                context = context,
                snapshot = snapshot,
                callLog = result
            )

        Log.e(
            TAG,
            """
            ðŸ§­ðŸ§­ðŸ§­ FINAL SIM RESOLUTION ðŸ§­ðŸ§­ðŸ§­
            =========================================
            source          = ${resolvedSim.source}
            subscriptionId  = ${resolvedSim.subscriptionId}
            simSlot         = ${resolvedSim.simSlot}
            carrierName     = ${resolvedSim.carrierName}
            displayName     = ${resolvedSim.displayName}
            countryIso      = ${resolvedSim.countryIso}
            =========================================
            """.trimIndent()
        )

        finishCall(
            context = context,
            childId = childId,
            snapshot = snapshot,
            callLog = result,
            resolvedSim = resolvedSim,
            finishBroadcast = finishBroadcast
        )
    }

    // =========================================================
    // RESOLVE ACTUAL SIM
    // =========================================================

    private fun resolveActualSim(
        context: Context,
        snapshot: CallSnapshot,
        callLog: CallLogResult
    ): ResolvedSim {

        // =====================================================
        // 1. VALID PHONE_STATE SUBSCRIPTION
        // =====================================================

        if (
            SubscriptionManager.isValidSubscriptionId(
                snapshot.subscriptionId
            )
        ) {

            val info =
                getSubscriptionInfo(
                    context = context,
                    subscriptionId =
                        snapshot.subscriptionId
                )

            if (info != null) {

                return subscriptionInfoToResolvedSim(
                    info = info,
                    source = "PHONE_STATE subscription"
                )
            }
        }

        // =====================================================
        // 2. CALLLOG PHONE ACCOUNT
        // =====================================================

        if (
            callLog.phoneAccountId.isNotBlank()
        ) {

            val info =
                findSubscriptionForPhoneAccount(
                    context = context,
                    phoneAccountId =
                        callLog.phoneAccountId,
                    phoneAccountComponent =
                        callLog.phoneAccountComponent
                )

            if (info != null) {

                return subscriptionInfoToResolvedSim(
                    info = info,
                    source = "CallLog PHONE_ACCOUNT_ID"
                )
            }
        }

        // =====================================================
        // 3. EXACTLY ONE ACTIVE SUBSCRIPTION
        // =====================================================

        val subscriptions =
            getActiveSubscriptions(
                context
            )

        if (
            subscriptions.size == 1
        ) {

            val onlySim =
                subscriptions.first()

            Log.d(
                TAG,
                """
                ðŸŸ¢ ONLY ONE ACTIVE SIM
                =================================
                subscriptionId=${onlySim.subscriptionId}
                slot=${onlySim.simSlotIndex}
                carrier=${onlySim.carrierName}
                =================================
                """.trimIndent()
            )

            return subscriptionInfoToResolvedSim(
                info = onlySim,
                source = "Only active subscription"
            )
        }

        // =====================================================
        // 4. SNAPSHOT INFORMATION
        // =====================================================

        if (
            SubscriptionManager.isValidSubscriptionId(
                snapshot.subscriptionId
            )
        ) {

            return ResolvedSim(
                subscriptionId =
                    snapshot.subscriptionId,

                simSlot =
                    snapshot.simSlot,

                carrierName =
                    snapshot.carrierName
                        .ifBlank {
                            "Unknown"
                        },

                displayName =
                    snapshot.displayName
                        .ifBlank {
                            "Unknown"
                        },

                source = "Snapshot"
            )
        }

        // =====================================================
        // 5. UNKNOWN
        // =====================================================

        Log.w(
            TAG,
            """
            âš ï¸âš ï¸âš ï¸ ACTUAL SIM COULD NOT BE IDENTIFIED
            =========================================

            phoneAccountId =
                ${callLog.phoneAccountId}

            phoneAccountComponent =
                ${callLog.phoneAccountComponent}

            activeSubscriptions =
                ${subscriptions.size}

            We will NOT select the default voice SIM.

            Carrier = Unknown
            =========================================
            """.trimIndent()
        )

        return ResolvedSim(
            source =
                "Unable to identify actual SIM"
        )
    }

    // =========================================================
    // FALLBACK SIM
    // =========================================================

    private fun resolveFallbackSim(
        context: Context,
        snapshot: CallSnapshot
    ): ResolvedSim {

        if (
            SubscriptionManager.isValidSubscriptionId(
                snapshot.subscriptionId
            )
        ) {

            val info =
                getSubscriptionInfo(
                    context = context,
                    subscriptionId =
                        snapshot.subscriptionId
                )

            if (info != null) {

                return subscriptionInfoToResolvedSim(
                    info = info,
                    source = "Snapshot fallback"
                )
            }
        }

        val subscriptions =
            getActiveSubscriptions(
                context
            )

        /*
         * Safe only when exactly one subscription exists.
         */
        if (
            subscriptions.size == 1
        ) {

            return subscriptionInfoToResolvedSim(
                info = subscriptions.first(),
                source = "Only active subscription"
            )
        }

        return ResolvedSim(
            source =
                "No reliable SIM information"
        )
    }

    // =========================================================
    // SUBSCRIPTION INFO -> RESOLVED SIM
    // =========================================================

    private fun subscriptionInfoToResolvedSim(
        info: SubscriptionInfo,
        source: String
    ): ResolvedSim {

        val carrier =
            info.carrierName
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Unknown"

        val displayName =
            info.displayName
                ?.toString()
                ?.trim()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: carrier

        val countryIso =
            info.countryIso
                ?.trim()
                ?.uppercase()
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "Unknown"

        Log.d(
            TAG,
            """
            ðŸ“¡ RESOLVED SIM
            source=$source
            subscriptionId=${info.subscriptionId}
            simSlot=${info.simSlotIndex}
            carrier=$carrier
            displayName=$displayName
            countryIso=$countryIso
            mcc=${info.mcc}
            mnc=${info.mnc}
            """.trimIndent()
        )

        return ResolvedSim(
            subscriptionId = info.subscriptionId,
            simSlot = info.simSlotIndex,
            carrierName = carrier,
            displayName = displayName,
            countryIso = countryIso,
            source = source
        )
    }

    // =========================================================
    // GET SUBSCRIPTION INFO
    // =========================================================

    private fun getSubscriptionInfo(
        context: Context,
        subscriptionId: Int
    ): SubscriptionInfo? {

        if (
            !SubscriptionManager.isValidSubscriptionId(
                subscriptionId
            )
        ) {
            return null
        }

        return try {

            val manager =
                context.getSystemService(
                    SubscriptionManager::class.java
                )

            manager?.getActiveSubscriptionInfo(
                subscriptionId
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ Cannot read SubscriptionInfo",
                e
            )

            null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ SubscriptionInfo error",
                e
            )

            null
        }
    }

    // =========================================================
    // GET ACTIVE SUBSCRIPTIONS
    // =========================================================

    private fun getActiveSubscriptions(
        context: Context
    ): List<SubscriptionInfo> {

        return try {

            val manager =
                context.getSystemService(
                    SubscriptionManager::class.java
                )
                    ?: return emptyList()

            val list =
                manager.activeSubscriptionInfoList
                    ?: emptyList()

            Log.d(
                TAG,
                "ðŸ“± ACTIVE SUBSCRIPTIONS = ${list.size}"
            )

            list.forEach { info ->

                Log.d(
                    TAG,
                    """
                    ðŸ“± ACTIVE SIM
                    --------------------------------
                    subscriptionId = ${info.subscriptionId}
                    slot           = ${info.simSlotIndex}
                    carrier        = ${info.carrierName}
                    display        = ${info.displayName}
                    country        = ${info.countryIso}
                    --------------------------------
                    """.trimIndent()
                )
            }

            list

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ Cannot read active subscriptions",
                e
            )

            emptyList()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ Active subscription lookup failed",
                e
            )

            emptyList()
        }
    }

    // =========================================================
    // READ MATCHING CALL LOG
    // =========================================================

    private fun readLatestMatchingCall(
        context: Context,
        snapshot: CallSnapshot
    ): CallLogResult {

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CALL_LOG
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                TAG,
                "âŒ READ_CALL_LOG permission NOT granted"
            )

            return CallLogResult()
        }

        return try {

            val projection =
                arrayOf(
                    CallLog.Calls._ID,
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.DATE,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DURATION,
                    CallLog.Calls.PHONE_ACCOUNT_ID,
                    CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                )

            val lowerTime =
                if (
                    snapshot.callStartTime > 0L
                ) {

                    snapshot.callStartTime -
                            CALL_LOG_LOOKBACK_MS

                } else {

                    System.currentTimeMillis() -
                            60_000L
                }

            val upperTime =
                System.currentTimeMillis() +
                        CALL_LOG_LOOKAHEAD_MS

            val selection =
                "${CallLog.Calls.DATE} >= ? AND " +
                        "${CallLog.Calls.DATE} <= ?"

            val selectionArgs =
                arrayOf(
                    lowerTime.toString(),
                    upperTime.toString()
                )

            val cursor =
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC"
                )

            cursor?.use {

                if (!it.moveToFirst()) {

                    Log.w(
                        TAG,
                        """
                        âš ï¸ No recent CallLog rows found
                        lowerTime=$lowerTime
                        upperTime=$upperTime
                        """.trimIndent()
                    )

                    return CallLogResult()
                }

                val idIndex =
                    it.getColumnIndex(
                        CallLog.Calls._ID
                    )

                val numberIndex =
                    it.getColumnIndex(
                        CallLog.Calls.NUMBER
                    )

                val dateIndex =
                    it.getColumnIndex(
                        CallLog.Calls.DATE
                    )

                val typeIndex =
                    it.getColumnIndex(
                        CallLog.Calls.TYPE
                    )

                val durationIndex =
                    it.getColumnIndex(
                        CallLog.Calls.DURATION
                    )

                val accountIdIndex =
                    it.getColumnIndex(
                        CallLog.Calls.PHONE_ACCOUNT_ID
                    )

                val componentIndex =
                    it.getColumnIndex(
                        CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
                    )

                val expectedType =
                    if (snapshot.outgoing) {

                        CallLog.Calls.OUTGOING_TYPE

                    } else {

                        CallLog.Calls.INCOMING_TYPE
                    }

                val candidates =
                    mutableListOf<CallLogCandidate>()

                var inspectedRows = 0

                do {

                    if (
                        inspectedRows >=
                        MAX_CALL_LOG_ROWS
                    ) {
                        break
                    }

                    inspectedRows++

                    val rowId =
                        if (idIndex >= 0) {
                            it.getLong(idIndex)
                        } else {
                            -1L
                        }

                    val number =
                        if (numberIndex >= 0) {
                            it.getString(numberIndex)
                                ?: ""
                        } else {
                            ""
                        }

                    val date =
                        if (dateIndex >= 0) {
                            it.getLong(dateIndex)
                        } else {
                            0L
                        }

                    val type =
                        if (typeIndex >= 0) {
                            it.getInt(typeIndex)
                        } else {
                            -1
                        }

                    val duration =
                        if (durationIndex >= 0) {
                            it.getLong(durationIndex)
                        } else {
                            0L
                        }

                    val phoneAccountId =
                        if (accountIdIndex >= 0) {
                            it.getString(accountIdIndex)
                                ?: ""
                        } else {
                            ""
                        }

                    val phoneAccountComponent =
                        if (componentIndex >= 0) {
                            it.getString(componentIndex)
                                ?: ""
                        } else {
                            ""
                        }

                    Log.d(
                        TAG,
                        """
                        â˜Žï¸ CALLLOG ROW #$inspectedRows
                        ---------------------------------
                        rowId           = $rowId
                        number          = $number
                        date            = $date
                        type            = $type
                        duration        = $duration
                        phoneAccountId  = $phoneAccountId
                        component       = $phoneAccountComponent
                        ---------------------------------
                        """.trimIndent()
                    )

                    // =========================================
                    // NUMBER MATCH
                    // =========================================

                    val snapshotHasNumber =
                        snapshot.number.isNotBlank() &&
                                !snapshot.number.equals(
                                    "UNKNOWN",
                                    true
                                )

                    val logHasNumber =
                        number.isNotBlank()

                    val numberMatches =
                        if (!snapshotHasNumber) {

                            /*
                             * Outgoing PHONE_STATE frequently
                             * does not contain the number.
                             *
                             * In this case we do NOT reject the
                             * row merely because snapshot.number
                             * is UNKNOWN.
                             *
                             * Type + time + duration scoring below
                             * determines the correct candidate.
                             */
                            true

                        } else if (!logHasNumber) {

                            false

                        } else {

                            try {

                                PhoneNumberUtils.compare(
                                    snapshot.number,
                                    number
                                )

                            } catch (e: Exception) {

                                snapshot.number.trim() ==
                                        number.trim()
                            }
                        }

                    if (!numberMatches) {

                        Log.d(
                            TAG,
                            "â†ªï¸ Row rejected: number mismatch"
                        )

                        continue
                    }

                    // =========================================
                    // TIME MATCH
                    // =========================================

                    val timeMatches =
                        date >= lowerTime &&
                                date <= upperTime

                    if (!timeMatches) {

                        Log.d(
                            TAG,
                            "â†ªï¸ Row rejected: time mismatch"
                        )

                        continue
                    }

                    // =========================================
                    // SCORE CANDIDATE
                    // =========================================

                    var score = 0

                    /*
                     * Exact/normalized number match is strongest.
                     */
                    if (
                        snapshotHasNumber &&
                        logHasNumber
                    ) {

                        score += 100
                    } else {

                        /*
                         * Number was not available from
                         * PHONE_STATE.
                         */
                        score += 20
                    }

                    /*
                     * Correct direction/type.
                     */
                    if (
                        type ==
                        expectedType
                    ) {

                        score += 100

                    } else {

                        /*
                         * A non-matching type is not immediately
                         * impossible, but it receives a severe
                         * penalty.
                         */
                        score -= 100
                    }

                    /*
                     * Prefer rows closest to the call start.
                     */
                    val timeDifference =
                        if (
                            snapshot.callStartTime > 0L
                        ) {

                            kotlin.math.abs(
                                date -
                                        snapshot.callStartTime
                            )

                        } else {
                            0L
                        }

                    when {

                        timeDifference <= 2_000L ->
                            score += 50

                        timeDifference <= 5_000L ->
                            score += 35

                        timeDifference <= 15_000L ->
                            score += 20

                        timeDifference <= 30_000L ->
                            score += 10
                    }

                    /*
                     * A completed answered call normally has
                     * duration > 0.
                     */
                    if (
                        duration > 0L
                    ) {

                        score += 10
                    }

                    candidates +=
                        CallLogCandidate(
                            number = number,
                            date = date,
                            type = type,
                            durationSeconds = duration,
                            phoneAccountId =
                                phoneAccountId,
                            phoneAccountComponent =
                                phoneAccountComponent,
                            rowId = rowId,
                            score = score
                        )

                    Log.d(
                        TAG,
                        """
                        ðŸ§® CALLLOG CANDIDATE
                        --------------------------------
                        row=$inspectedRows
                        rowId=$rowId
                        score=$score
                        expectedType=$expectedType
                        actualType=$type
                        timeDifference=${timeDifference}ms
                        number=$number
                        --------------------------------
                        """.trimIndent()
                    )

                } while (it.moveToNext())

                if (candidates.isEmpty()) {

                    Log.w(
                        TAG,
                        """
                        âš ï¸ No CallLog candidates
                        inspectedRows=$inspectedRows
                        snapshotNumber=${snapshot.number}
                        snapshotStart=${snapshot.callStartTime}
                        =================================
                        """.trimIndent()
                    )

                    return CallLogResult()
                }

                /*
                 * Highest score wins.
                 *
                 * If scores tie, prefer the newest CallLog row.
                 */
                val best =
                    candidates.maxWithOrNull(
                        compareBy<CallLogCandidate> {
                            it.score
                        }.thenBy {
                            it.date
                        }
                    )

                if (best == null) {
                    return CallLogResult()
                }

                /*
                 * Never accept a candidate whose direction is
                 * contradictory to the call snapshot.
                 */
                if (
                    best.type != expectedType
                ) {

                    Log.w(
                        TAG,
                        """
                        âš ï¸ BEST CALLLOG CANDIDATE HAS WRONG TYPE
                        =================================
                        expectedType=$expectedType
                        actualType=${best.type}
                        rowId=${best.rowId}
                        score=${best.score}

                        We will NOT accept it.
                        =================================
                        """.trimIndent()
                    )

                    return CallLogResult()
                }

                /*
                 * If we had a number from PHONE_STATE, require
                 * the candidate to have matched it.
                 */
                if (
                    snapshotHasValidNumber(
                        snapshot.number
                    )
                ) {

                    val numbersMatch =
                        if (
                            best.number.isBlank()
                        ) {

                            false

                        } else {

                            try {

                                PhoneNumberUtils.compare(
                                    snapshot.number,
                                    best.number
                                )

                            } catch (
                                _: Exception
                            ) {

                                snapshot.number.trim() ==
                                        best.number.trim()
                            }
                        }

                    if (!numbersMatch) {

                        Log.w(
                            TAG,
                            """
                            âš ï¸ BEST CANDIDATE NUMBER DOES NOT MATCH SNAPSHOT
                            =================================
                            snapshot=${snapshot.number}
                            candidate=${best.number}
                            =================================
                            """.trimIndent()
                        )

                        return CallLogResult()
                    }
                }

                Log.e(
                    TAG,
                    """
                    âœ… BEST MATCHING CALLLOG ROW
                    =================================
                    rowId           = ${best.rowId}
                    score           = ${best.score}
                    number          = ${best.number}
                    date            = ${best.date}
                    type            = ${best.type}
                    duration        = ${best.durationSeconds}
                    phoneAccountId  = ${best.phoneAccountId}
                    component       = ${best.phoneAccountComponent}
                    =================================
                    """.trimIndent()
                )

                return CallLogResult(
                    found = true,
                    number = best.number,
                    date = best.date,
                    type = best.type,
                    durationSeconds =
                        best.durationSeconds,
                    phoneAccountId =
                        best.phoneAccountId,
                    phoneAccountComponent =
                        best.phoneAccountComponent,
                    rowId =
                        best.rowId
                )
            }

            CallLogResult()

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ SecurityException reading CallLog",
                e
            )

            CallLogResult()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ CallLog query failed",
                e
            )

            CallLogResult()
        }
    }

    // =========================================================
    // SNAPSHOT NUMBER VALIDATION
    // =========================================================

    private fun snapshotHasValidNumber(
        number: String
    ): Boolean {

        return number.isNotBlank() &&
                !number.equals(
                    "UNKNOWN",
                    true
                ) &&
                !number.equals(
                    "PRIVATE",
                    true
                )
    }

    // =========================================================
    // FIND SUBSCRIPTION FOR PHONE ACCOUNT
    // =========================================================

    // =========================================================
// FIND SUBSCRIPTION FOR PHONE ACCOUNT
// =========================================================
    private fun findSubscriptionForPhoneAccount(
        context: Context,
        phoneAccountId: String?,
        phoneAccountComponent: String?
    ): SubscriptionInfo? {

        if (phoneAccountId.isNullOrBlank()) {
            Log.w(TAG, "âŒ PHONE_ACCOUNT_ID is blank")
            return null
        }

        Log.d(
            TAG,
            "ðŸ” Resolving PHONE_ACCOUNT_ID=$phoneAccountId " +
                    "component=$phoneAccountComponent"
        )

        val subscriptions = getActiveSubscriptions(context)

        /*
         * ============================================================
         * 1. BUILD EXACT PHONE ACCOUNT HANDLE FROM CALL LOG
         * ============================================================
         */
        if (!phoneAccountComponent.isNullOrBlank()) {

            try {

                val componentName =
                    ComponentName.unflattenFromString(phoneAccountComponent)

                if (componentName != null) {

                    val handle = PhoneAccountHandle(
                        componentName,
                        phoneAccountId
                    )

                    Log.d(TAG, "ðŸ“ž PHONE ACCOUNT HANDLE CREATED")
                    Log.d(TAG, "   package=${componentName.packageName}")
                    Log.d(TAG, "   class=${componentName.className}")
                    Log.d(TAG, "   id=${handle.id}")

                    /*
                     * ========================================================
                     * 2. ASK TELECOM FOR THE PHONE ACCOUNT
                     * ========================================================
                     */
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {

                        try {

                            val telecomManager =
                                context.getSystemService(
                                    TelecomManager::class.java
                                )

                            val phoneAccount =
                                telecomManager?.getPhoneAccount(handle)

                            if (phoneAccount != null) {

                                Log.d(
                                    TAG,
                                    "âœ… PHONE ACCOUNT FOUND"
                                )

                                Log.d(
                                    TAG,
                                    "   capabilities=" +
                                            phoneAccount.capabilities
                                )

                                Log.d(
                                    TAG,
                                    "   isSimSubscription=" +
                                            phoneAccount.hasCapabilities(
                                                PhoneAccount.CAPABILITY_SIM_SUBSCRIPTION
                                            )
                                )

                                Log.d(
                                    TAG,
                                    "   address=" +
                                            phoneAccount.address
                                )

                                Log.d(
                                    TAG,
                                    "   subscriptionAddress=" +
                                            phoneAccount.subscriptionAddress
                                )

                                Log.d(
                                    TAG,
                                    "   label=" +
                                            phoneAccount.label
                                )

                                Log.d(
                                    TAG,
                                    "   shortDescription=" +
                                            phoneAccount.shortDescription
                                )

                                /*
                                 * =================================================
                                 * 3. API 30+
                                 * PHONE ACCOUNT â†’ SUBSCRIPTION ID
                                 * =================================================
                                 *
                                 * This is the strongest possible resolution.
                                 */
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {

                                    try {

                                        val telephonyManager =
                                            context.getSystemService(
                                                TelephonyManager::class.java
                                            )

                                        val resolvedSubscriptionId =
                                            telephonyManager?.getSubscriptionId(handle)
                                                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

                                        Log.d(
                                            TAG,
                                            "ðŸ“¡ TELEPHONY RESOLVED SUBSCRIPTION = " +
                                                    resolvedSubscriptionId
                                        )

                                        if (
                                            resolvedSubscriptionId !=
                                            SubscriptionManager.INVALID_SUBSCRIPTION_ID
                                        ) {

                                            val info =
                                                subscriptions.firstOrNull {
                                                    it.subscriptionId ==
                                                            resolvedSubscriptionId
                                                }

                                            if (info != null) {

                                                Log.d(
                                                    TAG,
                                                    "ðŸŽ¯ðŸŽ¯ðŸŽ¯ EXACT SIM RESOLVED"
                                                )

                                                Log.d(
                                                    TAG,
                                                    "   subscriptionId=" +
                                                            info.subscriptionId
                                                )

                                                Log.d(
                                                    TAG,
                                                    "   slot=" +
                                                            info.simSlotIndex
                                                )

                                                Log.d(
                                                    TAG,
                                                    "   carrier=" +
                                                            info.carrierName
                                                )

                                                Log.d(
                                                    TAG,
                                                    "   display=" +
                                                            info.displayName
                                                )

                                                Log.d(
                                                    TAG,
                                                    "   country=" +
                                                            info.countryIso
                                                )

                                                return info
                                            }

                                            Log.w(
                                                TAG,
                                                "âš ï¸ Resolved subscription ID " +
                                                        "$resolvedSubscriptionId " +
                                                        "is not in active subscriptions"
                                            )

                                        } else {

                                            Log.w(
                                                TAG,
                                                "âš ï¸ Telephony returned INVALID subscription ID"
                                            )
                                        }

                                    } catch (e: SecurityException) {

                                        Log.e(
                                            TAG,
                                            "âŒ READ_PHONE_STATE blocked while resolving subscription",
                                            e
                                        )

                                    } catch (e: Exception) {

                                        Log.e(
                                            TAG,
                                            "âŒ Telephony subscription resolution failed",
                                            e
                                        )
                                    }
                                }

                                /*
                                 * =================================================
                                 * 4. PHONE ACCOUNT METADATA FALLBACK
                                 * =================================================
                                 *
                                 * Some Samsung/Android builds expose the real
                                 * carrier through PhoneAccount.label and
                                 * PhoneAccount.shortDescription, while
                                 * getSubscriptionId(handle) returns INVALID.
                                 *
                                 * We only accept this fallback when exactly ONE
                                 * active SubscriptionInfo matches.
                                 */
                                try {

                                    val accountLabel =
                                        phoneAccount.label
                                            ?.toString()
                                            ?.trim()
                                            .orEmpty()

                                    val accountDescription =
                                        phoneAccount.shortDescription
                                            ?.toString()
                                            ?.trim()
                                            .orEmpty()

                                    val accountMetadata =
                                        "$accountLabel $accountDescription"
                                            .trim()

                                    Log.d(
                                        TAG,
                                        "ðŸ”Ž PHONE ACCOUNT METADATA MATCH"
                                    )

                                    Log.d(
                                        TAG,
                                        "   label=$accountLabel"
                                    )

                                    Log.d(
                                        TAG,
                                        "   description=$accountDescription"
                                    )

                                    Log.d(
                                        TAG,
                                        "   metadata=$accountMetadata"
                                    )

                                    /*
                                     * ------------------------------------------------
                                     * Match against carrier name/display name.
                                     * ------------------------------------------------
                                     */
                                    val carrierMatches =
                                        subscriptions.filter { info ->

                                            val carrierName =
                                                info.carrierName
                                                    ?.toString()
                                                    ?.trim()
                                                    .orEmpty()

                                            val displayName =
                                                info.displayName
                                                    ?.toString()
                                                    ?.trim()
                                                    .orEmpty()

                                            if (accountMetadata.isBlank()) {
                                                false
                                            } else {

                                                val carrierMatch =
                                                    carrierName.isNotBlank() &&
                                                            (
                                                                    accountMetadata.equals(
                                                                        carrierName,
                                                                        ignoreCase = true
                                                                    ) ||
                                                                            accountMetadata.contains(
                                                                                carrierName,
                                                                                ignoreCase = true
                                                                            ) ||
                                                                            carrierName.contains(
                                                                                accountMetadata,
                                                                                ignoreCase = true
                                                                            )
                                                                    )

                                                val displayMatch =
                                                    displayName.isNotBlank() &&
                                                            (
                                                                    accountMetadata.equals(
                                                                        displayName,
                                                                        ignoreCase = true
                                                                    ) ||
                                                                            accountMetadata.contains(
                                                                                displayName,
                                                                                ignoreCase = true
                                                                            ) ||
                                                                            displayName.contains(
                                                                                accountMetadata,
                                                                                ignoreCase = true
                                                                            )
                                                                    )

                                                carrierMatch || displayMatch
                                            }
                                        }

                                    Log.d(
                                        TAG,
                                        "   metadata subscription matches=" +
                                                carrierMatches.size
                                    )

                                    carrierMatches.forEach { info ->

                                        Log.d(
                                            TAG,
                                            "   ðŸ“± METADATA CANDIDATE"
                                        )

                                        Log.d(
                                            TAG,
                                            "      subscriptionId=" +
                                                    info.subscriptionId
                                        )

                                        Log.d(
                                            TAG,
                                            "      slot=" +
                                                    info.simSlotIndex
                                        )

                                        Log.d(
                                            TAG,
                                            "      carrier=" +
                                                    info.carrierName
                                        )

                                        Log.d(
                                            TAG,
                                            "      display=" +
                                                    info.displayName
                                        )

                                        Log.d(
                                            TAG,
                                            "      country=" +
                                                    info.countryIso
                                        )
                                    }

                                    /*
                                     * ------------------------------------------------
                                     * EXACTLY ONE MATCH = SAFE RESOLUTION
                                     * ------------------------------------------------
                                     */
                                    if (carrierMatches.size == 1) {

                                        val info =
                                            carrierMatches.first()

                                        Log.d(
                                            TAG,
                                            "ðŸŽ¯ðŸŽ¯ðŸŽ¯ PHONE ACCOUNT METADATA " +
                                                    "RESOLVED SIM"
                                        )

                                        Log.d(
                                            TAG,
                                            "   subscriptionId=" +
                                                    info.subscriptionId
                                        )

                                        Log.d(
                                            TAG,
                                            "   slot=" +
                                                    info.simSlotIndex
                                        )

                                        Log.d(
                                            TAG,
                                            "   carrier=" +
                                                    info.carrierName
                                        )

                                        Log.d(
                                            TAG,
                                            "   display=" +
                                                    info.displayName
                                        )

                                        Log.d(
                                            TAG,
                                            "   country=" +
                                                    info.countryIso
                                        )

                                        return info
                                    }

                                    if (carrierMatches.size > 1) {

                                        Log.w(
                                            TAG,
                                            "âš ï¸ PhoneAccount metadata matched " +
                                                    "multiple active SIMs"
                                        )

                                    } else {

                                        Log.w(
                                            TAG,
                                            "âš ï¸ PhoneAccount metadata matched " +
                                                    "no active SIM"
                                        )
                                    }

                                } catch (e: Exception) {

                                    Log.e(
                                        TAG,
                                        "âŒ PhoneAccount metadata matching failed",
                                        e
                                    )
                                }

                            } else {

                                Log.w(
                                    TAG,
                                    "âš ï¸ TelecomManager returned NULL PhoneAccount"
                                )
                            }

                        } catch (e: SecurityException) {

                            Log.e(
                                TAG,
                                "âŒ Cannot access Telecom PhoneAccount",
                                e
                            )

                        } catch (e: Exception) {

                            Log.e(
                                TAG,
                                "âŒ PhoneAccount lookup failed",
                                e
                            )
                        }
                    }

                } else {

                    Log.w(
                        TAG,
                        "âš ï¸ Could not parse PhoneAccount component: " +
                                phoneAccountComponent
                    )
                }

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "âŒ Could not construct PhoneAccountHandle",
                    e
                )
            }
        }

        /*
         * ============================================================
         * 5. LEGACY DIRECT SUBSCRIPTION ID MATCH
         * ============================================================
         *
         * Some devices put the actual subscription ID directly in
         * PHONE_ACCOUNT_ID.
         */
        phoneAccountId.toIntOrNull()?.let { numericId ->

            val match =
                subscriptions.firstOrNull {
                    it.subscriptionId == numericId
                }

            if (match != null) {

                Log.d(
                    TAG,
                    "âœ… DIRECT SUBSCRIPTION ID MATCH"
                )

                Log.d(
                    TAG,
                    "   subscriptionId=${match.subscriptionId}"
                )

                Log.d(
                    TAG,
                    "   slot=${match.simSlotIndex}"
                )

                Log.d(
                    TAG,
                    "   carrier=${match.carrierName}"
                )

                Log.d(
                    TAG,
                    "   display=${match.displayName}"
                )

                return match
            }
        }

        /*
         * ============================================================
         * 6. EMBEDDED SUBSCRIPTION ID FALLBACK
         * ============================================================
         *
         * Only accept the embedded ID if exactly one active
         * subscription matches.
         */
        val embeddedMatches =
            subscriptions.filter { info ->

                phoneAccountId.contains(
                    info.subscriptionId.toString()
                )
            }

        if (embeddedMatches.size == 1) {

            val match =
                embeddedMatches.first()

            Log.d(
                TAG,
                "âœ… EMBEDDED SUBSCRIPTION ID MATCH"
            )

            Log.d(
                TAG,
                "   subscriptionId=${match.subscriptionId}"
            )

            Log.d(
                TAG,
                "   slot=${match.simSlotIndex}"
            )

            Log.d(
                TAG,
                "   carrier=${match.carrierName}"
            )

            Log.d(
                TAG,
                "   display=${match.displayName}"
            )

            return match
        }

        if (embeddedMatches.size > 1) {

            Log.w(
                TAG,
                "âš ï¸ PHONE_ACCOUNT_ID matched multiple " +
                        "subscription IDs"
            )
        }

        /*
         * ============================================================
         * 7. DO NOT GUESS DEFAULT VOICE SIM
         * ============================================================
         */
        Log.w(
            TAG,
            """
        âŒ ACTUAL SIM COULD NOT BE IDENTIFIED

        phoneAccountId=$phoneAccountId
        phoneAccountComponent=$phoneAccountComponent
        activeSubscriptions=${subscriptions.size}

        No SIM will be guessed.
        """.trimIndent()
        )

        return null
    }

    // =========================================================
    // FINISH CALL
    // =========================================================

    private fun finishCall(
        context: Context,
        childId: String,
        snapshot: CallSnapshot,
        callLog: CallLogResult?,
        resolvedSim: ResolvedSim,
        finishBroadcast: () -> Unit
    ) {

        try {

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

            // =================================================
            // NUMBER
            // =================================================

            val rawNumber =
                when {

                    snapshotHasValidNumber(
                        snapshot.number
                    ) ->

                        snapshot.number

                    !callLog?.number.isNullOrBlank() ->

                        callLog?.number
                            ?: "UNKNOWN"

                    else ->

                        "UNKNOWN"
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
                        rawNumber.trim()
                }

            // =================================================
            // DURATION
            // =================================================

            val finalDuration =
                if (
                    callLog != null &&
                    callLog.durationSeconds > 0L
                ) {

                    callLog.durationSeconds * 1000L

                } else {

                    if (
                        snapshot.callStartTime > 0L
                    ) {

                        (
                                System.currentTimeMillis() -
                                        snapshot.callStartTime
                                )
                            .coerceAtLeast(0L)

                    } else {

                        0L
                    }
                }

            // =================================================
            // DIRECTION
            // =================================================

            val direction =
                when (
                    callLog?.type
                ) {

                    CallLog.Calls.OUTGOING_TYPE ->
                        "OUTGOING"

                    CallLog.Calls.INCOMING_TYPE ->
                        "INCOMING"

                    CallLog.Calls.MISSED_TYPE ->
                        "MISSED"

                    CallLog.Calls.REJECTED_TYPE ->
                        "MISSED"

                    else ->
                        if (
                            snapshot.outgoing
                        ) {
                            "OUTGOING"
                        } else {
                            "INCOMING"
                        }
                }

            // =================================================
            // CONTACT
            // =================================================

            val contact =
                lookupContact(
                    context = context,
                    phoneNumber = cleanNumber
                )

            // =================================================
            // FROM / TO
            // =================================================

            val from =
                if (
                    direction == "OUTGOING"
                ) {

                    childName

                } else {

                    cleanNumber
                }

            val to =
                if (
                    direction == "OUTGOING"
                ) {

                    cleanNumber

                } else {

                    childName
                }

            // =================================================
            // VALIDATION
            // =================================================

            if (
                cleanNumber == "UNKNOWN"
            ) {

                Log.w(
                    TAG,
                    "âš ï¸ Ignoring call because number is UNKNOWN"
                )

                return
            }

            if (
                cleanNumber == "PRIVATE"
            ) {

                Log.w(
                    TAG,
                    "âš ï¸ Ignoring private/blocked number"
                )

                return
            }

            if (
                direction != "MISSED" &&
                finalDuration <
                MIN_VALID_CALL_DURATION_MS
            ) {

                Log.d(
                    TAG,
                    """
                    âš ï¸ Ignoring extremely short call
                    durationMs=$finalDuration
                    minimum=$MIN_VALID_CALL_DURATION_MS
                    """.trimIndent()
                )

                return
            }

            // =================================================
            // FINAL SIM
            // =================================================

            /*
             * IMPORTANT:
             *
             * These values come exclusively from resolvedSim.
             *
             * We do NOT read the mutable current* variables here.
             */
            val finalSubscriptionId =
                resolvedSim.subscriptionId

            val finalSimSlot =
                resolvedSim.simSlot

            val finalCarrier =
                resolvedSim.carrierName
                    .trim()
                    .ifBlank {
                        "Unknown"
                    }

            val finalSimCarrier =
                finalCarrier

            val finalDisplayName =
                resolvedSim.displayName
                    .trim()
                    .ifBlank {
                        finalCarrier
                    }

            val finalCountry =
                resolvedSim.countryIso
                    .trim()
                    .ifBlank {
                        "Unknown"
                    }

            // =================================================
            // TIMESTAMP
            // =================================================

            val finalTimestamp =
                callLog?.date
                    ?.takeIf {
                        it > 0L
                    }
                    ?: snapshot.callStartTime

            // =================================================
            // FINAL LOG
            // =================================================

            Log.e(
                TAG,
                """
                ðŸ“žðŸ“žðŸ“ž FINAL CALL RECORD ðŸ“žðŸ“žðŸ“ž
                =========================================
                number          = $cleanNumber
                direction       = $direction
                durationMs      = $finalDuration
                timestamp       = $finalTimestamp
                -----------------------------------------
                callLogRowId    = ${
                    callLog?.rowId
                        ?: -1
                }
                phoneAccountId  = ${
                    callLog?.phoneAccountId
                        ?: "Unknown"
                }
                phoneComponent  = ${
                    callLog?.phoneAccountComponent
                        ?: "Unknown"
                }
                -----------------------------------------
                SIM SOURCE      = ${resolvedSim.source}
                subscriptionId  = $finalSubscriptionId
                simSlot         = $finalSimSlot
                carrier         = $finalCarrier
                displayName     = $finalDisplayName
                country         = $finalCountry
                -----------------------------------------
                contactName     = ${contact.name}
                contactSaved    = ${contact.saved}
                =========================================
                """.trimIndent()
            )

            // =================================================
            // SEND TO SERVICE
            // =================================================

            sendToService(
                context = context,
                childId = childId,
                direction = direction,
                number = cleanNumber,
                from = from,
                to = to,
                timestamp = finalTimestamp,
                duration = finalDuration,
                subscriptionId = finalSubscriptionId,
                simSlot = finalSimSlot,
                carrierName = finalCarrier,
                displayName = finalDisplayName,
                countryIso = finalCountry,
                contactName = contact.name,
                contactSaved = contact.saved
            )

        } finally {

            /*
             * Always release the pending BroadcastReceiver.
             */
            finishBroadcast()
        }
    }

    // =========================================================
    // CONTACT LOOKUP
    // =========================================================

    private fun lookupContact(
        context: Context,
        phoneNumber: String
    ): ContactLookupResult {

        if (
            phoneNumber.isBlank() ||
            phoneNumber.equals(
                "UNKNOWN",
                true
            ) ||
            phoneNumber.equals(
                "PRIVATE",
                true
            )
        ) {

            return ContactLookupResult()
        }

        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.w(
                TAG,
                "âš ï¸ READ_CONTACTS permission not granted"
            )

            return ContactLookupResult()
        }

        return try {

            val directResult =
                lookupContactDirect(
                    context,
                    phoneNumber
                )

            if (
                directResult.saved &&
                directResult.name.isNotBlank()
            ) {

                return directResult
            }

            val fallbackResult =
                lookupContactByComparison(
                    context,
                    phoneNumber
                )

            if (
                fallbackResult.saved &&
                fallbackResult.name.isNotBlank()
            ) {

                return fallbackResult
            }

            Log.d(
                TAG,
                "ðŸ‘¤ NO SAVED CONTACT: $phoneNumber"
            )

            ContactLookupResult()

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ READ_CONTACTS security error",
                e
            )

            ContactLookupResult()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ Contact lookup failed",
                e
            )

            ContactLookupResult()
        }
    }

    // =========================================================
    // DIRECT CONTACT LOOKUP
    // =========================================================

    private fun lookupContactDirect(
        context: Context,
        phoneNumber: String
    ): ContactLookupResult {

        return try {

            val lookupUri =
                Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(phoneNumber)
                )

            val projection =
                arrayOf(
                    ContactsContract.PhoneLookup.DISPLAY_NAME
                )

            context.contentResolver.query(
                lookupUri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->

                if (
                    cursor.moveToFirst()
                ) {

                    val nameIndex =
                        cursor.getColumnIndex(
                            ContactsContract.PhoneLookup.DISPLAY_NAME
                        )

                    val contactName =
                        if (
                            nameIndex >= 0
                        ) {

                            cursor.getString(
                                nameIndex
                            )
                                ?.trim()
                                ?: ""

                        } else {
                            ""
                        }

                    if (
                        contactName.isNotBlank()
                    ) {

                        return ContactLookupResult(
                            name = contactName,
                            saved = true
                        )
                    }
                }
            }

            ContactLookupResult()

        } catch (e: Exception) {

            Log.w(
                TAG,
                "âš ï¸ Direct contact lookup failed: ${e.message}"
            )

            ContactLookupResult()
        }
    }

    // =========================================================
    // CONTACT COMPARISON
    // =========================================================

    private fun lookupContactByComparison(
        context: Context,
        phoneNumber: String
    ): ContactLookupResult {

        val target =
            phoneNumber.trim()

        val projection =
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
            )

        return try {

            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null
            )?.use { cursor ->

                val numberIndex =
                    cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.NUMBER
                    )

                val nameIndex =
                    cursor.getColumnIndex(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                    )

                if (
                    numberIndex < 0 ||
                    nameIndex < 0
                ) {

                    return ContactLookupResult()
                }

                while (
                    cursor.moveToNext()
                ) {

                    val savedNumber =
                        cursor.getString(
                            numberIndex
                        )
                            ?.trim()
                            ?: ""

                    val contactName =
                        cursor.getString(
                            nameIndex
                        )
                            ?.trim()
                            ?: ""

                    if (
                        savedNumber.isBlank() ||
                        contactName.isBlank()
                    ) {
                        continue
                    }

                    val androidMatch =
                        try {

                            PhoneNumberUtils.compare(
                                target,
                                savedNumber
                            )

                        } catch (
                            _: Exception
                        ) {

                            false
                        }

                    val digitMatch =
                        areNumberDigitsEquivalent(
                            digitsOnly(target),
                            digitsOnly(savedNumber)
                        )

                    if (
                        androidMatch ||
                        digitMatch
                    ) {

                        Log.d(
                            TAG,
                            """
                            ðŸ‘¤ CONTACT MATCH FOUND
                            --------------------------------
                            calledNumber=$target
                            savedNumber=$savedNumber
                            contactName=$contactName
                            --------------------------------
                            """.trimIndent()
                        )

                        return ContactLookupResult(
                            name = contactName,
                            saved = true
                        )
                    }
                }
            }

            ContactLookupResult()

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ Contact comparison permission error",
                e
            )

            ContactLookupResult()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ Contact comparison failed",
                e
            )

            ContactLookupResult()
        }
    }

    // =========================================================
    // DIGITS ONLY
    // =========================================================

    private fun digitsOnly(
        number: String
    ): String {

        return number.filter {
            it.isDigit()
        }
    }

    // =========================================================
    // NUMBER EQUIVALENCE
    // =========================================================

    private fun areNumberDigitsEquivalent(
        first: String,
        second: String
    ): Boolean {

        if (
            first.isBlank() ||
            second.isBlank()
        ) {
            return false
        }

        if (
            first == second
        ) {
            return true
        }

        /*
         * Compare a reasonably long suffix to support common
         * local/international formatting differences.
         */
        if (
            first.length >= 8 &&
            second.length >= 8
        ) {

            val suffixLength =
                minOf(
                    9,
                    first.length,
                    second.length
                )

            if (
                first.takeLast(
                    suffixLength
                ) ==
                second.takeLast(
                    suffixLength
                )
            ) {

                return true
            }
        }

        val firstWithoutLeadingZero =
            first.trimStart('0')

        val secondWithoutLeadingZero =
            second.trimStart('0')

        if (
            firstWithoutLeadingZero.isNotBlank() &&
            secondWithoutLeadingZero.isNotBlank() &&
            firstWithoutLeadingZero.length >= 8 &&
            secondWithoutLeadingZero.length >= 8
        ) {

            val suffixLength =
                minOf(
                    9,
                    firstWithoutLeadingZero.length,
                    secondWithoutLeadingZero.length
                )

            if (
                firstWithoutLeadingZero.takeLast(
                    suffixLength
                ) ==
                secondWithoutLeadingZero.takeLast(
                    suffixLength
                )
            ) {

                return true
            }
        }

        return false
    }

    // =========================================================
    // SEND TO CALL SERVICE
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
        subscriptionId: Int,
        simSlot: Int,
        carrierName: String,
        displayName: String,
        countryIso: String,
        contactName: String,
        contactSaved: Boolean
    ) {

        Log.e(
            TAG,
            """
            ðŸš€ðŸš€ðŸš€ PREPARING CALL SERVICE ðŸš€ðŸš€ðŸš€
            =====================================
            childId         = $childId
            direction       = $direction
            number          = $number
            from            = $from
            to              = $to
            timestamp       = $timestamp
            duration        = $duration
            subscriptionId  = $subscriptionId
            simSlot         = $simSlot
            carrierName     = $carrierName
            displayName     = $displayName
            countryIso      = $countryIso
            contactName     = $contactName
            contactSaved    = $contactSaved
            =====================================
            """.trimIndent()
        )

        try {

            val serviceIntent =
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

                    putExtra(
                        CallService.EXTRA_SUBSCRIPTION_ID,
                        subscriptionId
                    )

                    putExtra(
                        CallService.EXTRA_SIM_SLOT,
                        simSlot
                    )

                    putExtra(
                        CallService.EXTRA_CARRIER_NAME,
                        carrierName
                    )

                    putExtra(
                        CallService.EXTRA_SIM_CARRIER,
                        carrierName
                    )

                    putExtra(
                        CallService.EXTRA_SIM_DISPLAY_NAME,
                        displayName
                    )

                    putExtra(
                        CallService.EXTRA_SIM_COUNTRY,
                        countryIso
                    )

                    putExtra(
                        "contactName",
                        contactName
                    )

                    putExtra(
                        "contactSaved",
                        contactSaved
                    )
                }

            Log.e(
                TAG,
                """
                ðŸ“¦ SERVICE INTENT
                =====================================
                subscriptionId = ${
                    serviceIntent.getIntExtra(
                        CallService.EXTRA_SUBSCRIPTION_ID,
                        -999
                    )
                }
                simSlot = ${
                    serviceIntent.getIntExtra(
                        CallService.EXTRA_SIM_SLOT,
                        -1
                    )
                }
                carrierName = ${
                    serviceIntent.getStringExtra(
                        CallService.EXTRA_CARRIER_NAME
                    )
                }
                simCarrier = ${
                    serviceIntent.getStringExtra(
                        CallService.EXTRA_SIM_CARRIER
                    )
                }
                simDisplayName = ${
                    serviceIntent.getStringExtra(
                        CallService.EXTRA_SIM_DISPLAY_NAME
                    )
                }
                simCountry = ${
                    serviceIntent.getStringExtra(
                        CallService.EXTRA_SIM_COUNTRY
                    )
                }
                =====================================
                """.trimIndent()
            )

            ContextCompat.startForegroundService(
                context,
                serviceIntent
            )

            Log.e(
                TAG,
                "âœ… CallService started successfully"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "âŒ SECURITY EXCEPTION starting CallService",
                e
            )

        } catch (e: IllegalStateException) {

            Log.e(
                TAG,
                "âŒ ILLEGAL STATE starting CallService",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ UNKNOWN ERROR starting CallService",
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

        /*
         * Kept as a diagnostic fallback only.
         *
         * It is deliberately NOT used to select an arbitrary
         * call for the final record because doing so could attach
         * the wrong person's number to this call.
         */
        return try {

            if (
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_CALL_LOG
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                return "UNKNOWN"
            }

            val cursor =
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER
                    ),
                    null,
                    null,
                    "${CallLog.Calls.DATE} DESC"
                )

            cursor?.use {

                if (
                    it.moveToFirst()
                ) {

                    val index =
                        it.getColumnIndex(
                            CallLog.Calls.NUMBER
                        )

                    if (
                        index >= 0
                    ) {

                        return it.getString(
                            index
                        ) ?: "UNKNOWN"
                    }
                }
            }

            "UNKNOWN"

        } catch (e: Exception) {

            Log.e(
                TAG,
                "âŒ CallLog number error",
                e
            )

            "UNKNOWN"
        }
    }
}
