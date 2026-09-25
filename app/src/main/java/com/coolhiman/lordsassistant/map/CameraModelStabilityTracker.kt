package com.coolhiman.lordsassistant.map

import kotlin.math.abs

/**
 * Requires a camera model to remain geometrically consistent across frames
 * before it can contribute to action authority.
 *
 * Translation is intentionally ignored because normal map panning changes the
 * camera offsets. Scale is checked because an abrupt scale change can indicate
 * a zoom transition or an incorrect anchor association. The first valid model
 * is diagnostic only; a second consistent model establishes continuity.
 */
class CameraModelStabilityTracker(
    private val maxScaleChangePercent: Double = 3.0,
    private val maxResidualJumpPx: Double = 12.0,
    private val maxResidualPx: Double = 25.0
) {
    private var previous: CameraModel? = null

    fun update(model: CameraModel?): Boolean {
        if (model == null || !model.isUsable(maxResidualPx)) {
            previous = null
            return false
        }

        val before = previous
        previous = model
        if (before == null) return false

        if (before.scale <= 1e-6) return false

        val scaleChangePercent =
            abs(model.scale - before.scale) / before.scale * 100.0
        val residualJump = abs(model.residualRmsPx - before.residualRmsPx)

        return scaleChangePercent <= maxScaleChangePercent &&
            residualJump <= maxResidualJumpPx
    }

    fun reset() {
        previous = null
    }
}
