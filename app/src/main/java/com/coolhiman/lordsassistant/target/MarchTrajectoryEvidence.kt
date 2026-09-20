package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.vision.MarchSignal
import kotlin.math.hypot

/**
 * Session-bound evidence for the march associated with one action.
 *
 * This deliberately remains screen-space evidence. It must not be interpreted
 * as world-space movement unless a valid camera calibration is available.
 */
data class MarchTrajectoryEvidence(
    val actionPoint: ScreenPoint,
    val trajectory: List<MarchSignal>,
    val start: ScreenPoint?,
    val end: ScreenPoint?,
    val displacementPx: Float,
    val directionX: Float,
    val directionY: Float,
    val confirmingFrames: Int,
    val cameraStable: Boolean
) {
    companion object {
        fun from(
            actionPoint: ScreenPoint,
            trajectory: List<MarchSignal>,
            confirmingFrames: Int,
            cameraStable: Boolean
        ): MarchTrajectoryEvidence {
            val start = trajectory.firstOrNull()?.let { ScreenPoint(it.x, it.y) }
            val end = trajectory.lastOrNull()?.let { ScreenPoint(it.x, it.y) }
            val dx = if (start != null && end != null) end.x - start.x else 0f
            val dy = if (start != null && end != null) end.y - start.y else 0f
            val length = hypot(dx.toDouble(), dy.toDouble()).toFloat()
            return MarchTrajectoryEvidence(
                actionPoint = actionPoint,
                trajectory = trajectory.toList(),
                start = start,
                end = end,
                displacementPx = length,
                directionX = if (length > 0.001f) dx / length else 0f,
                directionY = if (length > 0.001f) dy / length else 0f,
                confirmingFrames = confirmingFrames,
                cameraStable = cameraStable
            )
        }
    }
}
