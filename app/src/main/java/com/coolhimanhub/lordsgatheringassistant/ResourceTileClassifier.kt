package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.max
import kotlin.math.min

/** V57.9 compile-safe adaptive resource-art classifier. */
class ResourceTileClassifier {
    enum class Type { FOOD, TIMBER, STONE, ORE, GOLD, UNKNOWN }
    data class Result(val type: Type, val confidence: Int, val redEvidence: Int)

    fun classify(bitmap: Bitmap, cx: Int, cy: Int): Result {
        val sx = bitmap.width / 1536f
        val sy = bitmap.height / 707f
        val a = classifyAt(bitmap, cx, cy, sx, sy)
        val badgeX = (cx + (18f * sx).toInt()).coerceIn(0, bitmap.width - 1)
        val badgeY = (cy + (7f * sy).toInt()).coerceIn(0, bitmap.height - 1)
        val b = classifyAt(bitmap, badgeX, badgeY, sx, sy)
        return if (b.confidence > a.confidence) b else a
    }

    private fun classifyAt(bitmap: Bitmap, cx: Int, cy: Int, sx: Float, sy: Float): Result {
        val left = max(0, cx - (42f * sx).toInt())
        val right = min(bitmap.width - 1, cx - (5f * sx).toInt())
        val top = max(0, cy - (34f * sy).toInt())
        val bottom = min(bitmap.height - 1, cy + (9f * sy).toInt())
        if (right <= left || bottom <= top) return Result(Type.UNKNOWN, 0, 0)

        var samples = 0
        var warm = 0
        var brown = 0
        var neutral = 0
        var blue = 0
        var cyan = 0
        var purple = 0
        var vivid = 0
        var redEvidence = 0
        val hsv = FloatArray(3)

        for (y in top..bottom) for (x in left..right) {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            if (mx > 220 && mn > 195) continue
            if (b > r + 25 && b > g + 12 && b > 120) continue
            if (r < 35 && g < 35 && b < 35) continue
            samples++
            if (r > 150 && g < 110 && b < 110) redEvidence++
            Color.RGBToHSV(r, g, b, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]
            if (s > .28f && v > .25f) vivid++
            if ((h < 45f || h >= 330f) && s > .25f && v > .28f) warm++
            if (h >= 15f && h <= 45f && s > .22f && v >= .20f && v <= .80f) brown++
            if (h >= 45f && h <= 68f && s > .30f && v > .45f) warm++
            if (h >= 175f && h <= 250f && s > .22f && v > .25f) blue++
            if (h >= 175f && h <= 205f && s > .30f && v > .35f) cyan++
            if (h >= 250f && h <= 330f && s > .20f && v > .22f) purple++
            if (s < .25f && v >= .25f && v <= .82f) neutral++
        }
        if (samples < 35) return Result(Type.UNKNOWN, 0, redEvidence)

        val food = vivid + purple + redEvidence
        val timber = brown * 2 + warm / 2
        val stone = neutral * 2 + purple / 2
        val ore = blue * 2 + cyan * 2
        val gold = warm * 2 + yellowScore(bitmap, left, right, top, bottom)
        val scores = linkedMapOf(
            Type.FOOD to food, Type.TIMBER to timber, Type.STONE to stone,
            Type.ORE to ore, Type.GOLD to gold
        )
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val second = ranked.getOrNull(1)?.value ?: 0
        val total = ranked.sumOf { it.value }.coerceAtLeast(1)
        val share = best.value.toFloat() / total
        val margin = (best.value - second).toFloat() / total
        val confidence = (100f * (.62f * share + .38f * margin)).toInt().coerceIn(0, 100)
        return if (best.value <= 0 || share < .28f || margin < .06f || confidence < 38) {
            Result(Type.UNKNOWN, confidence, redEvidence)
        } else Result(best.key, confidence, redEvidence)
    }

    private fun yellowScore(bitmap: Bitmap, left: Int, right: Int, top: Int, bottom: Int): Int {
        var score = 0
        for (y in top..bottom) for (x in left..right) {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (r > 145 && g > 105 && b < 85 && r > g * .92f) score++
        }
        return score
    }
}
