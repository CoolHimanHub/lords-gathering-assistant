package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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
            if (delivered.compareAndSet(false, true)) callback(result)
        }

        try {
            recognizer.process(InputImage.fromBitmap(ocrBitmap, 0))
                .addOnSuccessListener { result ->
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
                .addOnFailureListener {
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
                .addOnCompleteListener {
                    if (ocrBitmap !== bitmap && !ocrBitmap.isRecycled) {
                        ocrBitmap.recycle()
                    }
                    // Defensive completion path: ML Kit should terminate through
                    // success/failure, but the capture pipeline must never keep
                    // a frame locked if a future implementation violates that.
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

    fun close() = recognizer.close()
}
