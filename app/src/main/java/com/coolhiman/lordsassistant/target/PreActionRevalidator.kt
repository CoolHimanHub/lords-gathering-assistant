package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint

data class ActionTargetSnapshot(
    val coordinate: com.coolhiman.lordsassistant.model.WorldCoordinate,
    val kind: com.coolhiman.lordsassistant.model.TargetKind,
    val level: Int,
    val actionKind: ActionKind,
    val point: ScreenPoint
)

/**
 * Final pre-action consistency check.
 *
 * The scanner may have selected a target a few frames earlier. Before any
 * future gesture is dispatched, the latest scan must still describe the same
 * world target, level and action, and the interaction point must not have
 * drifted materially.
 */
object PreActionRevalidator {
    private const val MAX_POINT_DRIFT_PX = 45f

    fun revalidate(
        selected: ActionTargetSnapshot?,
        latestObservation: MapObservation?,
        latestValidation: TargetValidationResult,
        latestAction: ActionButton?
    ): TargetValidationResult {
        if (selected == null) {
            return TargetValidationResult(
                false,
                TargetValidationStage.DETECTED,
                setOf(TargetBlockReason.NO_TARGET)
            )
        }

        val reasons = linkedSetOf<TargetBlockReason>()
        val latestCoordinate = latestObservation?.coordinate
        val latestKind = latestObservation?.kind
        val latestLevel = latestObservation?.level
        val latestPoint = latestAction?.point

        if (latestCoordinate != selected.coordinate ||
            latestKind != selected.kind ||
            latestLevel != selected.level
        ) {
            reasons += TargetBlockReason.TARGET_CHANGED
        }

        if (latestAction?.kind != selected.actionKind) {
            reasons += TargetBlockReason.ACTION_MISMATCH
        }

        if (latestPoint == null ||
            distancePx(selected.point, latestPoint) > MAX_POINT_DRIFT_PX
        ) {
            reasons += TargetBlockReason.INTERACTION_POINT_INVALID
        }

        reasons += latestValidation.reasons

        return if (reasons.isEmpty() && latestValidation.safe) {
            TargetValidationResult(
                true,
                TargetValidationStage.SAFE_TO_INTERACT
            )
        } else {
            TargetValidationResult(
                false,
                latestValidation.stage,
                reasons
            )
        }
    }

    private fun distancePx(a: ScreenPoint, b: ScreenPoint): Float {
        val dx = a.x - b.x
        val dy = a.y - b.y
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }
}
