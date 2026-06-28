package com.parentalcontrol.childapp.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

object ContentClassifier2 {

    private const val TAG = "ContentClassifier"

    // -------------------------
    // Keyword fallbacks (existing)
    // -------------------------
    private val adultKeywords = listOf("porn", "sex", "xxx", "hentai", "adult", "adult-site", "nsfw")
    private val gamblingKeywords = listOf("bet", "casino", "gambling", "stake")
    private val violenceKeywords = listOf("kill", "shoot", "gun", "fight", "murder")
    private val drugsKeywords = listOf("cocaine", "meth", "weed", "marijuana", "drug")

    // -------------------------
    // TFLite model config (image classification)
    // -------------------------
    // Place your TFLite model in assets/ as nsfw_model.tflite
    private const val MODEL_FILE = "nsfw_model.tflite"
    private const val MODEL_INPUT_SIZE = 224 // set to your model's expected size
    private const val MODEL_PIXEL_SIZE = 3
    private const val MODEL_BYTE_SIZE_PER_CHANNEL = 4 // float32

    // Interpreter singleton must be initialized with a Context
    @Volatile
    private var tflite: Interpreter? = null
    private val initLock = Any()

    // A simple single-thread executor used for inference so it doesn't block UI thread
    private val inferExecutor = Executors.newSingleThreadExecutor()

    data class RiskResult(
        val score: Int,
        val level: String,
        val categories: Set<String>
    )

    suspend fun analyzeCombinedRisk(
        context: Context,
        text: String,
        bitmap: Bitmap?
    ): RiskResult {

        // 1️⃣ Analyze text risk
        val textResult = analyzeTextRisk(text)
        var finalScore = textResult.score
        val finalCategories = textResult.categories.toMutableSet()

        // 2️⃣ Analyze image risk (if image exists)
        var imageScore = 0f
        if (bitmap != null) {
            imageScore = imageExplicitScore(context, bitmap)
        }

        // 3️⃣ Escalation logic
        if (imageScore >= 0.6f) {
            finalCategories.add("explicit_image")
            finalScore = maxOf(finalScore, 85)
        }

        // 4️⃣ SUPER escalation:
        if ("adult" in finalCategories && imageScore >= 0.7f) {
            finalScore = 100
        }

        val level = when {
            finalScore >= 90 -> "critical"
            finalScore >= 75 -> "high"
            finalScore >= 50 -> "medium"
            else -> "low"
        }

        return RiskResult(
            score = finalScore,
            level = level,
            categories = finalCategories
        )
    }

    // -------------------------
    // Public text / URL checks (fast)
    // -------------------------
    fun urlFlag(url: String): String? {
        val lower = url.lowercase()
        return when {
            adultKeywords.any { lower.contains(it) } -> "adult"
            gamblingKeywords.any { lower.contains(it) } -> "gambling"
            violenceKeywords.any { lower.contains(it) } -> "violence"
            drugsKeywords.any { lower.contains(it) } -> "drugs"
            else -> null
        }
    }

    fun textFlags(text: String): Set<String> {
        val lower = text.lowercase()
        val flagged = mutableSetOf<String>()
        if (adultKeywords.any { lower.contains(it) }) flagged.add("adult")
        if (gamblingKeywords.any { lower.contains(it) }) flagged.add("gambling")
        if (violenceKeywords.any { lower.contains(it) }) flagged.add("violence")
        if (drugsKeywords.any { lower.contains(it) }) flagged.add("drugs")
        return flagged
    }

    // -------------------------
    // Initialization (call once, e.g. app start)
    // -------------------------
    fun initialize(context: Context) {
        if (tflite != null) return
        synchronized(initLock) {
            if (tflite != null) return
            try {
                val model = FileUtil.loadMappedFile(context, MODEL_FILE)
                val opts = Interpreter.Options()
                // opts.addDelegate(GpuDelegate()) // optional: add GPU delegate if available
                tflite = Interpreter(model, opts)
                Log.i(TAG, "TFLite model loaded")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load TFLite model: ${e.message}")
                tflite = null
            }
        }
    }

