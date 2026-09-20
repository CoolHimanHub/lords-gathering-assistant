package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState

enum class TargetValidationStage {
    DETECTED, IDENTIFIED, TEMPORALLY_CONFIRMED, POPUP_CONFIRMED, STATE_VALIDATED, SAFE_TO_INTERACT
}

enum class TargetBlockReason {
    NO_TARGET, TARGET_CHANGED, CAMERA_UNSTABLE, CALIBRATION_INVALID, STATE_UNKNOWN,
    OCCUPIED, INCOMING_TROOPS, KIND_UNKNOWN, LEVEL_UNKNOWN, POPUP_MISSING,
    POPUP_MISMATCH, INTERACTION_POINT_INVALID
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
        interactionPointValid: Boolean = observation?.screenPoint != null
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
        } else if (popupState.coordinate == null || expectedCoordinate == null || popupState.coordinate != expectedCoordinate) {
            reasons += TargetBlockReason.POPUP_MISMATCH
        }
        if (!interactionPointValid) reasons += TargetBlockReason.INTERACTION_POINT_INVALID
        val stage = when {
            reasons.contains(TargetBlockReason.CAMERA_UNSTABLE) || reasons.contains(TargetBlockReason.CALIBRATION_INVALID) -> TargetValidationStage.IDENTIFIED
            reasons.contains(TargetBlockReason.POPUP_MISSING) || reasons.contains(TargetBlockReason.POPUP_MISMATCH) -> TargetValidationStage.TEMPORALLY_CONFIRMED
            reasons.contains(TargetBlockReason.STATE_UNKNOWN) || reasons.contains(TargetBlockReason.OCCUPIED) || reasons.contains(TargetBlockReason.INCOMING_TROOPS) -> TargetValidationStage.POPUP_CONFIRMED
            reasons.isNotEmpty() -> TargetValidationStage.STATE_VALIDATED
            else -> TargetValidationStage.SAFE_TO_INTERACT
        }
        return TargetValidationResult(reasons.isEmpty(), stage, reasons)
    }
}