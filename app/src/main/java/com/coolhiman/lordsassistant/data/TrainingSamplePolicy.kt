package com.coolhiman.lordsassistant.data

import com.coolhiman.lordsassistant.vision.PopupState
import com.coolhiman.lordsassistant.vision.PopupSemantic

data class TrainingSampleDecision(
    val labelType: String? = null,
    val label: String? = null,
    val confidence: Float = 0f,
    val rejection: String? = null
)

object TrainingSamplePolicy {
    fun decide(
        popup: PopupState,
        acceptedForCalibration: Boolean,
        cameraStable: Boolean,
        coordinateAuthority: String
    ): TrainingSampleDecision {
        if (!acceptedForCalibration) return TrainingSampleDecision(rejection = "COORDINATE_NOT_ACCEPTED")
        if (!cameraStable) return TrainingSampleDecision(rejection = "CAMERA_UNSTABLE")
        if (coordinateAuthority != "OBSERVED") return TrainingSampleDecision(rejection = "COORDINATE_NOT_OBSERVED")
        if (!popup.isPopup || popup.coordinate == null) return TrainingSampleDecision(rejection = "NOT_A_TILE_POPUP")
        val effectiveSemantic = when {
            popup.semantic != PopupSemantic.UNKNOWN -> popup.semantic
            popup.kind?.name == "RESOURCE" -> PopupSemantic.RESOURCE
            popup.kind?.name == "MONSTER" -> PopupSemantic.MONSTER
            else -> PopupSemantic.UNKNOWN
        }
        if (effectiveSemantic == PopupSemantic.UNKNOWN && popup.terrainName != null) {
            return TrainingSampleDecision(rejection = "TERRAIN_POPUP")
        }
        val label = when (effectiveSemantic) {
            PopupSemantic.RESOURCE -> popup.resource?.name
            PopupSemantic.MONSTER -> popup.monsterName?.takeIf { it.isNotBlank() } ?: "MONSTER"
            PopupSemantic.DARKNEST -> "DARKNEST"
            PopupSemantic.CASTLE -> "CASTLE"
            PopupSemantic.EMPTY -> "EMPTY"
            PopupSemantic.UNKNOWN -> null
        } ?: return TrainingSampleDecision(rejection = "SEMANTIC_LABEL_MISSING")
        val confidence = when (effectiveSemantic) {
            PopupSemantic.RESOURCE -> if (popup.resource != null) 0.98f else 0f
            PopupSemantic.MONSTER -> if (!popup.monsterName.isNullOrBlank()) 0.96f else 0.82f
            PopupSemantic.DARKNEST -> 0.95f
            PopupSemantic.CASTLE -> 0.94f
            PopupSemantic.EMPTY -> 0.84f
            PopupSemantic.UNKNOWN -> 0f
        }
        if (label != "EMPTY" && confidence < 0.80f) {
            return TrainingSampleDecision(rejection = "SEMANTIC_CONFIDENCE_LOW")
        }
        if (popup.level != null && popup.level !in 1..5) {
            return TrainingSampleDecision(rejection = "LEVEL_OUT_OF_RANGE")
        }
        val labelType = when (effectiveSemantic) {
            PopupSemantic.RESOURCE -> "RESOURCE"
            PopupSemantic.MONSTER -> "MONSTER"
            PopupSemantic.DARKNEST -> "DARKNEST"
            PopupSemantic.CASTLE -> "CASTLE"
            PopupSemantic.EMPTY -> "EMPTY"
            PopupSemantic.UNKNOWN -> "UNKNOWN"
        }
        return TrainingSampleDecision(labelType, label, confidence)
    }
}
