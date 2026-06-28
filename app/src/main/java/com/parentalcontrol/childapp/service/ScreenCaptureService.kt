package com.parentalcontrol.childapp.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.FirebaseDatabase
import com.parentalcontrol.childapp.ai.FirebaseAlertUploader
import com.parentalcontrol.childapp.utils.ContentClassifier2
import com.parentalcontrol.childapp.utils.RiskCombiner
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {

    companion object {

        private const val TAG = "ScreenCaptureService"

        private const val CHANNEL_ID =
            "screen_capture_channel"

        private const val NOTIFICATION_ID = 1001

        // =========================================
        // SAFETY
        // =========================================
        private const val CAPTURE_INTERVAL = 15000L
        private const val MAX_BITMAP_WIDTH = 720
        private const val MAX_BITMAP_HEIGHT = 1280

        @Volatile
        var isRunning = false
    }

    // =============================================
    // CORE
    // =============================================
    private var mediaProjection: MediaProjection? = null

    private var imageReader: ImageReader? = null

    private var virtualDisplay: VirtualDisplay? = null

    private lateinit var childId: String

    // =============================================
    // COROUTINES
    // =============================================
    private val serviceScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.Default
        )

    // =============================================
    // SAFETY FLAGS
    // =============================================
    private val processingImage =
        AtomicBoolean(false)

    @Volatile
    private var lastCaptureTime = 0L

    // =============================================
    // START
    // =============================================
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        try {

            // =====================================
            // START FOREGROUND IMMEDIATELY
            // =====================================
            startForeground(
                NOTIFICATION_ID,
                createNotification()
            )

            if (isRunning) {

                Log.e(
                    TAG,
                    "Already running"
                )

                return START_STICKY
            }

            isRunning = true

            // =====================================
            // GET PERMISSION DATA
            // =====================================
            val resultCode =
                intent?.getIntExtra(
                    "resultCode",
                    Activity.RESULT_CANCELED
                ) ?: Activity.RESULT_CANCELED

            val data =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                    intent?.getParcelableExtra(
                        "data",
                        Intent::class.java
                    )

                } else {

                    @Suppress("DEPRECATION")
                    intent?.getParcelableExtra("data")
                }

            if (
                resultCode != Activity.RESULT_OK ||
                data == null
            ) {

                Log.e(
                    TAG,
                    "Projection permission missing"
                )

                stopSelf()

                return START_NOT_STICKY
            }

            // =====================================
            // CHILD ID
            // =====================================
            val prefs =
                getSharedPreferences(
                    "child_prefs",
                    Context.MODE_PRIVATE
                )

            childId =
                prefs.getString(
                    "child_id",
                    "unknown_child"
                ) ?: "unknown_child"

            // =====================================
            // MEDIA PROJECTION
            // =====================================
            val projectionManager =
                getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

            mediaProjection = try {

                projectionManager.getMediaProjection(
                    resultCode,
                    data
                )

            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "MediaProjection error",
                    e
                )

                null
            }

            if (mediaProjection == null) {

                stopSelf()

                return START_NOT_STICKY
            }

            setupCapture()

            Log.e(
                TAG,
                "✅ ScreenCaptureService started"
            )

            return START_STICKY

        } catch (e: Exception) {

            Log.e(
                TAG,
                "START ERROR",
                e
            )

            stopSelf()

            return START_NOT_STICKY
        }
    }

    // =============================================
    // CAPTURE SETUP
    // =============================================
    private fun setupCapture() {

        try {

            val metrics = DisplayMetrics()

            val wm =
                getSystemService(
                    Context.WINDOW_SERVICE
                ) as WindowManager

            wm.defaultDisplay.getMetrics(metrics)

            val width =
                metrics.widthPixels
                    .coerceAtMost(MAX_BITMAP_WIDTH)

            val height =
                metrics.heightPixels
                    .coerceAtMost(MAX_BITMAP_HEIGHT)

            imageReader =
                ImageReader.newInstance(
                    width,
                    height,
                    PixelFormat.RGBA_8888,
                    2
                )

            virtualDisplay =
                mediaProjection?.createVirtualDisplay(
                    "ScreenCapture",
                    width,
                    height,
                    metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )

            imageReader?.setOnImageAvailableListener(
                { reader ->

                    handleImage(reader)

                },
                Handler(Looper.getMainLooper())
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "SETUP ERROR",
                e
            )

            stopSelf()
        }
    }

    // =============================================
    // HANDLE IMAGE
    // =============================================
    private fun handleImage(
        reader: ImageReader
    ) {

        try {

            val now =
                System.currentTimeMillis()

            // =====================================
            // THROTTLE
            // =====================================
            if (
                now - lastCaptureTime <
                CAPTURE_INTERVAL
            ) {
                return
            }

            lastCaptureTime = now

            // =====================================
            // PREVENT PARALLEL PROCESSING
            // =====================================
            if (
                processingImage.get()
            ) {
                return
            }

            processingImage.set(true)

            serviceScope.launch {

                try {

                    processLatestImage(reader)

                } catch (e: Exception) {

                    Log.e(
                        TAG,
                        "PROCESS IMAGE ERROR",
                        e
                    )

                } finally {

                    processingImage.set(false)
                }
            }

        } catch (e: Exception) {

            processingImage.set(false)

            Log.e(
                TAG,
                "HANDLE IMAGE ERROR",
                e
            )
        }
    }

    // =============================================
    // PROCESS IMAGE
    // =============================================
    private suspend fun processLatestImage(
        reader: ImageReader
    ) {

        val image = try {

            reader.acquireLatestImage()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Acquire image failed",
                e
            )

            null
        } ?: return

        try {

            val bitmap =
                imageToBitmap(image)

            image.close()

            bitmap?.let {

                processBitmap(it)

                it.recycle()
            }

        } catch (e: Exception) {

            try {
                image.close()
            } catch (_: Exception) {
            }

            Log.e(
                TAG,
                "IMAGE PROCESS FAILED",
                e
            )
        }
    }

    // =============================================
    // BITMAP
    // =============================================
    private fun imageToBitmap(
        image: Image
    ): Bitmap? {

        return try {

            val plane =
                image.planes[0]

            val buffer: ByteBuffer =
                plane.buffer

            val pixelStride =
                plane.pixelStride

            val rowStride =
                plane.rowStride

            val rowPadding =
                rowStride - pixelStride * image.width

            val bitmap =
                Bitmap.createBitmap(
                    image.width +
                            rowPadding / pixelStride,
                    image.height,
                    Bitmap.Config.ARGB_8888
                )

            bitmap.copyPixelsFromBuffer(buffer)

            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                image.width,
                image.height
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "BITMAP ERROR",
                e
            )

            null
        }
    }

    // =============================================
    // AI PROCESS
    // =============================================
    private suspend fun processBitmap(
        bitmap: Bitmap
    ) {

        try {

            val result =
                withContext(Dispatchers.Default) {

                    val explicitScore =
                        ContentClassifier2
                            .imageExplicitScore(
                                applicationContext,
                                bitmap
                            )

                    RiskCombiner.combine(
                        explicitScore,
                        ""
                    )
                }

            Log.e(
                TAG,
                "AI SCORE = ${result.riskScore}"
            )

            uploadRisk(result)

            // =====================================
            // ALERT
            // =====================================
            if (
                result.riskLevel == "CRITICAL" ||
                result.riskScore > 0.75f
            ) {

                FirebaseAlertUploader.uploadAlert(
                    childId = childId,
                    type = result.riskLevel.lowercase(),
                    level = result.riskLevel,
                    score = result.riskScore,
                    categories = result.categories
                )
            }

        } catch (e: Exception) {

            Log.e(
                TAG,
                "BITMAP AI ERROR",
                e
            )
        }
    }

    // =============================================
    // FIREBASE
    // =============================================
    private fun uploadRisk(
        result: RiskCombiner.RiskResult
    ) {

        try {

            val data = mapOf(
                "riskScore" to result.riskScore,
                "riskLevel" to result.riskLevel,
                "categories" to result.categories.toList(),
                "timestamp" to System.currentTimeMillis()
            )

            FirebaseDatabase.getInstance()
                .getReference("children")
                .child(childId)
                .child("ai_monitor")
                .push()
                .setValue(data)

        } catch (e: Exception) {

            Log.e(
                TAG,
                "UPLOAD ERROR",
                e
            )
        }
    }

    // =============================================
    // NOTIFICATION
    // =============================================
    private fun createNotification(): Notification {

        createNotificationChannel()

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setContentTitle(
                "Child Protection Active"
            )
            .setContentText(
                "Monitoring harmful content"
            )
            .setSmallIcon(
                android.R.drawable.ic_menu_view
            )
            .setOngoing(true)
            .setPriority(
                NotificationCompat.PRIORITY_LOW
            )
            .build()
    }

    // =============================================
    // CHANNEL
    // =============================================
    private fun createNotificationChannel() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.O
        ) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen Monitoring",
                    NotificationManager.IMPORTANCE_LOW
                )

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(channel)
        }
    }

    // =============================================
    // BIND
    // =============================================
    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // =============================================
    // DESTROY
    // =============================================
    override fun onDestroy() {

        try {

            isRunning = false

            processingImage.set(false)

            imageReader?.setOnImageAvailableListener(
                null,
                null
            )

            imageReader?.close()

            virtualDisplay?.release()

            mediaProjection?.stop()

            serviceScope.cancel()

        } catch (e: Exception) {

            Log.e(
                TAG,
                "DESTROY ERROR",
                e
            )
        }

        Log.e(
            TAG,
            "❌ ScreenCaptureService destroyed"
        )

        super.onDestroy()
    }
}