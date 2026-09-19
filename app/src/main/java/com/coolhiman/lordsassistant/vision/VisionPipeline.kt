package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import com.coolhiman.lordsassistant.model.WorldCoordinate

data class VisionPipelineResult(
    val detection: DetectionFrame,
    val fused: List<FusionCandidate>,
    val coordinate: WorldCoordinate?
)

/**
 * V0.4 pipeline coordinator. It is intentionally side-effect free so it can
 * be exercised from screenshots before connecting it to live capture.
 */
class VisionPipeline(
    private val tileDetector: TemplateTileDetector,
    private val fusion: DetectionFusion
) {
    fun analyze(
        bitmap: Bitmap,
        templates: List<Pair<TileTemplate, Bitmap>>,
        textRegions: List<TextRegion> = emptyList(),
        marchSignals: List<MarchSignal> = emptyList(),
        coordinateResolver: (Float, Float) -> WorldCoordinate? = { _, _ -> null }
    ): VisionPipelineResult {
        val detection = tileDetector.detect(bitmap, templates)
        val fused = fusion.fuse(detection, textRegions, marchSignals, coordinateResolver)
        return VisionPipelineResult(detection, fused, null)
    }
}
