package com.coolhiman.lordsassistant.model

data class ScreenPoint(val x: Float, val y: Float)

data class MapObservation(
    val coordinate: WorldCoordinate?,
    val screenPoint: ScreenPoint?,
    val label: String?,
    val level: Int?,
    val quantity: Long?,
    val occupied: Boolean?,
    val incomingTroops: Boolean?,
    val kind: TargetKind?,
    val confidence: Float,
    val evidence: Set<ObservationEvidence> = emptySet(),
    val timestampMs: Long = System.currentTimeMillis()
)

data class ViewportCalibration(
    val anchor: WorldCoordinate,
    val anchorPx: ScreenPoint,
    val pixelsPerTileX: Float,
    val pixelsPerTileY: Float
) {
    fun predict(coordinate: WorldCoordinate): ScreenPoint {
        val dx = coordinate.x - anchor.x
        val dy = coordinate.y - anchor.y
        return ScreenPoint(
            anchorPx.x + (dx - dy) * pixelsPerTileX,
            anchorPx.y + (dx + dy) * pixelsPerTileY
        )
    }
}
