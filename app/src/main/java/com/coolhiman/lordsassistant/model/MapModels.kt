package com.coolhiman.lordsassistant.model

enum class CoordinateAuthority { NONE, INFERRED, CALIBRATED, OBSERVED }

/**
 * Provenance of a world coordinate. Provenance is deliberately separate from
 * numeric confidence: calibrated/predicted coordinates must never silently
 * become equivalent to directly observed game coordinates.
 */
data class CoordinateConfidence(
    val authority: CoordinateAuthority,
    val confidence: Float,
    val calibrationUsable: Boolean,
    val cameraStable: Boolean,
    val residualPx: Double? = null
) {
    val actionAuthoritative: Boolean
        get() = authority == CoordinateAuthority.OBSERVED &&
            confidence >= 0.95f &&
            calibrationUsable &&
            cameraStable

    companion object {
        fun none() = CoordinateConfidence(CoordinateAuthority.NONE, 0f, false, false)
        fun observed(calibrationUsable: Boolean, cameraStable: Boolean, residualPx: Double? = null) =
            CoordinateConfidence(CoordinateAuthority.OBSERVED, 1f, calibrationUsable, cameraStable, residualPx)
        fun calibrated(calibrationUsable: Boolean, cameraStable: Boolean, residualPx: Double?) =
            CoordinateConfidence(
                CoordinateAuthority.CALIBRATED,
                if (cameraStable && calibrationUsable) 0.9f else 0.65f,
                calibrationUsable, cameraStable, residualPx
            )
        fun inferred(calibrationUsable: Boolean, cameraStable: Boolean, residualPx: Double?) =
            CoordinateConfidence(
                CoordinateAuthority.INFERRED,
                if (cameraStable && calibrationUsable) 0.7f else 0.4f,
                calibrationUsable, cameraStable, residualPx
            )
    }
}

data class ScreenPoint(val x: Float, val y: Float)

data class MapObservation(
    val coordinate: WorldCoordinate?,
    val screenPoint: ScreenPoint?,
    val label: String? = null,
    val level: Int? = null,
    val quantity: Long? = null,
    val occupied: Boolean?,
    val incomingTroops: Boolean?,
    val kind: TargetKind?,
    val confidence: Float,
    val evidence: Set<ObservationEvidence> = emptySet(),
    val timestampMs: Long = System.currentTimeMillis(),
    /** Provenance of this observation's coordinate, not global OCR state. */
    val coordinateConfidence: CoordinateConfidence = CoordinateConfidence.none()
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
