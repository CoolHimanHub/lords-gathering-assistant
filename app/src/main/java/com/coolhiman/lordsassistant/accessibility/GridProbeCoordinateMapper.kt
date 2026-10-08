package com.coolhiman.lordsassistant.accessibility

import com.coolhiman.lordsassistant.model.ScreenPoint
import kotlin.math.abs
import kotlin.math.max

/**
 * Maps a probe point from MediaProjection bitmap coordinates into the
 * AccessibilityService display coordinate space.
 *
 * Probes fail closed when the two spaces appear rotated/cropped rather than
 * simply scaled. Guessing a rotated tap could hit a control.
 */
object GridProbeCoordinateMapper {
    private const val MAX_ASPECT_RATIO_ERROR = 0.02f

    fun map(
        point: ScreenPoint,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int
    ): ScreenPoint? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) {
            return null
        }

        if (!isInsideDisplay(point, sourceWidth, sourceHeight)) return null

        val sourceAspect = sourceWidth.toFloat() / sourceHeight.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()
        val aspectError = abs(sourceAspect - targetAspect) / max(sourceAspect, targetAspect)
        if (aspectError > MAX_ASPECT_RATIO_ERROR) return null

        return ScreenPoint(
            x = point.x * targetWidth.toFloat() / sourceWidth.toFloat(),
            y = point.y * targetHeight.toFloat() / sourceHeight.toFloat()
        )
    }

    fun isInsideDisplay(point: ScreenPoint, width: Int, height: Int): Boolean =
        width > 0 && height > 0 &&
            point.x >= 0f && point.x < width.toFloat() &&
            point.y >= 0f && point.y < height.toFloat()
}
