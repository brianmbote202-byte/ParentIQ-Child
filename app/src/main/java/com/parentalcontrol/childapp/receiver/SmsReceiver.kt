package com.parentalcontrol.childapp.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(
        context: Context,
        intent: Intent
    ) {

        // =========================================================
        // VERIFY SMS BROADCAST
        // =========================================================

        if (
            intent.action !=
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION
        ) {

            Log.d(
                TAG,
                "Ignoring intent: ${intent.action}"
            )

            return
        }


        // =========================================================
        // GET BROADCAST EXTRAS
        // =========================================================

        val bundle: Bundle =
            intent.extras ?: run {

                Log.e(
                    TAG,
                    "SMS broadcast has no extras"
                )

                return
            }


        // =========================================================
        // GET SUBSCRIPTION ID
        // =========================================================

        /*
         * This identifies which SIM/subscription received
         * the SMS.
         *
         * Example:
         *
         * SIM 1 -> Safaricom
         * SIM 2 -> Airtel
         *
         * Android gives us the subscription ID here.
         */

        val subscriptionId =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.LOLLIPOP_MR1
            ) {

                bundle.getInt(
                    SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )

            } else {

                SubscriptionManager.INVALID_SUBSCRIPTION_ID
            }


        Log.d(
            TAG,
            "SMS subscriptionId=$subscriptionId"
        )


        // =========================================================
        // GET PDUS
        // =========================================================

        val pdus =
            bundle.get("pdus") as? Array<*>

        if (
            pdus == null ||
            pdus.isEmpty()
        ) {

            Log.e(
                TAG,
                "No SMS PDUs found"
            )

            return
        }


        // =========================================================
        // SMS FORMAT
        // =========================================================

        val format =
            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.M
            ) {

                bundle.getString(
                    "format"
                )

            } else {

                null
            }


        // =========================================================
        // BUILD FULL SMS
        // =========================================================

        var fullMessage =
            ""

        var sender =
            "unknown_number"

        var smsTimestamp =
            System.currentTimeMillis()


        for (
        pdu in pdus
        ) {

            if (pdu !is ByteArray) {
                continue
            }


            try {

                val sms =
                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.M &&
                        format != null
                    ) {

                        SmsMessage.createFromPdu(
                            pdu,
                            format
                        )

                    } else {

                        @Suppress("DEPRECATION")
                        SmsMessage.createFromPdu(
                            pdu
                        )
                    }


                if (sms == null) {
                    continue
                }


                // =================================================
                // SENDER
                // =================================================

                sender =
                    sms.originatingAddress
                        ?: sender


                // =================================================
                // MESSAGE BODY
                // =================================================

                fullMessage +=
                    sms.messageBody ?: ""


                // =================================================
                // REAL SMS TIMESTAMP
                // =================================================

                if (
                    sms.timestampMillis > 0
                ) {

                    smsTimestamp =
                        sms.timestampMillis
                }


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed to decode SMS PDU",
                    e
                )
            }
        }


        // =========================================================
        // VALIDATE MESSAGE
        // =========================================================

        if (
            fullMessage.isBlank()
        ) {

            Log.e(
                TAG,
                "SMS body is empty"
            )

            return
        }


        Log.d(
            TAG,
            "FULL Incoming SMS → " +
                    "$sender : $fullMessage"
        )

        Log.d(
            TAG,
            "SMS timestamp=$smsTimestamp"
        )

        Log.d(
            TAG,
            "SMS subscriptionId=$subscriptionId"
        )


        // =========================================================
        // GET CHILD ID
        // =========================================================

        val prefs =
            context.getSharedPreferences(
                "child_prefs",
                Context.MODE_PRIVATE
            )

        val childId =
            prefs.getString(
                "child_id",
                ""
            ) ?: ""


        if (
            childId.isEmpty()
        ) {

            Log.e(
                TAG,
                "Child ID missing"
            )

            return
        }


        // =========================================================
        // CREATE MESSAGE SERVICE INTENT
        // =========================================================

        val serviceIntent =
            Intent(
                context,
                MessageService::class.java
            ).apply {

                // ---------------------------------------------
                // TYPE
                // ---------------------------------------------

                putExtra(
                    "type",
                    "SMS"
                )


                // ---------------------------------------------
                // CHILD
                // ---------------------------------------------

                putExtra(
                    "childId",
                    childId
                )


                // ---------------------------------------------
                // SENDER
                // ---------------------------------------------

                putExtra(
                    "fromRaw",
                    sender
                )

                putExtra(
                    "from",
                    sender
                )


                // ---------------------------------------------
                // DEVICE
                // ---------------------------------------------

                putExtra(
                    "toRaw",
                    "DEVICE"
                )

                putExtra(
                    "to",
                    ""
                )


                // ---------------------------------------------
                // MESSAGE
                // ---------------------------------------------

                putExtra(
                    "content",
                    fullMessage
                )


                // ---------------------------------------------
                // TIMESTAMP
                // ---------------------------------------------

                putExtra(
                    "timestamp",
                    smsTimestamp
                )


                // ---------------------------------------------
                // DIRECTION
                // ---------------------------------------------

                putExtra(
                    "direction",
                    "INCOMING"
                )


                // ---------------------------------------------
                // SIM / SUBSCRIPTION
                // ---------------------------------------------

                putExtra(
                    MessageService.EXTRA_SUBSCRIPTION_ID,
                    subscriptionId
                )
            }


        // =========================================================
        // START MESSAGE SERVICE
        // =========================================================

        try {

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.O
            ) {

                ContextCompat.startForegroundService(
                    context,
                    serviceIntent
                )

            } else {

                @Suppress("DEPRECATION")
                context.startService(
                    serviceIntent
                )
            }


            Log.d(
                TAG,
                "MessageService started successfully: " +
                        "subscriptionId=$subscriptionId"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Failed to start MessageService",
                e
            )
        }
    }
}