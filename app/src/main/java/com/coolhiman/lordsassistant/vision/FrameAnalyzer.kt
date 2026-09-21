package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class FrameAnalysis(
    val text: String,
    val coordinate: WorldCoordinate?,
    val classification: TextClassification,
    val textRegions: List<TextRegion> = emptyList(),
    val popup: PopupState? = null,
    val ocrProcessingMs: Long = 0L
)

class FrameAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(bitmap: Bitmap, defaultKingdom: Int, callback: (FrameAnalysis) -> Unit) {
        val startedAt = System.currentTimeMillis()
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = OcrParser.normalize(result.text)
                val regions = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { TextRegion(RectF(it), GameTextClassifier.classify(line.text), OcrParser.normalize(line.text)) }
                }
                callback(
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
                callback(FrameAnalysis("", null, TextClassification(), emptyList(), null, System.currentTimeMillis() - startedAt))
            }
    }

    fun close() = recognizer.close()
}
