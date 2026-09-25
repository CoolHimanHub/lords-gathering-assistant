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
    val marchAssociation: MarchAssociationDiagnostics = MarchAssociationDiagnostics(MarchAssociationStatus.NO_MARCH)
)

class DetectionFusion(
    private val maxTextDistancePx: Float = 180f,
    private val maxMarchDistancePx: Float = 75f
) {
    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        popupState: PopupState? = null,
        coordinateEvidenceResolver: ((Float, Float) -> CoordinateResolution?)? = null,
        coordinateResolver: (Float, Float) -> WorldCoordinate? = { _, _ -> null }
    ): List<FusionCandidate> {
        return frame.tiles.map { tile ->
            val text = selectTextForTile(tile, textRegions)
            val textClassification = text?.classification
            var classification = textClassification ?: TextClassification(
                kind = when (tile.tileClass) {
                    TileClass.RESOURCE -> TargetKind.RESOURCE
                    TileClass.MONSTER -> TargetKind.MONSTER
                },
                level = tile.level
            )

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
            val popup = popupState
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
                val confirmedPopup = popupState ?: return@mapNotNull null
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

            val evidence = listOf(
                tile.confidence,
                if (text != null) 0.90 else 0.0,
                if (associatedMarch != null) associatedMarch.confidence.toDouble() else 0.0,
                if (popupMatches) 0.98 else 0.0
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
                marchAssociation = marchAssociation
            )
        }
    }

    /**
     * Popup K/X/Y is global OCR evidence, so a coordinate match alone is not
     * sufficient when two detected tiles collapse onto the same rounded world
     * coordinate. Require a unique semantic match among all tiles that resolve
     * to that coordinate; otherwise leave every candidate calibrated-only.
     */
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
            val detected = selectTextForTile(other, textRegions)?.classification
            val kind = detected?.kind ?: when (other.tileClass) {
                TileClass.RESOURCE -> TargetKind.RESOURCE
                TileClass.MONSTER -> TargetKind.MONSTER
            }
            val kindMatch = popup.kind == null || popup.kind == kind
            val level = detected?.level ?: other.level
            val levelMatch = popup.level == null || level == null || popup.level == level
            val resourceMatch = popup.resource == null || detected?.resource == null || popup.resource == detected.resource
            val monsterMatch = popup.monsterName == null || detected?.monsterName == null ||
                popup.monsterName.equals(detected.monsterName, ignoreCase = true)
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
            .minByOrNull { it.second }

        if (compatible != null) return compatible.first

        // Structure/UI labels may be very close to a badge, but must never
        // override target semantics at the broader OCR association radius.
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
