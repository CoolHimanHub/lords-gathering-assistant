package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * V0.4 template detector.
 * Produces visual candidates only; popup/OCR/state validation is separate.
 */
class TemplateTileDetector(
    private val matcher: TemplateMatcher = TemplateMatcher(),
    private val threshold: Double = 0.84,
    private val iouThreshold: Float = 0.35f
) {
    fun detect(screen: Bitmap, templates: List<Pair<TileTemplate, Bitmap>>): DetectionFrame {
        val started = System.currentTimeMillis()
        val candidates = ArrayList<DetectedTile>()
        for ((template, bitmap) in templates) {
            if (bitmap.isRecycled || bitmap.width <= 2 || bitmap.height <= 2) continue
            val match = matcher.match(screen, bitmap, threshold) ?: continue
            candidates += DetectedTile(
                template.label, template.tileClass, template.level,
                RectF(match.x.toFloat(), match.y.toFloat(),
                    (match.x + match.width).toFloat(),
                    (match.y + match.height).toFloat()),
                match.score, template.imagePath
            )
        }
        return DetectionFrame(nonMaximumSuppress(candidates), System.currentTimeMillis() - started)
    }

    private fun nonMaximumSuppress(input: List<DetectedTile>): List<DetectedTile> {
        val result = ArrayList<DetectedTile>()
        for (candidate in input.sortedByDescending { it.confidence }) {
            if (result.none { iou(it.bounds, candidate.bounds) >= iouThreshold }) result += candidate
        }
        return result
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
