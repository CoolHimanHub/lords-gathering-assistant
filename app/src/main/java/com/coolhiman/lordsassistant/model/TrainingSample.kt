package com.coolhiman.lordsassistant.model

data class TrainingSample(
    val id: String,
    val labelType: String,
    val label: String,
    val level: Int? = null,
    val kingdom: Int? = null,
    val worldX: Int? = null,
    val worldY: Int? = null,
    val imagePath: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val captureSource: String = "UNKNOWN",
    val confidence: Float = 0f,
    val coordinateAuthority: String = "NONE",
    val cameraStable: Boolean = false,
    val modelVersion: String = "unknown",
    val createdAtMs: Long = System.currentTimeMillis()
)