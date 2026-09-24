package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import com.coolhiman.lordsassistant.model.ResourceType
import kotlin.math.max
import kotlin.math.min

data class VisualTileClassification(
    val resource: ResourceType?,
    val confidence: Double
)

/**
 * Conservative badge-to-object visual classifier.
 *
 * This is discovery evidence, not proof of a target. It deliberately returns
 * null when the object signature is weak. Popup/OCR confirmation and the
 * existing action validation gates remain authoritative before interaction.
 */
class VisualTileClassifier(
    private val minConfidence: Double = 0.72
) {
    fun classify(bitmap: Bitmap, badge: RectF, tileClass: TileClass): VisualTileClassification {
        if (tileClass != TileClass.RESOURCE || bitmap.isRecycled) {
            return VisualTileClassification(null, 0.0)
        }

        val w = max(1f, badge.width())
        val h = max(1f, badge.height())
        // Resource artwork normally sits below/left of its level badge.
        val left = max(0, (badge.left - 2.4f * w).toInt())
        val top = max(0, (badge.bottom - 0.35f * h).toInt())
        val right = min(bitmap.width, (badge.right + 0.8f * w).toInt())
        val bottom = min(bitmap.height, (badge.bottom + 4.0f * h).toInt())
        if (right - left < 12 || bottom - top < 12) {
            return VisualTileClassification(null, 0.0)
        }

        var samples = 0
        var warm = 0
        var brightYellow = 0
        var brown = 0
        var gray = 0
        var green = 0
        var brightness = 0.0

        val hsv = FloatArray(3)
        val step = max(1, min(w, h).toInt() / 8)

        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val pixel = bitmap.getPixel(x, y)
                if (Color.alpha(pixel) >= 200) {
                    Color.RGBToHSV(Color.red(pixel), Color.green(pixel), Color.blue(pixel), hsv)
                    val hue = hsv[0]
                    val sat = hsv[1]
                    val value = hsv[2]
                    if (value > 0.20f) {
                        samples++
                        brightness += value
                        if (hue in 18f..48f && sat > 0.20f) warm++
                        if (hue in 28f..65f && sat > 0.42f && value > 0.55f) brightYellow++
                        if (hue in 5f..32f && sat > 0.25f && value < 0.72f) brown++
                        if (sat < 0.20f && value in 0.25f..0.82f) gray++
                        if (hue in 55f..155f && sat > 0.20f && value > 0.25f) green++
                    }
                }
                x += step
            }
            y += step
        }

        if (samples < 20) return VisualTileClassification(null, 0.0)

        val warmRatio = warm.toDouble() / samples
        val yellowRatio = brightYellow.toDouble() / samples
        val brownRatio = brown.toDouble() / samples
        val grayRatio = gray.toDouble() / samples
        val greenRatio = green.toDouble() / samples
        val meanBrightness = brightness / samples

        // The map's food nodes are characteristically pale/warm and textured.
        val foodScore = (warmRatio * 1.35 + (0.80 - meanBrightness).coerceAtLeast(0.0) * 0.55
            - greenRatio * 0.55).coerceIn(0.0, 1.0)

        // Wood nodes are darker warm/brown objects with less bright-yellow coverage.
        val woodScore = (brownRatio * 1.55 + (0.75 - meanBrightness).coerceAtLeast(0.0) * 0.35
            - yellowRatio * 0.45 - greenRatio * 0.25).coerceIn(0.0, 1.0)

        // Gold is kept conservative because food and gold share yellow hues.
        val goldScore = (yellowRatio * 1.45 + meanBrightness * 0.30
            - greenRatio * 0.35).coerceIn(0.0, 1.0)

        // Stone/ore are intentionally not split by weak color evidence.
        val grayScore = (grayRatio * 1.35 - greenRatio * 0.25).coerceIn(0.0, 1.0)

        val scored = listOf(
            ResourceType.FOOD to foodScore,
            ResourceType.WOOD to woodScore,
            ResourceType.GOLD to goldScore,
            ResourceType.STONE to grayScore
        ).sortedByDescending { it.second }

        val best = scored.first()
        val runnerUp = scored.getOrNull(1)?.second ?: 0.0
        val margin = (best.second - runnerUp).coerceAtLeast(0.0)
        val confidence = (0.55 * best.second + 0.45 * margin).coerceIn(0.0, 0.95)

        return if (confidence >= minConfidence) {
            VisualTileClassification(best.first, confidence)
        } else {
            VisualTileClassification(null, confidence)
        }
    }
}
