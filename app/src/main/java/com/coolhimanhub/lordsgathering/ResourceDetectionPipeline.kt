package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap

/** V57: converts generic RSS badge detections into resource-aware candidates. */
class ResourceDetectionPipeline(
    private val classifier: ResourceTileClassifier = ResourceTileClassifier()
) {
    fun enrich(bitmap: Bitmap, detections: List<ScreenAnalyzer.RssDetection>): List<ScreenAnalyzer.RssDetection> =
        detections.map { d ->
            val r = classifier.classify(bitmap, d.centerX, d.centerY)
            d.copy(
                type = when (r.type) {
                    ResourceTileClassifier.Type.FOOD -> "food"
                    ResourceTileClassifier.Type.TIMBER -> "timber"
                    ResourceTileClassifier.Type.STONE -> "stone"
                    ResourceTileClassifier.Type.ORE -> "ore"
                    ResourceTileClassifier.Type.GOLD -> "gold"
                    ResourceTileClassifier.Type.UNKNOWN -> "unknown"
                },
                confidence = minOf(d.confidence, r.confidence.coerceAtLeast(0).let { if (it == 0) 0 else d.confidence }),
                dominantColor = r.redEvidence
            )
        }
}
