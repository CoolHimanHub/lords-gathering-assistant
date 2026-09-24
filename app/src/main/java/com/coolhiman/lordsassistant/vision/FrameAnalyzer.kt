package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
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
    const val MAX_DIMENSION = 1024

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
    // Keep ML Kit submission and Task callbacks on separate executors. If
    // process() performs synchronous model/native work on a real device, that
    // must not starve the executor responsible for completion callbacks.
    private val submissionExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    // Scanner/action/diagnostic handling is deliberately isolated from both
    // Android's main looper and ML Kit's completion executor. A slow UI queue
    // must not turn a completed OCR task into a multi-second capture timeout.
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val inFlight = AtomicBoolean(false)
    @Volatile private var stage = "IDLE"
    @Volatile private var lastFailure: String? = null

    fun isProcessing(): Boolean = inFlight.get()

    fun diagnosticState(): String {
        val failure = lastFailure?.let { " • " + it } ?: ""
        return stage + failure
    }

    fun analyze(bitmap: Bitmap, defaultKingdom: Int, callback: (FrameAnalysis) -> Unit): Boolean {
        if (!inFlight.compareAndSet(false, true)) {
            stage = "BUSY"
            return false
        }
        val startedAt = System.currentTimeMillis()
        val ocrBitmap = try {
            OcrBitmapPreprocessor.prepare(bitmap)
        } catch (_: Throwable) {
            bitmap
        }
        val delivered = AtomicBoolean(false)

        fun finish() {
            inFlight.set(false)
        }

        fun deliver(result: FrameAnalysis) {
            if (!delivered.compareAndSet(false, true)) return
            try {
                analysisExecutor.execute {
                    callback(result)
                }
            } catch (error: Throwable) {
                // The service is already fail-closed if delivery cannot be
                // scheduled; do not allow an executor rejection to leave the
                // analyzer's in-flight flag permanently asserted.
                finish()
            }
        }

        try {
            submissionExecutor.execute {
                try {
                    stage = "PREPARED"
                    lastFailure = null
                    stage = "SUBMITTING"
                    val task = recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
                    stage = "SUBMITTED"
                    task
                        .addOnSuccessListener(callbackExecutor) { result ->
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
                            stage = "SUCCESS"
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
                        .addOnFailureListener(callbackExecutor) { error ->
                            stage = "FAILURE"
                            lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
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
                        .addOnCompleteListener(callbackExecutor) {
                            stage = "COMPLETE"
                            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                                ocrBitmap.recycle()
                            }
                            finish()
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
                } catch (error: Throwable) {
                    stage = "SUBMISSION_EXCEPTION"
                    lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
                    if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                        ocrBitmap.recycle()
                    }
                    finish()
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
        } catch (error: Throwable) {
            stage = "EXECUTOR_REJECTED"
            lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
            finish()
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
        return true
    }

    fun close() {
        submissionExecutor.shutdownNow()
        callbackExecutor.shutdownNow()
        analysisExecutor.shutdownNow()
        inFlight.set(false)
        recognizer.close()
    }
}
