package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

data class FrameAnalysis(val text: String, val coordinate: WorldCoordinate?)

class FrameAnalyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    fun analyze(bitmap: Bitmap, defaultKingdom: Int, callback: (FrameAnalysis) -> Unit) {
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                val text = OcrParser.normalize(result.text)
                callback(FrameAnalysis(text, OcrParser.parseCoordinate(text, defaultKingdom)))
            }
            .addOnFailureListener {
                callback(FrameAnalysis("", null))
            }
    }

    fun close() = recognizer.close()
}
