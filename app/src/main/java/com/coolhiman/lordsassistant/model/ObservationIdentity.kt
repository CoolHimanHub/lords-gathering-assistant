package com.coolhiman.lordsassistant.model

enum class ObservationEvidence {
    POPUP_CONFIRMED,
    OCR_CONFIRMED,
    MARCH_CONFIRMED,
    TEMPORALLY_CONFIRMED,
    STATE_UNKNOWN,
    CAMERA_UNSTABLE
}

data class ObservationIdentity(
    val coordinate: WorldCoordinate,
    val kind: TargetKind?,
    val level: Int?
) {
    override fun toString(): String =
        "K${coordinate.kingdom}:X${coordinate.x}:Y${coordinate.y}:${kind ?: "UNKNOWN"}:L${level ?: "?"}"
}
