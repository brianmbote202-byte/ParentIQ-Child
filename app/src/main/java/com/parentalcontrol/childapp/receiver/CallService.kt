package com.parentalcontrol.childapp.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.util.Log
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

        const val EXTRA_SUBSCRIPTION_ID =
            "subscriptionId"

        const val EXTRA_SIM_SLOT =
            "simSlot"

        const val EXTRA_CARRIER_NAME =
            "carrierName"

        const val EXTRA_SIM_CARRIER =
            "simCarrier"

        const val EXTRA_SIM_DISPLAY_NAME =
            "simDisplayName"

        const val EXTRA_SIM_COUNTRY =
            "simCountry"
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

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent == null) {

            Log.w(
                TAG,
                "⚠️ CallService started with NULL intent"
            )

            stopSelfResult(startId)

            return START_NOT_STICKY
        }

        try {

            // =====================================================
            // BASIC CALL INFORMATION
            // =====================================================

            val childId =
                intent
                    .getStringExtra("childId")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: run {

                        Log.e(
                            TAG,
                            "❌ Missing childId"
                        )

                        stopSelfResult(startId)

                        return START_NOT_STICKY
                    }

            val number =
                intent
                    .getStringExtra("number")
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: "UNKNOWN"

            val contactName =
                intent
                    .getStringExtra("contactName")
                    ?.trim()
                    ?: ""

            val contactSaved =
                intent.getBooleanExtra(
                    "contactSaved",
                    false
                )

            val direction =
                intent
                    .getStringExtra("direction")
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

            // =====================================================
            // RECEIVER SIM DATA
            // =====================================================

            val suppliedSubscriptionId =
                intent.getIntExtra(
                    EXTRA_SUBSCRIPTION_ID,
                    SubscriptionManager.INVALID_SUBSCRIPTION_ID
                )

            val suppliedSimSlot =
                intent.getIntExtra(
                    EXTRA_SIM_SLOT,
                    -1
                )

            val suppliedCarrierName =
                intent
                    .getStringExtra(
                        EXTRA_CARRIER_NAME
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""

            val suppliedSimCarrier =
                intent
                    .getStringExtra(
                        EXTRA_SIM_CARRIER
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: suppliedCarrierName

            val suppliedDisplayName =
                intent
                    .getStringExtra(
                        EXTRA_SIM_DISPLAY_NAME
                    )
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: suppliedSimCarrier

            val suppliedSimCountry =
                intent
                    .getStringExtra(
                        EXTRA_SIM_COUNTRY
                    )
                    ?.trim()
                    ?.uppercase()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""

            Log.d(
                TAG,
                """
                📦 RECEIVER → CALL SERVICE
                =====================================
                subscriptionId = $suppliedSubscriptionId
                simSlot        = $suppliedSimSlot
                carrierName    = $suppliedCarrierName
                simCarrier     = $suppliedSimCarrier
                displayName    = $suppliedDisplayName
                country        = $suppliedSimCountry
                =====================================
                """.trimIndent()
            )

            // =====================================================
            // OPTIONAL ANDROID ENRICHMENT
            //
            // IMPORTANT:
            //
            // We ONLY query the exact subscription ID supplied
            // by CallReceiver.
            //
            // We NEVER choose:
            //
            // - default voice SIM
            // - default data SIM
            // - first SIM
            // - another active SIM
            //
            // If the receiver supplied -1, the service preserves
            // the receiver values.
            // =====================================================

            val subscriptionInfo =
                getSubscriptionInfo(
                    suppliedSubscriptionId
                )

            // =====================================================
            // FINAL SUBSCRIPTION ID
            // =====================================================

            val finalSubscriptionId =
                if (
                    subscriptionInfo != null &&
                    SubscriptionManager.isValidSubscriptionId(
                        subscriptionInfo.subscriptionId
                    )
                ) {

                    subscriptionInfo.subscriptionId

                } else {

                    suppliedSubscriptionId
                }

            // =====================================================
            // FINAL SLOT
            // =====================================================

            val finalSimSlot =
                subscriptionInfo
                    ?.simSlotIndex
                    ?.takeIf {
                        it >= 0
                    }
                    ?: suppliedSimSlot

            // =====================================================
            // ANDROID CARRIER
            // =====================================================

            val androidCarrierName =
                subscriptionInfo
                    ?.carrierName
                    ?.toString()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""

            // =====================================================
            // FINAL CARRIER
            //
            // Android information is used only for the same
            // subscription supplied by the receiver.
            // =====================================================

            val finalCarrier =
                androidCarrierName
                    .ifBlank {
                        suppliedCarrierName
                    }
                    .ifBlank {
                        suppliedSimCarrier
                    }
                    .ifBlank {
                        "Unknown"
                    }

            val finalSimCarrier =
                androidCarrierName
                    .ifBlank {
                        suppliedSimCarrier
                    }
                    .ifBlank {
                        suppliedCarrierName
                    }
                    .ifBlank {
                        "Unknown"
                    }

            // =====================================================
            // DISPLAY NAME
            // =====================================================

            val androidDisplayName =
                subscriptionInfo
                    ?.displayName
                    ?.toString()
                    ?.trim()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""

            val finalDisplayName =
                androidDisplayName
                    .ifBlank {
                        suppliedDisplayName
                    }
                    .ifBlank {
                        finalCarrier
                    }

            // =====================================================
            // COUNTRY
            // =====================================================

            val androidCountry =
                subscriptionInfo
                    ?.countryIso
                    ?.trim()
                    ?.uppercase()
                    ?.takeIf {
                        it.isNotBlank()
                    }
                    ?: ""

            val country =
                androidCountry
                    .ifBlank {
                        suppliedSimCountry
                    }
                    .ifBlank {
                        "Unknown"
                    }

            // =====================================================
            // FINAL DIAGNOSTIC
            // =====================================================

            Log.d(
                TAG,
                """
                📞 FINAL CALL INFORMATION
                ==========================================

                childId             = $childId

                direction           = $direction
                number              = $number
                duration            = $finalDuration
                timestamp           = $timestamp

                ------------------------------------------

                RECEIVER RESOLUTION
                subscriptionId      = $suppliedSubscriptionId
                simSlot             = $suppliedSimSlot
                carrier             = $suppliedCarrierName
                simCarrier          = $suppliedSimCarrier
                displayName         = $suppliedDisplayName
                country             = $suppliedSimCountry

                ------------------------------------------

                ANDROID ENRICHMENT
                subscriptionId      = ${
                    subscriptionInfo?.subscriptionId
                        ?: "Unavailable"
                }

                simSlot             = ${
                    subscriptionInfo?.simSlotIndex
                        ?: "Unavailable"
                }

                carrierName         = ${
                    androidCarrierName.ifBlank {
                        "Unavailable"
                    }
                }

                displayName         = ${
                    androidDisplayName.ifBlank {
                        "Unavailable"
                    }
                }

                countryIso          = ${
                    androidCountry.ifBlank {
                        "Unavailable"
                    }
                }

                ------------------------------------------

                FINAL FIREBASE VALUES

                provider            = $finalCarrier
                simCarrier          = $finalSimCarrier
                simSlot             = $finalSimSlot
                subscriptionId      = $finalSubscriptionId
                country             = $country
                displayName         = $finalDisplayName

                ------------------------------------------

                contactName         = $contactName
                contactSaved        = $contactSaved

                ==========================================
                """.trimIndent()
            )

            // =====================================================
            // CREATE FIREBASE RECORD
            // =====================================================

            val callRecord =
                CallRecord(

                    number =
                        number,

                    direction =
                        direction,

                    contactName =
                        contactName,

                    contactSaved =
                        contactSaved,

                    timestamp =
                        timestamp,

                    duration =
                        finalDuration,

                    provider =
                        finalCarrier,

                    country =
                        country,

                    simSlot =
                        finalSimSlot,

                    simCarrier =
                        finalSimCarrier,

                    subscriptionId =
                        finalSubscriptionId
                )

            // =====================================================
            // SAVE FIREBASE
            // =====================================================

            callsRef(
                childId
            )
                .push()
                .setValue(
                    callRecord
                )
                .addOnSuccessListener {

                    Log.d(
                        TAG,
                        """
                        ✅✅✅ CALL SAVED TO FIREBASE ✅✅✅
                        =====================================

                        path =
                        phone_calls/$childId

                        number =
                        $number

                        direction =
                        $direction

                        provider =
                        $finalCarrier

                        simCarrier =
                        $finalSimCarrier

                        simSlot =
                        $finalSimSlot

                        subscriptionId =
                        $finalSubscriptionId

                        country =
                        $country

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
                "❌ Phone/subscription permission error",
                e
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ onStartCommand error",
                e
            )
        }

        return START_NOT_STICKY
    }

    // ============================================================
    // GET SUBSCRIPTION INFORMATION
    // ============================================================

    private fun getSubscriptionInfo(
        subscriptionId: Int
    ): SubscriptionInfo? {

        if (
            !SubscriptionManager.isValidSubscriptionId(
                subscriptionId
            )
        ) {

            Log.d(
                TAG,
                "ℹ️ No valid subscription ID supplied by receiver"
            )

            return null
        }

        return try {

            val subscriptionManager =
                getSystemService(
                    SubscriptionManager::class.java
                )

            val info =
                subscriptionManager
                    ?.getActiveSubscriptionInfo(
                        subscriptionId
                    )

            if (
                info == null
            ) {

                Log.w(
                    TAG,
                    """
                    ⚠️ SubscriptionInfo unavailable

                    subscriptionId=$subscriptionId

                    The service will preserve the values
                    supplied by CallReceiver.

                    No other SIM will be selected.
                    """.trimIndent()
                )

            } else {

                Log.d(
                    TAG,
                    """
                    🟢 ANDROID SUBSCRIPTION FOUND
                    --------------------------------
                    subscriptionId=${info.subscriptionId}
                    simSlot=${info.simSlotIndex}
                    carrierName=${info.carrierName}
                    displayName=${info.displayName}
                    countryIso=${info.countryIso}
                    --------------------------------
                    """.trimIndent()
                )
            }

            info

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "❌ Cannot read SubscriptionInfo",
                e
            )

            null

        } catch (e: Exception) {

            Log.e(
                TAG,
                "❌ SubscriptionInfo error",
                e
            )

            null
        }
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

                channel.setShowBadge(
                    false
                )

                getSystemService(
                    NotificationManager::class.java
                )
                    ?.createNotificationChannel(
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
                        "Saving call information"
                    )
                    .setSmallIcon(
                        R.mipmap.ic_launcher
                    )
                    .setOngoing(
                        true
                    )
                    .setCategory(
                        NotificationCompat.CATEGORY_SERVICE
                    )
                    .build()

            if (
                Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.Q
            ) {

                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo
                        .FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )

            } else {

                startForeground(
                    NOTIF_ID,
                    notification
                )
            }

            Log.d(
                TAG,
                "✅ CallService foreground started"
            )

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "❌ Foreground security error",
                e
            )

            stopSelf()

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
    ): IBinder? {

        return null
    }
}