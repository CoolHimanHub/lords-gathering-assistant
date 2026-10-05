package com.coolhiman.lordsassistant.data

import com.coolhiman.lordsassistant.vision.PopupState

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
        if (popup.terrainName != null && popup.kind == null && popup.resource == null && popup.monsterName == null) {
            return TrainingSampleDecision(rejection = "TERRAIN_POPUP")
        }
        val label = when {
            popup.kind?.name == "RESOURCE" && popup.resource != null -> popup.resource.name
            popup.kind?.name == "MONSTER" -> popup.monsterName?.takeIf { it.isNotBlank() } ?: "MONSTER"
            else -> null
        } ?: return TrainingSampleDecision(rejection = "SEMANTIC_LABEL_MISSING")
        val confidence = when {
            popup.kind?.name == "RESOURCE" && popup.resource != null -> 0.98f
            popup.kind?.name == "MONSTER" && !popup.monsterName.isNullOrBlank() -> 0.96f
            popup.kind?.name == "MONSTER" -> 0.82f
            else -> 0.90f
        }
        if (label != "EMPTY" && confidence < 0.80f) {
            return TrainingSampleDecision(rejection = "SEMANTIC_CONFIDENCE_LOW")
        }
        if (popup.level != null && popup.level !in 1..5) {
            return TrainingSampleDecision(rejection = "LEVEL_OUT_OF_RANGE")
        }
        val labelType = when {
            popup.kind?.name == "RESOURCE" -> "RESOURCE"
            popup.kind?.name == "MONSTER" -> "MONSTER"
            else -> "UNKNOWN"
        }
        return TrainingSampleDecision(labelType, label, confidence)
    }
}
