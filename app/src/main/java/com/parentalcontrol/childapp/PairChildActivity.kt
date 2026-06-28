package com.parentalcontrol.childapp

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.database.FirebaseDatabase
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.parentalcontrol.childapp.receiver.InsightWorker
import com.parentalcontrol.childapp.service.InsightScheduler
import com.parentalcontrol.childapp.vpn.VpnController
import org.json.JSONObject

class PairChildActivity : AppCompatActivity() {


    private lateinit var btnScanQR: Button

    // ✅ QR Scanner launcher (CORRECT approach)
    private val qrLauncher = registerForActivityResult(ScanContract()) { result ->

        if (result.contents == null) {
            Toast.makeText(this, "Scan cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }

        val raw = result.contents.trim()

        try {
            val json = JSONObject(raw)

            if (json.has("pairingCode")) {
                validatePairingCode(json.getString("pairingCode"))
                return@registerForActivityResult
            }

        } catch (_: Exception) {
            // not JSON → continue
        }

        validatePairingCode(raw)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ ONLY ONE layout
        setContentView(R.layout.activity_pair_child)


        btnScanQR = findViewById(R.id.btnScanQR)



        // QR scan button
        btnScanQR.setOnClickListener {
            val options = ScanOptions().apply {
                setPrompt("Scan QR from Parent App")
                setBeepEnabled(true)
                setOrientationLocked(false)
            }

            qrLauncher.launch(options)
        }
    }
    private fun validatePairingCode(childId: String) {

        val db = FirebaseDatabase.getInstance().reference
        val childRef = db.child("children").child(childId)

        childRef.get().addOnSuccessListener { snapshot ->



            if (!snapshot.exists()) {
                Toast.makeText(this, "Invalid QR code", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }

            val alreadyPaired = snapshot.child("paired").getValue(Boolean::class.java) ?: false

            if (alreadyPaired) {
                Toast.makeText(this, "Device already paired", Toast.LENGTH_SHORT).show()
                return@addOnSuccessListener
            }


            childRef.child("paired")
                .setValue(true)
                .addOnSuccessListener {

                    val saved = getSharedPreferences(
                        "child_prefs",
                        MODE_PRIVATE
                    ).edit()
                        .putString("child_id", childId)
                        .putBoolean("onboarding_done", true)
                        .putBoolean("vpn_allowed", true)
                        .commit()

                    Log.e("PAIR_DEBUG", "PREF SAVE SUCCESS = $saved")

                    val checkId = getSharedPreferences(
                        "child_prefs",
                        MODE_PRIVATE
                    ).getString("child_id", null)

                    Log.e("PAIR_DEBUG", "VERIFY SAVED ID = $checkId")

                    InsightScheduler.scheduleInsights(
                        this@PairChildActivity,
                        childId
                    )

                    startActivity(
                        Intent(this, MainActivity::class.java).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                    )

                    finish()
                }

                .addOnFailureListener { e ->

                    Log.e(
                        "PAIR_DEBUG",
                        "PAIRING FAILED",
                        e
                    )

                    Toast.makeText(
                        this,
                        "Pairing failed",
                        Toast.LENGTH_SHORT
                    ).show()
                }

            VpnController.startVpn(this@PairChildActivity)
        }.addOnFailureListener {
            Toast.makeText(this, "Network error", Toast.LENGTH_SHORT).show()
        }
        Log.e("QR_DEBUG", "Received = $childId")

    }
}