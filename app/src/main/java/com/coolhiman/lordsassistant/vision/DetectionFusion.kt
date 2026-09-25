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
        coordinateResolver: (Float, Float) -> WorldCoordinate? = { _, _ -> null },
        coordinateEvidenceResolver: ((Float, Float) -> CoordinateResolution?)? = null
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
            val popupMatches = popupState?.isPopup == true &&
                popupState.coordinate != null && coordinate == popupState.coordinate

            if (popupMatches) {
                coordinateConfidence = CoordinateConfidence.observed(
                    calibrationUsable = coordinateConfidence.calibrationUsable,
                    cameraStable = coordinateConfidence.cameraStable,
                    residualPx = coordinateConfidence.residualPx
                )
                classification = classification.copy(
                    kind = popupState.kind ?: classification.kind,
                    resource = popupState.resource ?: classification.resource,
                    monsterName = popupState.monsterName ?: classification.monsterName,
                    level = popupState.level ?: classification.level,
                    quantity = popupState.quantity ?: classification.quantity,
                    occupied = popupState.occupied ?: classification.occupied,
                    incomingTroops = popupState.incomingTroops ?: classification.incomingTroops
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
