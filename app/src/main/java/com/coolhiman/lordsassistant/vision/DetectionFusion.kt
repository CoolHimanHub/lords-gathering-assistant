package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
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
        coordinateResolver: (Float, Float) -> WorldCoordinate?,
        popupState: PopupState?
    ): List<FusionCandidate> = fuse(
        frame = frame,
        textRegions = textRegions,
        marchSignals = marchSignals,
        popupState = popupState,
        coordinateResolver = coordinateResolver
    )

    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        popupState: PopupState? = null,
        coordinateResolver: (Float, Float) -> WorldCoordinate? = { _, _ -> null }
    ): List<FusionCandidate> {
    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        coordinateResolver: (Float, Float) -> WorldCoordinate?
    ): List<FusionCandidate> = fuse(
        frame = frame,
        textRegions = textRegions,
        marchSignals = marchSignals,
        coordinateResolver = coordinateResolver,
        popupState = null
    )

        return frame.tiles.map { tile ->
            val text = textRegions.minByOrNull { distance(tile.bounds, it.bounds) }
                ?.takeIf { distance(tile.bounds, it.bounds) <= maxTextDistancePx }

            var classification = text?.classification ?: TextClassification(
                kind = when (tile.tileClass) {
                    TileClass.RESOURCE -> TargetKind.RESOURCE
                    TileClass.MONSTER -> TargetKind.MONSTER
                },
                level = tile.level
            )

            val nearbyMarches = marchSignals.mapNotNull { signal ->
                val distance = hypot((signal.x - tile.centerX).toDouble(), (signal.y - tile.centerY).toDouble()).toFloat()
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

            val coordinate = coordinateResolver(tile.centerX, tile.centerY)
            val popupMatches = popupState?.isPopup == true &&
                popupState.coordinate != null && coordinate == popupState.coordinate

            if (popupMatches) {
                classification = classification.copy(
                    kind = popupState.kind ?: classification.kind,
                    resource = popupState.resource ?: classification.resource,
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
                if (incoming == true) true else classification.occupied
            }

            val evidence = listOf(
                tile.confidence,
                if (text != null) 0.90 else 0.0,
                if (associatedMarch != null) associatedMarch.confidence.toDouble() else 0.0,
                if (popupMatches) 0.98 else 0.0
            ).filter { it > 0.0 }
            val confidence = evidence.average().coerceIn(0.0, 1.0)

            FusionCandidate(tile, classification, coordinate, occupied, incoming, confidence, marchAssociation)
        }
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
