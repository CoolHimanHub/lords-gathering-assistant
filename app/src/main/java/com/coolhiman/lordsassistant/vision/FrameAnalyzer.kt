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
    val textRegions: List<TextRegion> = emptyList()
)

class FrameAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(bitmap: Bitmap, defaultKingdom: Int, callback: (FrameAnalysis) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = OcrParser.normalize(result.text)
                val regions = result.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                    line.boundingBox?.let { TextRegion(RectF(it), GameTextClassifier.classify(line.text)) }
                }
                callback(
                    FrameAnalysis(
                        text,
                        OcrParser.parseCoordinate(text, defaultKingdom),
                        GameTextClassifier.classify(text),
                        regions
                    )
                )
            }
            .addOnFailureListener {
                callback(FrameAnalysis("", null, TextClassification(), emptyList()))
            }
    }

    fun close() = recognizer.close()
}
