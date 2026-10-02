package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.coolhiman.lordsassistant.map.CoordinateResolution

data class VisionPipelineResult(
    val detection: DetectionFrame,
    val fused: List<FusionCandidate>,
    val badgeDetections: Int = 0,
    val openCvReady: Boolean = false,
    val aiScene: AiSceneResult = AiSceneResult(),
    val gameSceneHypotheses: List<GameSceneHypothesis> = emptyList(),
    /** Optional Lords Mobile-specific model output; never coordinate/action authority. */
    val lordsMobileSceneHypotheses: List<LordsMobileObjectHypothesis> = emptyList()
)

class VisionPipeline(
    private val tileDetector: TemplateTileDetector,
    private val fusion: DetectionFusion,
    private val aiSceneDetector: AiSceneDetector,
    /** Optional game-specific LiteRT/TFLite model. Generic ML Kit remains the fallback. */
    private val gameSceneModel: LordsMobileSceneModel? = null
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
        val aiScene = aiSceneDetector.analyze(bitmap)
        val gameSceneHypotheses = gameSceneModel?.detect(bitmap).orEmpty()
        val detection = tileDetector.detect(bitmap, templates)
        val levelEnrichedDetection = enrichBadgeLevels(detection, textRegions)
        val fused = fusion.fuseWithGameSceneHypotheses(
            frame = levelEnrichedDetection,
            textRegions = textRegions,
            marchSignals = marchSignals,
            aiSceneHypotheses = GameSceneTaxonomy.hypotheses(aiScene.objects),
            gameSceneHypotheses = gameSceneHypotheses,
            popupState = popupState,
            coordinateResolver = coordinateResolver,
            coordinateEvidenceResolver = coordinateEvidenceResolver
        )
        return VisionPipelineResult(
            detection = levelEnrichedDetection,
            fused = fused,
            badgeDetections = tileDetector.lastBadgeDetections,
            openCvReady = OpenCvRuntime.isLoaded(),
            aiScene = aiScene,
            gameSceneHypotheses = GameSceneTaxonomy.hypotheses(aiScene.objects),
            lordsMobileSceneHypotheses = gameSceneHypotheses
        )
    }
    private fun enrichBadgeLevels(detection: DetectionFrame, textRegions: List<TextRegion>): DetectionFrame {
        if (detection.tiles.none { it.source == DetectionSource.LEVEL_BADGE } || textRegions.isEmpty()) return detection
        val enriched = detection.tiles.map { tile ->
            if (tile.source != DetectionSource.LEVEL_BADGE || tile.level != null) return@map tile
            val level = LevelBadgeSemanticAssociator().associate(tile.bounds, textRegions)
            tile.copy(level = level)
        }
        return detection.copy(tiles = enriched)
    }

    fun close() = aiSceneDetector.close()

    private fun distance(a: RectF, b: RectF): Float = kotlin.math.hypot(
        (a.centerX() - b.centerX()).toDouble(),
        (a.centerY() - b.centerY()).toDouble()
    ).toFloat()

}
