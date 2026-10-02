package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.map.CoordinateResolution
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.hypot

enum class MarchAssociationStatus { NO_MARCH, CLEAR_MARCH, AMBIGUOUS_MARCH }

data class MarchAssociationDiagnostics(
    val status: MarchAssociationStatus,
    val nearestDistancePx: Float? = null,
    val secondNearestDistancePx: Float? = null,
    val marginRatio: Float? = null
)

data class FusionCandidate(
    val tile: DetectedTile,
    val classification: TextClassification,
    val coordinate: WorldCoordinate?,
    val occupied: Boolean?,
    val incomingTroops: Boolean?,
    val confidence: Double,
    val coordinateConfidence: CoordinateConfidence = CoordinateConfidence.none(),
    val ignored: Boolean = false,
    val aiSceneSupported: Boolean = false,
    val marchAssociation: MarchAssociationDiagnostics = MarchAssociationDiagnostics(MarchAssociationStatus.NO_MARCH)
)

class DetectionFusion(
    private val maxTextDistancePx: Float = 180f,
    private val maxMarchDistancePx: Float = 75f,
    private val maxTextNearestToSecondRatio: Float = 0.72f,
    private val minAiSupportConfidence: Double = 0.55
) {
    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        aiSceneHypotheses: List<GameSceneHypothesis> = emptyList(),
        gameSceneHypotheses: List<LordsMobileObjectHypothesis> = emptyList(),
        popupState: PopupState? = null,
        coordinateEvidenceResolver: ((Float, Float) -> CoordinateResolution?)? = null,
        coordinateResolver: (Float, Float) -> WorldCoordinate? = { _, _ -> null }
    ): List<FusionCandidate> {
        return frame.tiles.map { tile ->
            val text = selectTextForTile(tile, textRegions)
            val textClassification = text?.classification
            val gameScene = gameSceneHypotheses
                .filter { it.confidence >= 0.60 && distance(tile.bounds, it.bounds) <= maxTextDistancePx }
                .maxByOrNull { it.confidence }
            var classification = textClassification ?: TextClassification(
                kind = when (tile.tileClass) {
                    TileClass.RESOURCE -> TargetKind.RESOURCE
                    TileClass.MONSTER -> TargetKind.MONSTER
                },
                level = tile.level
            )
            if (gameScene != null) {
                classification = classificationFromGameScene(gameScene, classification)
            }

            val nearbyMarches = marchSignals.mapNotNull { signal ->
                val tileCenterX = (tile.bounds.left + tile.bounds.right) / 2f
                val tileCenterY = (tile.bounds.top + tile.bounds.bottom) / 2f
                val distance = hypot((signal.x - tileCenterX).toDouble(), (signal.y - tileCenterY).toDouble()).toFloat()
                if (distance <= maxMarchDistancePx) signal to distance else null
            }.sortedBy { it.second }
            val nearestDistance = nearbyMarches.firstOrNull()?.second
            val secondNearestDistance = nearbyMarches.getOrNull(1)?.second
            val marginRatio = if (nearestDistance != null && secondNearestDistance != null && secondNearestDistance > 0f) {
                nearestDistance / secondNearestDistance
            } else null
            val clearlyAssociatedMarch = nearestDistance != null && (nearbyMarches.size == 1 ||
                nearestDistance <= secondNearestDistance!! * 0.72f)
            val associatedMarch = if (clearlyAssociatedMarch) nearbyMarches.firstOrNull()?.first else null
            val marchAssociation = when {
                nearbyMarches.isEmpty() -> MarchAssociationDiagnostics(MarchAssociationStatus.NO_MARCH)
                clearlyAssociatedMarch -> MarchAssociationDiagnostics(
                    MarchAssociationStatus.CLEAR_MARCH, nearestDistance, secondNearestDistance, marginRatio
                )
                else -> MarchAssociationDiagnostics(
                    MarchAssociationStatus.AMBIGUOUS_MARCH, nearestDistance, secondNearestDistance, marginRatio
                )
            }

            val resolution = coordinateEvidenceResolver?.invoke(tile.centerX, tile.centerY)
                ?: coordinateResolver(tile.centerX, tile.centerY)?.let {
                    CoordinateResolution(it, CoordinateConfidence.none())
                }
            val coordinate = resolution?.coordinate
            var coordinateConfidence = resolution?.confidence ?: CoordinateConfidence.none()
            val popupMatches = popupMatchesExactly(
                tile = tile,
                classification = classification,
                coordinate = coordinate,
                popupState = popupState,
                allTiles = frame.tiles,
                coordinateResolver = coordinateResolver,
                coordinateEvidenceResolver = coordinateEvidenceResolver,
                textRegions = textRegions
            )

            if (popupMatches) {
                val confirmedPopup = requireNotNull(popupState)
                coordinateConfidence = CoordinateConfidence.observed(
                    calibrationUsable = coordinateConfidence.calibrationUsable,
                    cameraStable = coordinateConfidence.cameraStable,
                    residualPx = coordinateConfidence.residualPx
                )
                classification = classification.copy(
                    kind = confirmedPopup.kind ?: classification.kind,
                    resource = confirmedPopup.resource ?: classification.resource,
                    monsterName = confirmedPopup.monsterName ?: classification.monsterName,
                    level = confirmedPopup.level ?: classification.level,
                    quantity = confirmedPopup.quantity ?: classification.quantity,
                    occupied = confirmedPopup.occupied ?: classification.occupied,
                    incomingTroops = confirmedPopup.incomingTroops ?: classification.incomingTroops
                )
            }

            val incoming = if (popupMatches && popupState?.incomingTroops != null) {
                popupState.incomingTroops
            } else {
                classification.incomingTroops ?: associatedMarch?.let { true }
            }
            val occupied = if (popupMatches && popupState?.occupied != null) {
                popupState.occupied
            } else {
                when {
                    incoming == true -> true
                    classification.occupied != null -> classification.occupied
                    tile.source == DetectionSource.LEVEL_BADGE -> false
                    else -> null
                }
            }

            val aiSupport = aiSceneHypotheses.any { hypothesis ->
                hypothesis.sceneClass == GameSceneClass.RESOURCE_CANDIDATE &&
                    hypothesis.confidence >= minAiSupportConfidence &&
                    distance(tile.bounds, hypothesis.bounds) <= maxTextDistancePx
            }

            val evidence = listOf(
                tile.confidence,
                if (text != null) 0.90 else 0.0,
                if (associatedMarch != null) associatedMarch.confidence.toDouble() else 0.0,
                if (popupMatches) 0.98 else 0.0,
                if (aiSupport) 0.65 else 0.0
            ).filter { it > 0.0 }
            val confidence = evidence.average().coerceIn(0.0, 1.0)

            FusionCandidate(
                tile = tile,
                classification = classification,
                coordinate = coordinate,
                occupied = occupied,
                incomingTroops = incoming,
                confidence = confidence,
                coordinateConfidence = coordinateConfidence,
                ignored = textClassification?.ignored == true,
                aiSceneSupported = aiSupport,
                marchAssociation = marchAssociation
            )
        }
    }

    private fun classificationFromGameScene(
        scene: LordsMobileObjectHypothesis,
        fallback: TextClassification
    ): TextClassification {
        val kind = when (scene.objectClass) {
            LordsMobileObjectClass.RESOURCE -> TargetKind.RESOURCE
            LordsMobileObjectClass.MONSTER -> TargetKind.MONSTER
            else -> fallback.kind
        }
        val resource = when (scene.resourceType) {
            LordsMobileResourceType.FOOD -> com.coolhiman.lordsassistant.model.ResourceType.FOOD
            LordsMobileResourceType.WOOD -> com.coolhiman.lordsassistant.model.ResourceType.WOOD
            LordsMobileResourceType.STONE -> com.coolhiman.lordsassistant.model.ResourceType.STONE
            LordsMobileResourceType.ORE -> com.coolhiman.lordsassistant.model.ResourceType.ORE
            LordsMobileResourceType.GOLD -> com.coolhiman.lordsassistant.model.ResourceType.GOLD
            else -> fallback.resource
        }
        return fallback.copy(
            kind = kind,
            resource = resource,
            level = scene.level ?: fallback.level
        )
    }

    private fun popupMatchesExactly(
        tile: DetectedTile,
        classification: TextClassification,
        coordinate: WorldCoordinate?,
        popupState: PopupState?,
        allTiles: List<DetectedTile>,
        coordinateResolver: (Float, Float) -> WorldCoordinate?,
        coordinateEvidenceResolver: ((Float, Float) -> CoordinateResolution?)?,
        textRegions: List<TextRegion>
    ): Boolean {
        if (popupState?.isPopup != true || popupState.coordinate == null || coordinate != popupState.coordinate) {
            return false
        }

        fun resolved(other: DetectedTile): WorldCoordinate? =
            coordinateEvidenceResolver?.invoke(other.centerX, other.centerY)?.coordinate
                ?: coordinateResolver(other.centerX, other.centerY)

        val popup = popupState
        fun semanticMatch(other: DetectedTile): Boolean {
            val selectedText = selectTextForTile(other, textRegions)
            val selectedTextOwnerCount = if (selectedText == null) 0 else {
                allTiles.count { candidate -> selectTextForTile(candidate, textRegions) == selectedText }
            }
            val detected = selectedText?.takeIf { selectedTextOwnerCount == 1 }?.classification
            val kind = detected?.kind ?: when (other.tileClass) {
                TileClass.RESOURCE -> TargetKind.RESOURCE
                TileClass.MONSTER -> TargetKind.MONSTER
            }
            val kindMatch = popup.kind == null || popup.kind == kind
            val level = detected?.level ?: other.level
            val levelMatch = popup.level == null || level == popup.level
            val resourceMatch = popup.resource == null || popup.resource == detected?.resource
            val monsterMatch = popup.monsterName == null || popup.monsterName.equals(detected?.monsterName, ignoreCase = true)
            return kindMatch && levelMatch && resourceMatch && monsterMatch
        }

        val matchingTiles = allTiles.count { other ->
            resolved(other) == popup.coordinate && semanticMatch(other)
        }

        return matchingTiles == 1 && semanticMatch(tile)
    }

    private fun selectTextForTile(tile: DetectedTile, textRegions: List<TextRegion>): TextRegion? {
        val compatible = textRegions
            .map { it to distance(tile.bounds, it.bounds) }
            .filter { (region, d) ->
                d <= maxTextDistancePx && when (tile.tileClass) {
                    TileClass.RESOURCE -> region.classification.kind == TargetKind.RESOURCE ||
                        region.classification.resource != null
                    TileClass.MONSTER -> region.classification.kind == TargetKind.MONSTER ||
                        region.classification.monsterName != null
                }
            }
            .sortedBy { it.second }

        val nearest = compatible.firstOrNull() ?: return selectIgnoredTextForTile(tile, textRegions)
        val second = compatible.getOrNull(1)
        if (second != null) {
            if (second.second <= 0f) return null
            if (nearest.second / second.second > maxTextNearestToSecondRatio) return null
        }

        return nearest.first
    }

    private fun selectIgnoredTextForTile(tile: DetectedTile, textRegions: List<TextRegion>): TextRegion? {
        return textRegions
            .map { it to distance(tile.bounds, it.bounds) }
            .filter { (region, d) -> region.classification.ignored && d <= 70f }
            .minByOrNull { it.second }
            ?.first
    }

    private fun distance(a: RectF, b: RectF): Float {
        return hypot(
            (a.centerX() - b.centerX()).toDouble(),
            (a.centerY() - b.centerY()).toDouble()
        ).toFloat()
    }
}

data class TextRegion(
    val bounds: RectF,
    val classification: TextClassification,
    val text: String = ""
)
