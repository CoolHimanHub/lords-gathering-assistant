package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.coolhiman.lordsassistant.map.CoordinateResolution

data class VisionPipelineResult(
    val detection: DetectionFrame,
    val fused: List<FusionCandidate>,
    val badgeDetections: Int = 0,
    val openCvReady: Boolean = false
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
        coordinateEvidenceResolver: ((Float, Float) -> CoordinateResolution?)? = null,
        coordinateResolver: (Float, Float) -> com.coolhiman.lordsassistant.model.WorldCoordinate? = { _, _ -> null }
    ): VisionPipelineResult {
        val detection = tileDetector.detect(bitmap, templates)
        val levelEnrichedDetection = enrichBadgeLevels(detection, textRegions)
        val fused = fusion.fuse(
            frame = levelEnrichedDetection,
            textRegions = textRegions,
            marchSignals = marchSignals,
            popupState = popupState,
            coordinateResolver = coordinateResolver,
            coordinateEvidenceResolver = coordinateEvidenceResolver
        )
        return VisionPipelineResult(
            detection = levelEnrichedDetection,
            fused = fused,
            badgeDetections = tileDetector.lastBadgeDetections,
            openCvReady = OpenCvRuntime.isLoaded()
        )
    }
    private fun enrichBadgeLevels(detection: DetectionFrame, textRegions: List<TextRegion>): DetectionFrame {
        if (detection.tiles.none { it.source == DetectionSource.LEVEL_BADGE } || textRegions.isEmpty()) return detection
        val enriched = detection.tiles.map { tile ->
            if (tile.source != DetectionSource.LEVEL_BADGE || tile.level != null) return@map tile
            val level = textRegions.asSequence()
                .filter { it.classification.level in 1..5 }
                .map { it.classification.level!! to distance(tile.bounds, it.bounds) }
                .filter { it.second <= 55f }
                .minByOrNull { it.second }
                ?.first
            tile.copy(level = level)
        }
        return detection.copy(tiles = enriched)
    }

    private fun distance(a: RectF, b: RectF): Float = kotlin.math.hypot(
        (a.centerX() - b.centerX()).toDouble(),
        (a.centerY() - b.centerY()).toDouble()
    ).toFloat()

}
