package com.parentalcontrol.childapp

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase

class LockScreenActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "REQUEST_TIME"
    }

    private lateinit var btnRequestMoreTime: Button
    private lateinit var btnOverlayClose: Button
    private lateinit var btnOverlayExit: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "🚀 LockScreenActivity OPENED")

        setContentView(R.layout.overlay_screen_time)

        Log.d(TAG, "✅ Layout loaded")

        // ====================================
        // BIND VIEWS
        // ====================================

        btnRequestMoreTime =
            findViewById(R.id.btnRequestMoreTime)

        btnOverlayClose =
            findViewById(R.id.btnOverlayClose)

        btnOverlayExit =
            findViewById(R.id.btnOverlayExit)

        Log.d(TAG, "✅ Buttons bound")

        // ====================================
        // DISABLE BACK BUTTON
        // ====================================

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {

                    Log.d(
                        TAG,
                        "⛔ Back button blocked"
                    )
                }
            })

        // ====================================
        // REQUEST MORE TIME
        // ====================================

        btnRequestMoreTime.setOnClickListener {

            Log.d(
                TAG,
                "🔥 REQUEST BUTTON CLICKED"
            )

            Toast.makeText(
                this,
                "Sending request...",
                Toast.LENGTH_SHORT
            ).show()

            requestExtraTime(15)
        }

        // ====================================
        // CLOSE BUTTON
        // ====================================

        btnOverlayClose.setOnClickListener {

            Log.d(
                TAG,
                "❌ Overlay close pressed"
            )

            finish()
        }

        // ====================================
        // EXIT BUTTON
        // ====================================

        btnOverlayExit.setOnClickListener {

            Log.d(
                TAG,
                "🚪 Exit pressed"
            )

            finishAffinity()
        }
    }

    // ====================================
    // REQUEST EXTRA TIME
    // ====================================

    private fun requestExtraTime(minutes: Int) {

        Log.d(
            TAG,
            "🚀 requestExtraTime STARTED"
        )

        // ====================================
        // LOAD PREFS
        // ====================================

        val prefs =
            applicationContext.getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            )

        val childId =
            prefs.getString(
                "child_id",
                "unknown_child"
            ) ?: "unknown_child"

        Log.d(
            TAG,
            "👶 Loaded childId = $childId"
        )

        // ====================================
        // VALIDATION
        // ====================================

        if (
            childId.isBlank() ||
            childId == "unknown_child"
        ) {

            Log.e(
                TAG,
                "❌ INVALID CHILD ID"
            )

            Toast.makeText(
                this,
                "Child ID missing",
                Toast.LENGTH_LONG
            ).show()

            return
        }

        // ====================================
        // FIREBASE REF
        // ====================================

        val requestsRef =
            FirebaseDatabase.getInstance()
                .reference
                .child("requests")
                .child(childId)

        val requestId =
            requestsRef.push().key

        if (requestId == null) {

            Log.e(
                TAG,
                "❌ REQUEST ID NULL"
            )

            return
        }

        // ====================================
        // REQUEST OBJECT
        // ====================================

        val requestData = mapOf(
            "requestId" to requestId,
            "type" to "extra_time",
            "minutes" to minutes,
            "status" to "pending",
            "timestamp" to System.currentTimeMillis()
        )

        Log.d(
            TAG,
            "📤 Uploading to Firebase..."
        )

        // ====================================
        // FIREBASE WRITE
        // ====================================

        requestsRef
            .child(requestId)
            .setValue(requestData)

            .addOnSuccessListener {

                Log.d(
                    TAG,
                    "✅ REQUEST SUCCESS"
                )

                Toast.makeText(
                    this,
                    "Request sent to parent",
                    Toast.LENGTH_LONG
                ).show()
            }

            .addOnFailureListener { error ->

                Log.e(
                    TAG,
                    "❌ FIREBASE FAILED",
                    error
                )

                Toast.makeText(
                    this,
                    "Firebase upload failed",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
}