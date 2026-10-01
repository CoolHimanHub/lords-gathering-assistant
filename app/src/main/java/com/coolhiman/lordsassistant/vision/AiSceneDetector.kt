package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.objects.defaults.PredefinedCategory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class AiSceneObject(
    val bounds: RectF,
    val category: String?,
    val confidence: Double,
    val trackingId: Int?
)

data class AiSceneResult(
    val objects: List<AiSceneObject> = emptyList(),
    val processingMs: Long = 0L,
    val available: Boolean = false,
    val diagnostic: String = "AI IDLE"
)

class AiSceneDetector(context: Context) : AutoCloseable {
    companion object {
        private const val MIN_INTERVAL_MS = 900L
        private const val MAX_WAIT_MS = 350L
    }

    private val detector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder()
            .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableMultipleObjects()
            .enableClassification()
            .build()
    )
    private val inFlight = AtomicBoolean(false)
    @Volatile private var lastRunMs = 0L
    @Volatile private var lastResult = AiSceneResult()

    fun analyze(bitmap: Bitmap): AiSceneResult {
        if (bitmap.isRecycled) return lastResult.copy(diagnostic = "AI BITMAP INVALID")
        val now = System.currentTimeMillis()
        if (now - lastRunMs < MIN_INTERVAL_MS) return lastResult
        if (!inFlight.compareAndSet(false, true)) return lastResult
        lastRunMs = now
        val started = now
        return try {
            val objects = Tasks.await(
                detector.process(InputImage.fromBitmap(bitmap, 0)),
                MAX_WAIT_MS,
                TimeUnit.MILLISECONDS
            )
            val mapped = objects.mapNotNull(::mapObject)
            val taxonomy = GameSceneTaxonomy.summarize(mapped)
            AiSceneResult(mapped, System.currentTimeMillis() - started, true, "AI OK • objects=${mapped.size} • game=$taxonomy").also { lastResult = it }
        } catch (error: Throwable) {
            lastResult.copy(available = false, diagnostic = "AI FALLBACK • " + (error.message ?: error.javaClass.simpleName).take(100))
        } finally {
            inFlight.set(false)
        }
    }

    private fun mapObject(item: DetectedObject): AiSceneObject? {
        val best = item.labels.maxByOrNull { it.confidence }
        val category = when (best?.text) {
            PredefinedCategory.FOOD -> "FOOD"
            PredefinedCategory.PLANT -> "PLANT"
            PredefinedCategory.PLACE -> "PLACE"
            PredefinedCategory.HOME_GOOD -> "HOME_GOOD"
            PredefinedCategory.FASHION_GOOD -> "FASHION_GOOD"
            else -> best?.text
        }
        val confidence = best?.confidence?.toDouble() ?: 0.0
        if (category == null && confidence <= 0.0) return null
        return AiSceneObject(RectF(item.boundingBox), category, confidence, item.trackingId)
    }

    override fun close() { detector.close() }
}