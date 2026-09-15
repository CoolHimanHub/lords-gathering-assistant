package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** V58: lightweight candidate discovery, independent of blue badge detection. */
class TileCandidateScanner {
    data class Candidate(val x: Int, val y: Int, val score: Int)

    fun scan(bitmap: Bitmap): List<Candidate> {
        val sx = bitmap.width / 1536f
        val sy = bitmap.height / 707f
        val left = max(0, (410 * sx).toInt())
        val right = min(bitmap.width - 1, (1420 * sx).toInt())
        val top = max(0, (90 * sy).toInt())
        val bottom = min(bitmap.height - 1, (665 * sy).toInt())
        val step = max(28, (48 * sx).toInt())
        val radius = max(6, (12 * sx).toInt())
        val out = ArrayList<Candidate>()
        var y = top + step / 2
        while (y <= bottom) {
            var x = left + step / 2
            while (x <= right) {
                var vivid = 0
                var nonDark = 0
                for (yy in max(top, y - radius)..min(bottom, y + radius)) {
                    for (xx in max(left, x - radius)..min(right, x + radius)) {
                        val c = bitmap.getPixel(xx, yy)
                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                        val mx = max(r, max(g, b))
                        if (mx > 55) nonDark++
                        if (mx - min(r, min(g, b)) > 45 && mx > 90) vivid++
                    }
                }
                val score = min(100, vivid * 100 / nonDark.coerceAtLeast(1))
                if (score >= 18) out += Candidate(x, y, score)
                x += step
            }
            y += step
        }
        return out
    }
}