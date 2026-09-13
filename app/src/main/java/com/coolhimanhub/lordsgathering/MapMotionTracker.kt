package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import kotlin.math.abs

/**
 * V54: visual movement confirmation independent of coordinate OCR.
 *
 * The coverage engine must not depend on X/Y OCR being available on every
 * frame. This tracker compares a small, stable sample of the map itself and
 * reports whether the viewport visibly changed. It deliberately ignores the
 * assistant overlay, top HUD and bottom controls.
 */
class MapMotionTracker {
    private var previous: FloatArray? = null

    companion object {
        private const val GRID_W = 32
        private const val GRID_H = 14
        private const val LEFT_F = 0.27f
        private const val RIGHT_F = 0.93f
        private const val TOP_F = 0.14f
        private const val BOTTOM_F = 0.86f
        private const val MOVEMENT_THRESHOLD = 9.0f
        private const val STRONG_MOVEMENT_THRESHOLD = 16.0f
    }

    /** Returns true when this frame is visually different from the last map frame. */
    @Synchronized
    fun observe(bitmap: Bitmap): Boolean {
        val current = sample(bitmap)
        val old = previous
        previous = current
        if (old == null || old.size != current.size) return false

        var total = 0f
        var changed = 0
        for (i in current.indices) {
            val d = abs(current[i] - old[i])
            total += d
            if (d >= MOVEMENT_THRESHOLD) changed++
        }
        val mean = total / current.size
        val changedRatio = changed.toFloat() / current.size

        // A real map swipe changes a broad part of the sampled map. Small
        // animated resource effects normally affect only a few samples.
        return mean >= STRONG_MOVEMENT_THRESHOLD ||
            (mean >= MOVEMENT_THRESHOLD && changedRatio >= 0.22f)
    }

    @Synchronized
    fun reset() {
        previous = null
    }

    private fun sample(bitmap: Bitmap): FloatArray {
        val out = FloatArray(GRID_W * GRID_H)
        val left = (bitmap.width * LEFT_F).toInt().coerceAtLeast(0)
        val right = (bitmap.width * RIGHT_F).toInt().coerceAtMost(bitmap.width - 1)
        val top = (bitmap.height * TOP_F).toInt().coerceAtLeast(0)
        val bottom = (bitmap.height * BOTTOM_F).toInt().coerceAtMost(bitmap.height - 1)
        val spanW = (right - left).coerceAtLeast(1)
        val spanH = (bottom - top).coerceAtLeast(1)

        var index = 0
        for (gy in 0 until GRID_H) {
            val y = top + ((gy + 0.5f) / GRID_H * spanH).toInt()
            for (gx in 0 until GRID_W) {
                val x = left + ((gx + 0.5f) / GRID_W * spanW).toInt()
                val c = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
                // Luma is sufficient and avoids sensitivity to small colour shifts.
                out[index++] = 0.299f * ((c ushr 16) and 0xff) +
                    0.587f * ((c ushr 8) and 0xff) +
                    0.114f * (c and 0xff)
            }
        }
        return out
    }
}
