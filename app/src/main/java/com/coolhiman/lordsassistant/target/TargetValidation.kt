package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState

enum class TargetValidationStage {
    DETECTED, IDENTIFIED, TEMPORALLY_CONFIRMED, POPUP_CONFIRMED, STATE_VALIDATED, SAFE_TO_INTERACT
}

enum class TargetBlockReason {
    NO_TARGET, TARGET_CHANGED, CAMERA_UNSTABLE, CALIBRATION_INVALID, STATE_UNKNOWN,
    OCCUPIED, INCOMING_TROOPS, KIND_UNKNOWN, LEVEL_UNKNOWN, POPUP_MISSING,
    POPUP_MISMATCH, POPUP_KIND_MISMATCH, POPUP_RESOURCE_MISMATCH,
    POPUP_LEVEL_MISMATCH, INTERACTION_POINT_INVALID, ACTION_MISMATCH
}

data class TargetValidationResult(
    val safe: Boolean,
    val stage: TargetValidationStage,
    val reasons: Set<TargetBlockReason> = emptySet()
)

class TargetValidationEngine {
    fun validate(
        observation: MapObservation?,
        cameraStable: Boolean,
        calibrationValid: Boolean,
        popupState: PopupState?,
        expectedCoordinate: WorldCoordinate? = observation?.coordinate,
        expectedKind: TargetKind? = observation?.kind,
        expectedResource: com.coolhiman.lordsassistant.model.ResourceType? =
            observation?.label?.let { runCatching { com.coolhiman.lordsassistant.model.ResourceType.valueOf(it) }.getOrNull() },
        expectedLevel: Int? = observation?.level,
        interactionPointValid: Boolean = observation?.screenPoint != null,
        actionKind: ActionKind? = null
    ): TargetValidationResult {
        if (observation == null) return TargetValidationResult(false, TargetValidationStage.DETECTED, setOf(TargetBlockReason.NO_TARGET))
        val reasons = linkedSetOf<TargetBlockReason>()
        if (!cameraStable) reasons += TargetBlockReason.CAMERA_UNSTABLE
        if (!calibrationValid) reasons += TargetBlockReason.CALIBRATION_INVALID
        if (observation.coordinate == null) reasons += TargetBlockReason.TARGET_CHANGED
        if (observation.kind == null) reasons += TargetBlockReason.KIND_UNKNOWN
        if (observation.level == null) reasons += TargetBlockReason.LEVEL_UNKNOWN
        if (observation.occupied == null || observation.incomingTroops == null) reasons += TargetBlockReason.STATE_UNKNOWN
        if (observation.occupied == true) reasons += TargetBlockReason.OCCUPIED
        if (observation.incomingTroops == true) reasons += TargetBlockReason.INCOMING_TROOPS
        if (!observation.evidence.contains(ObservationEvidence.TEMPORALLY_CONFIRMED)) {
            reasons += TargetBlockReason.STATE_UNKNOWN
        }

        if (popupState?.isPopup != true) {
            reasons += TargetBlockReason.POPUP_MISSING
        } else {
            if (popupState.coordinate == null || expectedCoordinate == null || popupState.coordinate != expectedCoordinate) {
                reasons += TargetBlockReason.POPUP_MISMATCH
            }
            if (expectedKind != null && popupState.kind != null && popupState.kind != expectedKind) {
                reasons += TargetBlockReason.POPUP_KIND_MISMATCH
            }
            if (expectedKind != null && popupState.kind == null) {
                reasons += TargetBlockReason.POPUP_KIND_MISMATCH
            }
            if (expectedResource != null && popupState.resource != null && popupState.resource != expectedResource) {
                reasons += TargetBlockReason.POPUP_RESOURCE_MISMATCH
            }
            if (expectedResource != null && popupState.resource == null && expectedKind == TargetKind.RESOURCE) {
                reasons += TargetBlockReason.POPUP_RESOURCE_MISMATCH
            }
            if (expectedLevel != null && popupState.level != null && popupState.level != expectedLevel) {
                reasons += TargetBlockReason.POPUP_LEVEL_MISMATCH
            }
            if (expectedLevel != null && popupState.level == null) {
                reasons += TargetBlockReason.POPUP_LEVEL_MISMATCH
            }
        }
        if (!interactionPointValid) reasons += TargetBlockReason.INTERACTION_POINT_INVALID
        val actionValid = when (observation.kind) {
            TargetKind.RESOURCE -> actionKind == ActionKind.GATHER
            TargetKind.MONSTER -> actionKind == ActionKind.HUNT || actionKind == ActionKind.ATTACK
            null -> false
        }
        if (!actionValid) reasons += TargetBlockReason.ACTION_MISMATCH

        val stage = when {
            reasons.contains(TargetBlockReason.CAMERA_UNSTABLE) || reasons.contains(TargetBlockReason.CALIBRATION_INVALID) -> TargetValidationStage.IDENTIFIED
            reasons.contains(TargetBlockReason.POPUP_MISSING) || reasons.any {
                it == TargetBlockReason.POPUP_MISMATCH ||
                    it == TargetBlockReason.POPUP_KIND_MISMATCH ||
                    it == TargetBlockReason.POPUP_RESOURCE_MISMATCH ||
                    it == TargetBlockReason.POPUP_LEVEL_MISMATCH
            } -> TargetValidationStage.TEMPORALLY_CONFIRMED
            reasons.contains(TargetBlockReason.STATE_UNKNOWN) || reasons.contains(TargetBlockReason.OCCUPIED) || reasons.contains(TargetBlockReason.INCOMING_TROOPS) -> TargetValidationStage.POPUP_CONFIRMED
            reasons.isNotEmpty() -> TargetValidationStage.STATE_VALIDATED
            else -> TargetValidationStage.SAFE_TO_INTERACT
        }
        return TargetValidationResult(reasons.isEmpty(), stage, reasons)
    }
}
