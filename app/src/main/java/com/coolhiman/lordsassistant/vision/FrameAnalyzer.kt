package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FrameAnalysis(
    val text: String,
    val coordinate: WorldCoordinate?,
    val classification: TextClassification,
    val textRegions: List<TextRegion> = emptyList(),
    val popup: PopupState? = null,
    val ocrProcessingMs: Long = 0L
)

object OcrBitmapPreprocessor {
    const val MAX_DIMENSION = 1280

    fun prepare(source: Bitmap): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= MAX_DIMENSION) return source
        val scale = MAX_DIMENSION.toFloat() / largest.toFloat()
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }
}

class FrameAnalyzer {
    // Keep ML Kit submission away from ImageReader/UI's main-thread callback.
    // Some real devices perform bundled-model initialization synchronously on
    // the first process() call; blocking the capture thread can also starve the
    // watchdog and make an accepted frame appear permanently stuck.
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val callbackHandler = Handler(Looper.getMainLooper())
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(bitmap: Bitmap, defaultKingdom: Int, callback: (FrameAnalysis) -> Unit) {
        val startedAt = System.currentTimeMillis()
        val ocrBitmap = try {
            OcrBitmapPreprocessor.prepare(bitmap)
        } catch (_: Throwable) {
            bitmap
        }
        val delivered = AtomicBoolean(false)

        fun deliver(result: FrameAnalysis) {
            if (delivered.compareAndSet(false, true)) callbackHandler.post { callback(result) }
        }

        try {
            ocrExecutor.execute {
                try {
                    recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
                        .addOnSuccessListener(ocrExecutor) { result ->
                            val text = OcrParser.normalize(result.text)
                            val regions = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                                line.boundingBox?.let {
                                    TextRegion(
                                        RectF(it),
                                        GameTextClassifier.classify(line.text),
                                        OcrParser.normalize(line.text)
                                    )
                                }
                            }
                            deliver(
                                FrameAnalysis(
                                    text = text,
                                    coordinate = OcrParser.parseCoordinate(text, defaultKingdom),
                                    classification = GameTextClassifier.classify(text),
                                    textRegions = regions,
                                    popup = PopupStateParser.parse(text, defaultKingdom),
                                    ocrProcessingMs = System.currentTimeMillis() - startedAt
                                )
                            )
                        }
                        .addOnFailureListener(ocrExecutor) {
                            deliver(
                                FrameAnalysis(
                                    "",
                                    null,
                                    TextClassification(),
                                    emptyList(),
                                    null,
                                    System.currentTimeMillis() - startedAt
                                )
                            )
                        }
                        .addOnCompleteListener(ocrExecutor) {
                            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                                ocrBitmap.recycle()
                            }
                            deliver(
                                FrameAnalysis(
                                    "",
                                    null,
                                    TextClassification(),
                                    emptyList(),
                                    null,
                                    System.currentTimeMillis() - startedAt
                                )
                            )
                        }
                } catch (_: Throwable) {
                    if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                        ocrBitmap.recycle()
                    }
                    deliver(
                        FrameAnalysis(
                            "",
                            null,
                            TextClassification(),
                            emptyList(),
                            null,
                            System.currentTimeMillis() - startedAt
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
            deliver(
                FrameAnalysis(
                    "",
                    null,
                    TextClassification(),
                    emptyList(),
                    null,
                    System.currentTimeMillis() - startedAt
                )
            )
        }
    }

    fun close() {
        ocrExecutor.shutdownNow()
        callbackHandler.removeCallbacksAndMessages(null)
        recognizer.close()
    }
}
