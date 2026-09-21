package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap

data class VisionPipelineResult(
    val detection: DetectionFrame,
    val fused: List<FusionCandidate>
)

class VisionPipeline(
    private val tileDetector: TemplateTileDetector,
    private val fusion: DetectionFusion
) {
    fun analyze(
        bitmap: Bitmap,
        templates: List<Pair<TileTemplate, Bitmap>>,
        textRegions: List<TextRegion> = emptyList(),
        marchSignals: List<MarchSignal> = emptyList(),
        popupState: PopupState? = null,
        coordinateResolver: (Float, Float) -> com.coolhiman.lordsassistant.model.WorldCoordinate? = { _, _ -> null }
    ): VisionPipelineResult {
        val detection = tileDetector.detect(bitmap, templates)
        val fused = fusion.fuse(
            frame = detection,
            textRegions = textRegions,
            marchSignals = marchSignals,
            popupState = popupState,
            coordinateResolver = coordinateResolver
        )
        return VisionPipelineResult(detection, fused)
    }
}
