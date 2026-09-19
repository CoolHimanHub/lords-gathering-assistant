package com.coolhiman.lordsassistant.vision

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.hypot
import kotlin.math.min

data class FusionCandidate(
    val tile: DetectedTile,
    val classification: TextClassification,
    val coordinate: WorldCoordinate?,
    val occupied: Boolean,
    val incomingTroops: Boolean,
    val confidence: Double
)

/**
 * Associates visual tile candidates with nearby OCR/blue-march evidence.
 * Association is spatial and conservative: missing evidence remains unknown
 * rather than being guessed.
 */
class DetectionFusion(
    private val maxTextDistancePx: Float = 180f,
    private val maxMarchDistancePx: Float = 110f
) {
    fun fuse(
        frame: DetectionFrame,
        textRegions: List<TextRegion>,
        marchSignals: List<MarchSignal>,
        coordinateResolver: (Float, Float) -> WorldCoordinate?
    ): List<FusionCandidate> {
        return frame.tiles.map { tile ->
            val text = textRegions.minByOrNull { distance(tile.bounds, it.bounds) }
                ?.takeIf { distance(tile.bounds, it.bounds) <= maxTextDistancePx }

            val classification = text?.classification ?: TextClassification(
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

            val incoming = classification.incomingTroops == true || march != null
            val occupied = classification.occupied == true || incoming
            val coordinate = coordinateResolver(tile.centerX, tile.centerY)
            val evidence = listOf(
                tile.confidence,
                if (text != null) 0.90 else 0.0,
                if (march != null) march.confidence.toDouble() else 0.0
            ).filter { it > 0.0 }
            val confidence = evidence.average().coerceIn(0.0, 1.0)

            FusionCandidate(tile, classification, coordinate, occupied, incoming, confidence)
        }
    }

    private fun distance(a: RectF, b: RectF): Float {
        val ax = a.centerX()
        val ay = a.centerY()
        val bx = b.centerX()
        val by = b.centerY()
        return hypot((ax - bx).toDouble(), (ay - by).toDouble()).toFloat()
    }
}

data class TextRegion(
    val bounds: RectF,
    val classification: TextClassification
)
