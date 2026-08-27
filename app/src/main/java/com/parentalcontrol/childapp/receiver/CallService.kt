package com.parentalcontrol.childapp.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.R
import com.parentalcontrol.childapp.model.CallRecord

class CallService : Service() {

    companion object {

        private const val TAG = "CallService"

        private const val CHANNEL_ID =
            "call_service_channel"

        private const val NOTIF_ID = 102

        /**
         * Subscription ID supplied by CallReceiver.
         */
        const val EXTRA_SUBSCRIPTION_ID =
            "subscriptionId"
    }

    private lateinit var database: FirebaseDatabase


    // ============================================================
    // CREATE
    // ============================================================

    override fun onCreate() {
        super.onCreate()

        database =
            FirebaseDatabase.getInstance()

        Log.d(
            TAG,
            "🚀 CallService created"
        )

        startForegroundSafely()
    }


    // ============================================================
    // START
    // ============================================================

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            Log.w(
                TAG,
                "⚠️ CallService started with null intent"
            )

            return START_NOT_STICKY
        }

        try {

            // ====================================================
            // BASIC CALL INFORMATION
            // ====================================================

            val childId =
                intent.getStringExtra("childId")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: run {

                        Log.e(
                            TAG,
                            "❌ Missing childId"
                        )

                        return START_NOT_STICKY
                    }


            val number =
                intent.getStringExtra("number")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "UNKNOWN"


            val direction =
                intent.getStringExtra("direction")
                    ?.trim()
                    ?.uppercase()
                    ?: "INCOMING"


            val timestamp =
                intent.getLongExtra(
                    "timestamp",
                    System.currentTimeMillis()
                )


            val duration =
                intent.getLongExtra(
                    "duration",
                    0L
                )


            val finalDuration =
                if (
                    direction == "MISSED"
                ) {
                    0L
                } else {
                    duration.coerceAtLeast(0L)
                }


            // ====================================================
            // TELEPHONY
            // ====================================================

            val telephonyManager =
                getSystemService(
                    TELEPHONY_SERVICE
                ) as TelephonyManager


            val subscriptionManager =
                getSystemService(
                    SubscriptionManager::class.java
                )


            // ====================================================
            // SUBSCRIPTION ID
            //
            // IMPORTANT:
            //
            // We MUST prefer the subscription ID supplied by
            // CallReceiver.
            //
            // Do NOT immediately use the default voice SIM.
            // ====================================================

            val suppliedSubscriptionId =
                intent.getIntExtra(
                    EXTRA_SUBSCRIPTION_ID,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )


            val defaultVoiceSubscriptionId =
                SubscriptionManager
                    .getDefaultVoiceSubscriptionId()


            val subscriptionId =
                when {

                    SubscriptionManager.isValidSubscriptionId(
                        suppliedSubscriptionId
                    ) -> {

                        Log.d(
                            TAG,
                            "📱 Using subscriptionId supplied by receiver = " +
                                    suppliedSubscriptionId
                        )

                        suppliedSubscriptionId
                    }


                    SubscriptionManager.isValidSubscriptionId(
                        defaultVoiceSubscriptionId
                    ) -> {

                        Log.w(
                            TAG,
                            "⚠️ Receiver did not provide valid subscriptionId."
                        )

                        Log.w(
                            TAG,
                            "⚠️ Falling back to default voice subscription = " +
                                    defaultVoiceSubscriptionId
                        )

                        defaultVoiceSubscriptionId
                    }


                    else -> {

                        Log.w(
                            TAG,
                            "⚠️ No valid subscription ID available"
                        )

                        SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    }
                }


            // ====================================================
            // FIND SIM INFORMATION
            // ====================================================

            val simInfo: SubscriptionInfo? =

                if (
                    SubscriptionManager.isValidSubscriptionId(
                        subscriptionId
                    )
                ) {

                    subscriptionManager
                        .getActiveSubscriptionInfo(
                            subscriptionId
                        )

                } else {

                    null
                }


            // ====================================================
            // SIM SLOT
            // ====================================================

            val simSlot =
                simInfo?.simSlotIndex
                    ?: -1


            // ====================================================
            // RAW SIM CARRIER NAME
            // ====================================================

            val rawCarrierName =
                simInfo
                    ?.carrierName
                    ?.toString()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""


            // ====================================================
            // SUBSCRIPTION-SPECIFIC TELEPHONY MANAGER
            // ====================================================

            val subscriptionTelephonyManager =

                if (
                    SubscriptionManager.isValidSubscriptionId(
                        subscriptionId
                    )
                ) {

                    if (
                        Build.VERSION.SDK_INT >=
                        Build.VERSION_CODES.N
                    ) {

                        telephonyManager
                            .createForSubscriptionId(
                                subscriptionId
                            )

                    } else {

                        telephonyManager
                    }

                } else {

                    telephonyManager
                }


            // ====================================================
            // SIM OPERATOR
            //
            // This is the important value for identifying
            // the actual SIM carrier.
            //
            // Kenya examples:
            //
            // 63902 -> Safaricom
            // 63903 -> Airtel
            // 63904 -> Faiba
            // 63905 -> Equitel
            // 63907 -> Telkom
            // ====================================================

            val simOperator =
                subscriptionTelephonyManager
                    .simOperator
                    ?.trim()
                    ?.replace(
                        " ",
                        ""
                    )
                    ?: ""


            // ====================================================
            // NETWORK OPERATOR
            //
            // NOTE:
            //
            // This is NOT used as the primary SIM carrier.
            //
            // It can represent the currently registered network
            // and can therefore be misleading.
            // ====================================================

            val networkOperatorName =
                subscriptionTelephonyManager
                    .networkOperatorName
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""


            // ====================================================
            // COUNTRY
            // ====================================================

            val country =
                simInfo
                    ?.countryIso
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?.uppercase()
                    ?: subscriptionTelephonyManager
                        .simCountryIso
                        ?.trim()
                        ?.takeIf {
                            it.isNotBlank()
                        }
                        ?.uppercase()
                    ?: "Unknown"


            // ====================================================
            // RESOLVE ACTUAL SIM CARRIER
            // ====================================================

            val resolvedCarrier =
                resolveCarrier(
                    simOperator = simOperator,
                    rawCarrierName = rawCarrierName
                )


            // ====================================================
            // DEBUG
            // ====================================================

            Log.d(
                TAG,
                """
                
                📞 CALL INFORMATION
                =====================================
                
                childId             = $childId
                
                direction           = $direction
                number              = $number
                duration            = $finalDuration
                timestamp           = $timestamp
                
                -------------------------------------
                
                suppliedSubId       = $suppliedSubscriptionId
                defaultVoiceSubId   = $defaultVoiceSubscriptionId
                selectedSubId       = $subscriptionId
                
                simSlot             = $simSlot
                
                -------------------------------------
                
                simOperator         = $simOperator
                rawCarrierName      = $rawCarrierName
                networkOperator     = $networkOperatorName
                
                -------------------------------------
                
                RESOLVED CARRIER    = $resolvedCarrier
                country             = $country
                
                =====================================
                
                """.trimIndent()
            )


            // ====================================================
            // CREATE FIREBASE RECORD
            // ====================================================

            val callRecord =
                CallRecord(

                    number =
                        number,

                    direction =
                        direction,

                    timestamp =
                        timestamp,

                    duration =
                        finalDuration,

                    provider =
                        resolvedCarrier,

                    country =
                        country,

                    simSlot =
                        simSlot,

                    simCarrier =
                        resolvedCarrier,

                    subscriptionId =
                        subscriptionId
                )


            // ====================================================
            // SAVE TO FIREBASE
            // ====================================================

            callsRef(childId)
                .push()
                .setValue(callRecord)
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        """
                        
                        ✅ CALL SAVED
                        =====================================
                        
                        path =
                        phone_calls/$childId
                        
                        number =
                        $number
                        
                        direction =
                        $direction
                        
                        carrier =
                        $resolvedCarrier
                        
                        simOperator =
                        $simOperator
                        
                        slot =
                        $simSlot
                        
                        subscriptionId =
                        $subscriptionId
                        
                        =====================================
                        
                        """.trimIndent()
                    )
                }
                .addOnFailureListener { e ->

                    Log.e(
                        TAG,
                        "❌ Failed saving call to Firebase",
                        e
                    )
                }


        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "❌ Missing phone/subscription permission",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ onStartCommand error",
                e
            )
        }


        return START_STICKY
    }


    // ============================================================
    // CARRIER RESOLVER
    // ============================================================

    private fun resolveCarrier(
        simOperator: String,
        rawCarrierName: String
    ): String {

        val operator =
            simOperator
                .trim()
                .replace(
                    " ",
                    ""
                )


        // ========================================================
        // KENYA MCC/MNC
        // ========================================================

        when {

            // ----------------------------------------------------
            // SAFARICOM
            // ----------------------------------------------------

            operator == "63902" ||
                    operator.startsWith("63902") -> {

                return "Safaricom"
            }


            // ----------------------------------------------------
            // AIRTEL KENYA
            // ----------------------------------------------------

            operator == "63903" ||
                    operator.startsWith("63903") -> {

                return "Airtel"
            }


            // ----------------------------------------------------
            // FAIBA / JAMII
            // ----------------------------------------------------

            operator == "63904" ||
                    operator.startsWith("63904") -> {

                return "Faiba"
            }


            // ----------------------------------------------------
            // EQUITEL
            // ----------------------------------------------------

            operator == "63905" ||
                    operator.startsWith("63905") -> {

                return "Equitel"
            }


            // ----------------------------------------------------
            // TELKOM KENYA
            // ----------------------------------------------------

            operator == "63907" ||
                    operator.startsWith("63907") -> {

                return "Telkom"
            }
        }


        // ========================================================
        // UNKNOWN MCC/MNC
        //
        // Only now fall back to carrierName.
        // ========================================================

        if (
            rawCarrierName.isNotBlank()
        ) {

            return rawCarrierName
        }


        return "Unknown"
    }


    // ============================================================
    // FIREBASE REFERENCE
    // ============================================================

    private fun callsRef(
        childId: String
    ): DatabaseReference {

        return database
            .getReference(
                "phone_calls"
            )
            .child(
                childId
            )
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
                        "Call Monitoring",
                        NotificationManager.IMPORTANCE_LOW
                    )

                channel.setShowBadge(false)

                getSystemService(
                    NotificationManager::class.java
                )?.createNotificationChannel(
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
                        "Monitoring phone calls"
                    )
                    .setSmallIcon(
                        R.mipmap.ic_launcher
                    )
                    .setOngoing(true)
                    .build()


            startForeground(
                NOTIF_ID,
                notification
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ Foreground service error",
                e
            )

            stopSelf()
        }
    }


    // ============================================================
    // BIND
    // ============================================================

    override fun onBind(
        intent: Intent?
    ): IBinder? = null
}