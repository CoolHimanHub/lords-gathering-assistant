package com.coolhiman.lordsassistant.vision

import android.graphics.RectF

enum class TileClass { RESOURCE, MONSTER }

data class TileTemplate(
    val label: String,
    val tileClass: TileClass,
    val level: Int? = null,
    val imagePath: String
)

data class DetectedTile(
    val label: String,
    val tileClass: TileClass,
    val level: Int?,
    val bounds: RectF,
    val confidence: Double,
    val sourceTemplate: String? = null
) {
    val centerX: Float get() = bounds.centerX()
    val centerY: Float get() = bounds.centerY()
}

data class DetectionFrame(
    val tiles: List<DetectedTile>,
    val processingMs: Long
) {
    val resources: List<DetectedTile> get() = tiles.filter { it.tileClass == TileClass.RESOURCE }
    val monsters: List<DetectedTile> get() = tiles.filter { it.tileClass == TileClass.MONSTER }
}
