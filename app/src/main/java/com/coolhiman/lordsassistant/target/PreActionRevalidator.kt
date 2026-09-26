package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint

data class ActionTargetSnapshot(
    val coordinate: com.coolhiman.lordsassistant.model.WorldCoordinate,
    val kind: com.coolhiman.lordsassistant.model.TargetKind,
    val level: Int,
    val actionKind: ActionKind,
    val point: ScreenPoint,
    /** Semantic identity captured with the selected target; null means unavailable. */
    val semanticIdentity: String? = null
) {
    /** Stable world/action identity; screen point is transient UI geometry. */
    fun identity(): ActionTargetIdentity =
        ActionTargetIdentity(coordinate, kind, level, actionKind, semanticIdentity)
}

data class ActionTargetIdentity(
    val coordinate: com.coolhiman.lordsassistant.model.WorldCoordinate,
    val kind: com.coolhiman.lordsassistant.model.TargetKind,
    val level: Int,
    val actionKind: ActionKind,
    val semanticIdentity: String? = null
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
        val latestObservationPoint = latestObservation?.screenPoint
        val latestSemanticIdentity = latestObservation?.label?.trim()?.takeIf { it.isNotEmpty() }

        if (latestCoordinate != selected.coordinate ||
            latestKind != selected.kind ||
            latestLevel != selected.level
        ) {
            reasons += TargetBlockReason.TARGET_CHANGED
        }

        if (latestAction?.kind != selected.actionKind) {
            reasons += TargetBlockReason.ACTION_MISMATCH
        }

        if (selected.semanticIdentity != null &&
            (latestSemanticIdentity == null ||
                !selected.semanticIdentity.equals(latestSemanticIdentity, ignoreCase = true))
        ) {
            reasons += TargetBlockReason.TARGET_CHANGED
        }

        // Coordinate provenance is a direct pre-dispatch requirement. Do not
        // rely on a separately supplied validation object to prove that the
        // latest frame still has an observed, action-authoritative coordinate.
        if (latestObservation?.coordinateConfidence?.actionAuthoritative != true) {
            reasons += TargetBlockReason.COORDINATE_AUTHORITY_INVALID
        }

        // Temporal confirmation is also frame-local evidence. A stale
        // validation result must not make a newly unconfirmed observation safe.
        if (latestObservation?.evidence?.contains(ObservationEvidence.TEMPORALLY_CONFIRMED) != true) {
            reasons += TargetBlockReason.STATE_UNKNOWN
        }

        // Do not trust a separately supplied validation object over the
        // frame-local state it is supposed to validate. This closes the
        // TOCTOU gap where an occupied/incoming tile could be paired with an
        // accidentally stale "safe" validation result.
        val latestOccupied = latestObservation?.occupied
        val latestIncoming = latestObservation?.incomingTroops
        if (latestOccupied == null || latestIncoming == null) {
            reasons += TargetBlockReason.STATE_UNKNOWN
        }
        if (latestOccupied == true) {
            reasons += TargetBlockReason.OCCUPIED
        }
        if (latestIncoming == true) {
            reasons += TargetBlockReason.INCOMING_TROOPS
        }

        if (latestPoint == null ||
            distancePx(selected.point, latestPoint) > MAX_POINT_DRIFT_PX
        ) {
            reasons += TargetBlockReason.INTERACTION_POINT_INVALID
        }

        if (latestObservationPoint == null ||
            distancePx(selected.point, latestObservationPoint) > MAX_POINT_DRIFT_PX
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