    // -------------------------
    // Image inference: returns probability [0..1] that image is EXPLICIT (higher => more likely)
    // This is suspending; performs work off main thread.
    // -------------------------
    suspend fun imageExplicitScore(context: Context, bitmap: Bitmap): Float = withContext(Dispatchers.Default) {
        if (tflite == null) {
            initialize(context)
            if (tflite == null) {
                // model missing; return 0.0 (no explicit)
                return@withContext 0.0f
            }
        }

        // Preprocess: resize & normalize depending on model expectation
        val inputSize = MODEL_INPUT_SIZE
        val resized = resizeBitmap(bitmap, inputSize, inputSize)

        // Create a TensorImage and ByteBuffer
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resized)

        // If model expects normalized floats [-1..1] or [0..1], adjust accordingly
        // Here we normalize to [0,1]
        val imgBuffer = tensorImage.buffer.rewind() // ByteBuffer of floats in order

        // Prepare output buffer
        // many NSFW models output a single float prob or array [2] -> [sfw, nsfw]
        val outputShape = intArrayOf(1, 1) // default; override below if needed

        // We'll try two common output shapes: single float or array[1][2]
        val tfliteLocal = tflite!!

        // Try to infer shape: read input signature / output tensor info isn't available reliably via support lib,
        // so we'll try common patterns.
        // Create a float buffer for output
        val outputBuffer1 = TensorBuffer.createFixedSize(intArrayOf(1, 1), DataType.FLOAT32)
        val outputBuffer2 = TensorBuffer.createFixedSize(intArrayOf(1, 2), DataType.FLOAT32)

        // Run inference - try single-float first, fallback to two-element output
        return@withContext try {
            // Primary try (single float output)
            tfliteLocal.run(imgBuffer, outputBuffer1.buffer.rewind())
            val score = outputBuffer1.floatArray.firstOrNull() ?: 0f
            score.coerceIn(0f, 1f)
        } catch (e1: Exception) {
            try {
                // fallback: two-output probs [sfw, nsfw]
                tfliteLocal.run(imgBuffer, outputBuffer2.buffer.rewind())
                val arr = outputBuffer2.floatArray
                // assume arr[1] is nsfw probability
                val score = if (arr.size >= 2) arr[1] else arr.firstOrNull() ?: 0f
                score.coerceIn(0f, 1f)
            } catch (e2: Exception) {
                Log.e(TAG, "Inference failed: ${e2.message}")
                0f
            }
        }
    }
    fun analyzeTextRisk(text: String): RiskResult {

        val categories = textFlags(text)
        var score = 10

        if ("adult" in categories) score = maxOf(score, 90)
        if ("violence" in categories) score = maxOf(score, 80)
        if ("drugs" in categories) score = maxOf(score, 75)
        if ("gambling" in categories) score = maxOf(score, 60)

        val level = when {
            score >= 90 -> "critical"
            score >= 75 -> "high"
            score >= 50 -> "medium"
            else -> "low"
        }

        return RiskResult(score, level, categories)
    }

    // -------------------------
    // Convenience: synchronous wrapper (not recommended on UI thread)
    // -------------------------
    fun imageIsExplicitBlocking(context: Context, bitmap: Bitmap, threshold: Float = 0.6f): Boolean {
        // NOTE: Do NOT call this on UI thread. Use coroutine version instead.
        initialize(context)
        if (tflite == null) return false
        val resized = resizeBitmap(bitmap, MODEL_INPUT_SIZE, MODEL_INPUT_SIZE)
        val tensorImage = TensorImage(DataType.FLOAT32)
        tensorImage.load(resized)
        val inBuf = tensorImage.buffer.rewind()
        val out = TensorBuffer.createFixedSize(intArrayOf(1, 1), DataType.FLOAT32)
        return try {
            tflite!!.run(inBuf, out.buffer.rewind())
            val score = out.floatArray.firstOrNull() ?: 0f
            score >= threshold
        } catch (e: Exception) {
            Log.e(TAG, "Blocking inference failed: ${e.message}")
            false
        }
    }

    // -------------------------
    // Utils
    // -------------------------
    private fun resizeBitmap(source: Bitmap, width: Int, height: Int): Bitmap {
        if (source.width == width && source.height == height) return source
        val matrix = Matrix()
        val sx = width.toFloat() / source.width
        val sy = height.toFloat() / source.height
        matrix.postScale(sx, sy)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }
}
