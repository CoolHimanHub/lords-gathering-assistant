package com.coolhiman.lordsassistant.map

/**
 * Describes how a world coordinate became available to the scanner.
 *
 * These levels are deliberately ordered by provenance, not by a numeric
 * "accuracy score". An inferred coordinate must never silently become
 * equivalent to a directly observed coordinate.
 */
enum class CoordinateAuthority {
    NONE,
    INFERRED,
    CALIBRATED,
    OBSERVED
}

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
        fun none(): CoordinateConfidence =
            CoordinateConfidence(CoordinateAuthority.NONE, 0f, false, false)

        fun observed(
            calibrationUsable: Boolean,
            cameraStable: Boolean
        ): CoordinateConfidence =
            CoordinateConfidence(
                authority = CoordinateAuthority.OBSERVED,
                confidence = 1f,
                calibrationUsable = calibrationUsable,
                cameraStable = cameraStable
            )

        fun calibrated(
            calibrationUsable: Boolean,
            cameraStable: Boolean,
            residualPx: Double?
        ): CoordinateConfidence =
            CoordinateConfidence(
                authority = CoordinateAuthority.CALIBRATED,
                confidence = if (cameraStable && calibrationUsable) 0.9f else 0.65f,
                calibrationUsable = calibrationUsable,
                cameraStable = cameraStable,
                residualPx = residualPx
            )

        fun inferred(
            calibrationUsable: Boolean,
            cameraStable: Boolean,
            residualPx: Double?
        ): CoordinateConfidence =
            CoordinateConfidence(
                authority = CoordinateAuthority.INFERRED,
                confidence = if (cameraStable && calibrationUsable) 0.7f else 0.4f,
                calibrationUsable = calibrationUsable,
                cameraStable = cameraStable,
                residualPx = residualPx
            )
    }
}
