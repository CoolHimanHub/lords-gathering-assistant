package com.coolhiman.lordsassistant.vision

import android.graphics.RectF

/**
 * Game-specific perception contract.
 *
 * A future Lords Mobile LiteRT/TFLite model can implement this interface
 * without changing capture, calibration, OCR, popup validation, or action
 * authority. The current generic ML Kit detector is intentionally not
 * presented as a Lords Mobile classifier.
 */
enum class LordsMobileObjectClass {
    RESOURCE,
    MONSTER,
    DARKNEST,
    CASTLE,
    EMPTY,
    UNKNOWN
}

enum class LordsMobileResourceType {
    FOOD,
    WOOD,
    STONE,
    ORE,
    GOLD,
    UNKNOWN
}

data class LordsMobileObjectHypothesis(
    val bounds: RectF,
    val objectClass: LordsMobileObjectClass,
    val confidence: Double,
    val resourceType: LordsMobileResourceType? = null,
    val level: Int? = null,
    val levelConfidence: Double = 0.0
) {
    val isGameSpecific: Boolean
        get() = objectClass != LordsMobileObjectClass.UNKNOWN

    val isActionSafeEvidence: Boolean
        get() = false
}

/**
 * Model seam only: perception may describe what is visible, but it never
 * grants coordinate authority or permission to tap/gather/hunt.
 */
interface LordsMobileSceneModel {
    fun detect(bitmap: android.graphics.Bitmap): List<LordsMobileObjectHypothesis>
    fun close() {}
}
