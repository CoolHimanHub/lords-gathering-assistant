package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap

/**
 * V57.3: single-pass resource enrichment.
 *
 * ScreenAnalyzer already classifies the visual region. This pipeline is kept
 * as a defensive second pass for callers that supply generic detections. It
 * must never increase confidence or turn an uncertain classification into a
 * valid gather candidate.
 */
class ResourceDetectionPipeline(
    private val classifier: ResourceTileClassifier = ResourceTileClassifier()
) {
    fun enrich(
        bitmap: Bitmap,
        detections: List<ScreenAnalyzer.RssDetection>
    ): List<ScreenAnalyzer.RssDetection> = detections.map { d ->
        val r = classifier.classify(bitmap, d.centerX, d.centerY)
        val type = when (r.type) {
            ResourceTileClassifier.Type.FOOD -> "food"
            ResourceTileClassifier.Type.TIMBER -> "timber"
            ResourceTileClassifier.Type.STONE -> "stone"
            ResourceTileClassifier.Type.ORE -> "ore"
            ResourceTileClassifier.Type.GOLD -> "gold"
            ResourceTileClassifier.Type.UNKNOWN -> "unknown"
        }
        val combinedConfidence = if (type == "unknown") 0 else minOf(d.confidence, r.confidence)
        d.copy(type = type, confidence = combinedConfidence, dominantColor = r.redEvidence)
    }
}
