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

    /**
     * The Lords Mobile map coordinate HUD is a small, high-value OCR region
     * near the upper-middle of the landscape viewport. At 1024px full-frame OCR
     * resolution it can be too small for ML Kit even when the player can read
     * it clearly. Enlarge only that ROI for a fallback pass; the primary full
     * frame remains the source of semantic/tile OCR regions.
     */
    fun prepareCoordinateHud(source: Bitmap): Bitmap? {
        if (source.width < 32 || source.height < 32) return null

        val left = (source.width * 0.35f).toInt().coerceAtLeast(0)
        val top = 0
        val right = (source.width * 0.75f).toInt().coerceAtMost(source.width)
        val bottom = (source.height * 0.28f).toInt().coerceAtMost(source.height)
        if (right <= left || bottom <= top) return null

        val crop = Bitmap.createBitmap(source, left, top, right - left, bottom - top)
        val enlarged = Bitmap.createScaledBitmap(
            crop,
            (crop.width * 2).coerceAtLeast(1),
            (crop.height * 2).coerceAtLeast(1),
            true
        )
        if (enlarged !== crop && !crop.isRecycled) crop.recycle()
        return enlarged
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
                finish()
            }
        }

        fun cleanupCoordinateBitmap(coordinateBitmap: Bitmap?) {
            if (coordinateBitmap != null && !coordinateBitmap.isRecycled) {
                coordinateBitmap.recycle()
            }
        }

        fun complete(
            result: FrameAnalysis,
            coordinateBitmap: Bitmap? = null
        ) {
            cleanupCoordinateBitmap(coordinateBitmap)
            if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                ocrBitmap.recycle()
            }
            finish()
            deliver(result)
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
                            val coordinate = OcrParser.parseHudCoordinate(text, regions, ocrBitmap.width, ocrBitmap.height, defaultKingdom)

                            if (coordinate != null) {
                                stage = "SUCCESS"
                                complete(
                                    FrameAnalysis(
                                        text = text,
                                        coordinate = coordinate,
                                        classification = GameTextClassifier.classify(text),
                                        textRegions = regions,
                                        popup = PopupStateParser.parse(text, defaultKingdom),
                                        ocrProcessingMs = System.currentTimeMillis() - startedAt
                                    )
                                )
                                return@addOnSuccessListener
                            }

                            // Full-frame OCR produced useful semantic evidence but
                            // missed the tiny coordinate HUD. Run a narrow enlarged
                            // fallback only in that case. This does not replace or
                            // alter the primary text regions used by map fusion.
                            val coordinateBitmap = try {
                                OcrBitmapPreprocessor.prepareCoordinateHud(ocrBitmap)
                            } catch (_: Throwable) {
                                null
                            }

                            if (coordinateBitmap == null) {
                                stage = "SUCCESS_NO_COORDINATE"
                                complete(
                                    FrameAnalysis(
                                        text = text,
                                        coordinate = null,
                                        classification = GameTextClassifier.classify(text),
                                        textRegions = regions,
                                        popup = PopupStateParser.parse(text, defaultKingdom),
                                        ocrProcessingMs = System.currentTimeMillis() - startedAt
                                    )
                                )
                                return@addOnSuccessListener
                            }

                            stage = "COORDINATE_FALLBACK_SUBMITTING"
                            try {
                                recognizer.process(InputImage.fromBitmap(coordinateBitmap, 0))
                                    .addOnSuccessListener(callbackExecutor) { coordinateResult ->
                                        val coordinateText = OcrParser.normalize(coordinateResult.text)
                                        val fallbackRegions = coordinateResult.textBlocks
                                            .flatMap { it.lines }
                                            .mapNotNull { line ->
                                                line.boundingBox?.let {
                                                    TextRegion(
                                                        RectF(it),
                                                        GameTextClassifier.classify(line.text),
                                                        OcrParser.normalize(line.text)
                                                    )
                                                }
                                            }
                                        val fallbackCoordinate = OcrParser.parseCoordinate(
                                            coordinateText,
                                            fallbackRegions,
                                            defaultKingdom
                                        )
                                        stage = if (fallbackCoordinate != null) {
                                            "COORDINATE_FALLBACK_SUCCESS"
                                        } else {
                                            "COORDINATE_FALLBACK_NO_COORDINATE"
                                        }
                                        complete(
                                            FrameAnalysis(
                                                text = text,
                                                coordinate = fallbackCoordinate,
                                                classification = GameTextClassifier.classify(text),
                                                textRegions = regions,
                                                popup = PopupStateParser.parse(text, defaultKingdom),
                                                ocrProcessingMs = System.currentTimeMillis() - startedAt
                                            ),
                                            coordinateBitmap
                                        )
                                    }
                                    .addOnFailureListener(callbackExecutor) { error ->
                                        stage = "COORDINATE_FALLBACK_FAILURE"
                                        lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
                                        complete(
                                            FrameAnalysis(
                                                text = text,
                                                coordinate = null,
                                                classification = GameTextClassifier.classify(text),
                                                textRegions = regions,
                                                popup = PopupStateParser.parse(text, defaultKingdom),
                                                ocrProcessingMs = System.currentTimeMillis() - startedAt
                                            ),
                                            coordinateBitmap
                                        )
                                    }
                            } catch (error: Throwable) {
                                stage = "COORDINATE_FALLBACK_EXCEPTION"
                                lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
                                complete(
                                    FrameAnalysis(
                                        text = text,
                                        coordinate = null,
                                        classification = GameTextClassifier.classify(text),
                                        textRegions = regions,
                                        popup = PopupStateParser.parse(text, defaultKingdom),
                                        ocrProcessingMs = System.currentTimeMillis() - startedAt
                                    ),
                                    coordinateBitmap
                                )
                            }
                        }
                        .addOnFailureListener(callbackExecutor) { error ->
                            stage = "FAILURE"
                            lastFailure = (error.message ?: error.javaClass.simpleName).take(180)
                            complete(
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
                    complete(
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
            complete(
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
