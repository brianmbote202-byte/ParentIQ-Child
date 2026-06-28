package com.parentalcontrol.childapp.receiver

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.model.CallRecord
import com.parentalcontrol.childapp.R

class CallService : Service() {

    private lateinit var telephonyManager: TelephonyManager

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "call_service_channel"
        private const val NOTIF_ID = 102
    }

    private lateinit var database: FirebaseDatabase

    override fun onCreate() {
        super.onCreate()
        database = FirebaseDatabase.getInstance()
        startForegroundSafely()
    }

    @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_NOT_STICKY

        try {
            val childId = intent.getStringExtra("childId") ?: return START_NOT_STICKY
            val number = intent.getStringExtra("number") ?: "UNKNOWN"
            val direction = intent.getStringExtra("direction") ?: "INCOMING"
            val timestamp = intent.getLongExtra("timestamp", System.currentTimeMillis())
            val duration = intent.getLongExtra("duration", 0L)

            var finalDuration = duration

            if (direction == "MISSED") {
                finalDuration = 0L
            }


            telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

            val provider = telephonyManager.networkOperatorName
                ?.takeIf { it.isNotBlank() }
                ?: "Unknown"

            val country = telephonyManager.simCountryIso?.takeIf { it.isNotBlank() }
                ?.uppercase() ?: "Unknown"

            //---------dual sim API------
            val subscriptionManager =
                getSystemService(SubscriptionManager::class.java)

            //---------GET ACTIVE SIMS-------
            val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
            //----------GET SIM SLOT INFO---------
            val simInfo = activeSubscriptions?.firstOrNull()

            val simSlot = simInfo?.simSlotIndex ?: -1
            val simCarrier = simInfo?.carrierName?.toString() ?: "Unknown"

            Log.d(TAG, "📞 Saving call → $direction $number ($provider) duration=$finalDuration")

            val callRecord = CallRecord(
                number = number,
                direction = direction,
                timestamp = timestamp,
                duration =  finalDuration,
                provider = provider,
                country = country,
                simSlot = simSlot,
                simCarrier = simCarrier
            )



            callsRef(childId)
                .push()
                .setValue(callRecord)
                .addOnSuccessListener {
                    Log.d(TAG, "✅ Call saved to phone_calls/$childId")
                }
                .addOnFailureListener { e ->
                    Log.e(TAG, "❌ Failed saving call", e)
                }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ onStartCommand error", e)
        }

        return START_STICKY
    }

    //-------helper to send call data to "child" under phone_calls in firebase--------
    private fun callsRef(childId: String) =
        FirebaseDatabase.getInstance()
            .getReference("phone_calls")
            .child(childId)



    private fun startForegroundSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Call Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                )
                channel.setShowBadge(false)
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Parental Control Active")
                .setContentText("Monitoring phone calls")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setOngoing(true)
                .build()

            startForeground(NOTIF_ID, notification)

        } catch (e: Exception) {
            Log.e(TAG, "❌ Foreground error", e)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}