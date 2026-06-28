package com.parentalcontrol.childapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.firebase.database.FirebaseDatabase
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.google.zxing.ResultPoint

class QRScannerActivity : AppCompatActivity() {

    private lateinit var barcodeView: CompoundBarcodeView
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        barcodeView = CompoundBarcodeView(this)
        setContentView(barcodeView)

        if (hasCameraPermission()) {
            startScanner()
        } else {
            requestCameraPermission()
        }

        Log.e(
            "FIREBASE_DEBUG",
            "PROJECT = ${FirebaseDatabase.getInstance().app.options.projectId}"
        )
    }

    // ===================== SCANNER =====================

    private fun startScanner() {

        barcodeView.decodeContinuous(object : BarcodeCallback {

            override fun barcodeResult(result: BarcodeResult?) {

                val code = result?.text ?: return

                // prevent multiple triggers
                if (isProcessing) return
                isProcessing = true

                barcodeView.pause()
                validatePairingCode(code.trim())

                Log.e("QR_DEBUG", "SCANNED QR = $code")


                Log.e(
                    "PAIR_DEBUG",
                    "🔥 barcodeResult fired"
                )

                Log.e(
                    "PAIR_DEBUG",
                    "🔥 QR CODE = $code"
                )

                if (isProcessing) return
                isProcessing = true

                barcodeView.pause()
                validatePairingCode(code.trim())
            }

            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {}
        })
    }


    private fun validatePairingCode(childId: String) {


        val startTime = System.currentTimeMillis()



        Log.e("PAIR_DEBUG", "================================")
        Log.e("PAIR_DEBUG", "QR VALUE = [$childId]")

        if (childId.isBlank()) {

            Log.e("PAIR_DEBUG", "❌ Empty QR")

            Toast.makeText(
                this,
                "Invalid QR",
                Toast.LENGTH_SHORT
            ).show()

            resetScanner()
            return
        }

        val db = FirebaseDatabase.getInstance().reference

        Log.e(
            "PAIR_DEBUG",
            "Firebase Path = children/$childId"
        )

        val childRef =
            db.child("children")
                .child(childId)

        childRef.get()

            .addOnSuccessListener { snapshot ->

                Log.e(
                    "PAIR_DEBUG",
                    "⏱ Firebase read took ${
                        System.currentTimeMillis() - startTime
                    } ms"
                )

                Log.e(
                    "PAIR_DEBUG",
                    "snapshot.exists = ${snapshot.exists()}"
                )

                if (!snapshot.exists()) {
                    Log.e(
                        "PAIR_DEBUG",
                        "snapshot.exists = ${snapshot.exists()}"
                    )

                    Log.e(
                        "PAIR_DEBUG",
                        "snapshot.value = ${snapshot.value}"
                    )

                    Log.e(
                        "PAIR_DEBUG",
                        "❌ Child does not exist"
                    )

                    Toast.makeText(
                        this,
                        "Invalid QR code",
                        Toast.LENGTH_SHORT
                    ).show()

                    resetScanner()
                    return@addOnSuccessListener
                }

                Log.e(
                    "PAIR_DEBUG",
                    "✅ Child found"
                )

                Log.e(
                    "PAIR_DEBUG",
                    "Firebase Data = ${snapshot.value}"
                )

                saveLocally(childId)

                val savedId =
                    getSharedPreferences(
                        "child_prefs",
                        MODE_PRIVATE
                    ).getString(
                        "child_id",
                        null
                    )

                Log.e(
                    "PAIR_DEBUG",
                    "Saved child_id = $savedId"
                )

                childRef.child("paired")
                    .setValue(true)

                    .addOnSuccessListener {

                        Log.e(
                            "PAIR_DEBUG",
                            "⏱ TOTAL PAIRING TIME = ${
                                System.currentTimeMillis() - startTime
                            } ms"
                        )

                        Log.e(
                            "PAIR_DEBUG",
                            "✅ paired=true updated"
                        )

                        goToMain()
                    }

                    .addOnFailureListener {

                        Log.e(
                            "PAIR_DEBUG",
                            "❌ paired update failed",
                            it
                        )

                        resetScanner()
                    }
            }

            .addOnFailureListener { e ->

                Log.e(
                    "PAIR_DEBUG",
                    "❌ Firebase read failed",
                    e
                )

                Toast.makeText(
                    this,
                    "Network error",
                    Toast.LENGTH_SHORT
                ).show()

                resetScanner()
            }
    }

    private fun saveLocally(childId: String) {

        val success = getSharedPreferences(
            "child_prefs",
            MODE_PRIVATE
        ).edit()

            .putString("child_id", childId)

            // IMPORTANT
            .putBoolean("onboarding_done", true)


            // 🔥 ADD THIS
            .putBoolean("vpn_allowed", true)

            .commit()

        if (!success) {

            Toast.makeText(
                this,
                "Failed saving pairing data",
                Toast.LENGTH_SHORT
            ).show()
        }
    }


    private fun goToMain() {

        val childId =
            getSharedPreferences(
                "child_prefs",
                MODE_PRIVATE
            ).getString(
                "child_id",
                null
            )

        Log.e(
            "PAIR_DEBUG",
            "🚀 goToMain child_id = $childId"
        )

        Toast.makeText(
            this,
            "Device paired ✓",
            Toast.LENGTH_SHORT
        ).show()

        val intent =
            Intent(this, MainActivity::class.java).apply {

                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TASK
            }

        startActivity(intent)

        finish()
    }

    // ===================== RESET SCANNER =====================

    private fun resetScanner() {
        isProcessing = false
        barcodeView.resume()
    }

    // ===================== PERMISSIONS =====================

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            2001
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 2001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startScanner()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ===================== LIFECYCLE =====================

    override fun onResume() {
        super.onResume()
        if (hasCameraPermission()) {
            resetScanner()
        }
    }

    override fun onPause() {
        super.onPause()
        barcodeView.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeView.pause()
    }
}