package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.hypot

data class FusionCandidate(
    val tile: DetectedTile,
    val classification: TextClassification,
    val coordinate: WorldCoordinate?,
    val occupied: Boolean?,
    val incomingTroops: Boolean?,
    val confidence: Double
)

class DetectionFusion(
    private val maxTextDistancePx: Float = 180f,
    private val maxMarchDistancePx: Float = 110f
) {
    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        coordinateResolver: (Float, Float) -> WorldCoordinate?,
        popupState: PopupState? = null
    ): List<FusionCandidate> {
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

            val march = marchSignals.minByOrNull {
                hypot((it.x - tile.centerX).toDouble(), (it.y - tile.centerY).toDouble())
            }?.takeIf {
                hypot((it.x - tile.centerX).toDouble(), (it.y - tile.centerY).toDouble()) <= maxMarchDistancePx
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
                classification.incomingTroops ?: march?.let { true }
            }
            val occupied = if (popupMatches && popupState?.occupied != null) {
                popupState.occupied
            } else {
                if (incoming == true) true else classification.occupied
            }

            val evidence = listOf(
                tile.confidence,
                if (text != null) 0.90 else 0.0,
                if (march != null) march.confidence.toDouble() else 0.0,
                if (popupMatches) 0.98 else 0.0
            ).filter { it > 0.0 }
            val confidence = evidence.average().coerceIn(0.0, 1.0)

            FusionCandidate(tile, classification, coordinate, occupied, incoming, confidence)
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
    val classification: TextClassification
)
