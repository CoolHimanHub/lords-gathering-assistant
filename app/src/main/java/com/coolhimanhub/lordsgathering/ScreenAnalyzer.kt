package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Conservative RSS detector.
 *
 * Important design rule: a resource is never created from a generic bright/grey
 * area. The first gate is the small blue level badge used by Lords Mobile.
 * Only after a real badge is found do we inspect the nearby resource artwork.
 */
class ScreenAnalyzer {

    companion object {
        private const val BLUE_MIN = 105
        private const val BLUE_RED_GAP = 35
        private const val BLUE_GREEN_GAP = 12
        private const val MIN_COMPONENT = 25
        private const val MAX_COMPONENT = 1800

        private val DIGITS = arrayOf(
            arrayOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            arrayOf("11100", "00010", "00010", "00100", "01000", "10000", "11110"),
            arrayOf("11100", "00010", "00010", "01100", "00010", "00010", "11100"),
            arrayOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            arrayOf("11110", "10000", "10000", "11100", "00010", "00010", "11100")
        )
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 100 || bitmap.height < 100) return emptyList()

        val boxes = findLevelBadgeComponents(bitmap, expectedRegionX, expectedRegionY)
        val out = mutableListOf<RssDetection>()

        for (box in boxes) {
            val level = readBadgeLevel(bitmap, box) ?: continue
            val typeResult = classifyResourceArtwork(bitmap, box)

            if (typeResult == null) continue

            val occupied = detectOccupation(bitmap, box)
            val confidence = min(
                100,
                (typeResult.second * 0.65 + badgeConfidence(bitmap, box) * 0.35).toInt()
            )

            if (confidence < 45) continue

            out += RssDetection(
                type = typeResult.first,
                level = level,
                centerX = box.centerX,
                centerY = box.centerY,
                boundingBox = box,
                confidence = confidence,
                occupied = occupied,
                dominantColor = typeResult.third
            )
        }

        return deduplicate(out)
    }

    /** Finds connected blue badge components, not arbitrary bright regions. */
    private fun findLevelBadgeComponents(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {
        val step = 2
        val gw = (bitmap.width + step - 1) / step
        val gh = (bitmap.height + step - 1) / step
        val visited = BooleanArray(gw * gh)
        val result = mutableListOf<BoundingBox>()

        fun inside(gx: Int, gy: Int): Boolean =
            gx in 0 until gw && gy in 0 until gh

        fun isBlue(gx: Int, gy: Int): Boolean {
            val x = gx * step
            val y = gy * step
            if (x !in regionX || y !in regionY) return false

            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)

            return b >= BLUE_MIN &&
                b - r >= BLUE_RED_GAP &&
                b - g >= BLUE_GREEN_GAP
        }

        for (gy in 0 until gh) {
            for (gx in 0 until gw) {
                val idx = gy * gw + gx

                if (visited[idx] || !isBlue(gx, gy)) continue

                val qx = IntArray(2048)
                val qy = IntArray(2048)
                var head = 0
                var tail = 0

                qx[tail] = gx
                qy[tail++] = gy
                visited[idx] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var count = 0

                while (head < tail) {
                    val cx = qx[head]
                    val cy = qy[head++]

                    count++

                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue

                            val nx = cx + dx
                            val ny = cy + dy

                            if (!inside(nx, ny)) continue

                            val ni = ny * gw + nx

                            if (!visited[ni] && isBlue(nx, ny)) {
                                visited[ni] = true

                                if (tail < qx.size) {
                                    qx[tail] = nx
                                    qy[tail++] = ny
                                }
                            }
                        }
                    }
                }

                val w = (maxX - minX + 1) * step
                val h = (maxY - minY + 1) * step

                if (
                    count in 8..(MAX_COMPONENT / 4) &&
                    w in 12..90 &&
                    h in 12..70 &&
                    w.toFloat() / h.toFloat() in 0.35f..3.0f
                ) {
                    result += BoundingBox(
                        minX * step,
                        minY * step,
                        min((maxX + 1) * step - 1, bitmap.width - 1),
                        min((maxY + 1) * step - 1, bitmap.height - 1)
                    )
                }
            }
        }

        return result
    }

    private fun readBadgeLevel(
        bitmap: Bitmap,
        box: BoundingBox
    ): Int? {

        val left = box.minX
        val top = box.minY
        val right = box.maxX
        val bottom = box.maxY

        val w = right - left + 1
        val h = bottom - top + 1

        if (w < 10 || h < 10) return null

        val samples = Array(7) { BooleanArray(5) }

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {

                val x0 = left + gx * w / 5
                val x1 = left + (gx + 1) * w / 5
                val y0 = top + gy * h / 7
                val y1 = top + (gy + 1) * h / 7

                var white = 0
                var total = 0

                for (y in y0 until max(y0 + 1, y1)) {
                    for (x in x0 until max(x0 + 1, x1)) {

                        if (x >= bitmap.width || y >= bitmap.height) continue

                        val c = bitmap.getPixel(x, y)

                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)

                        if (r > 180 && g > 180 && b > 180) {
                            white++
                        }

                        total++
                    }
                }

                samples[gy][gx] =
                    total > 0 &&
                    white * 100 >= total * 22
            }
        }

        var bestDigit = 1
        var bestScore = Double.MAX_VALUE

        for (d in 0 until 5) {

            var diff = 0

            for (y in 0 until 7) {
                for (x in 0 until 5) {

                    if (
                        samples[y][x] !=
                        (DIGITS[d][y][x] == '1')
                    ) {
                        diff++
                    }
                }
            }

            if (diff < bestScore) {
                bestScore = diff.toDouble()
                bestDigit = d + 1
            }
        }

        return if (bestScore <= 18) bestDigit else null
    }

    /**
     * Inspect artwork below/left of the badge.
     * The badge itself is excluded.
     */
    private fun classifyResourceArtwork(
        bitmap: Bitmap,
        badge: BoundingBox
    ): TripleResult? {

        val cx = badge.centerX
        val top = badge.maxY + 2
        val left = cx - 58
        val right = cx + 34
        val bottom = badge.maxY + 82

        val colors = mutableListOf<Triple<Int, Int, Int>>()

        var resourceLike = 0

        for (
            y in max(0, top)..min(bitmap.height - 1, bottom) step 3
        ) {
            for (
                x in max(0, left)..min(bitmap.width - 1, right) step 3
            ) {

                val c = bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (b - r > 30 && b - g > 10) continue

                val maxC = max(r, max(g, b))
                val minC = min(r, min(g, b))

                if (maxC - minC > 25 || maxC > 145) {
                    colors += Triple(r, g, b)
                    resourceLike++
                }
            }
        }

        if (colors.size < 20 || resourceLike < 20) {
            return null
        }

        val avg = Triple(
            colors.map { it.first }.average().toInt(),
            colors.map { it.second }.average().toInt(),
            colors.map { it.third }.average().toInt()
        )

        val features = colors.map { hsv(it) }

        val sat = features.map { it.second }.average()
        val value = features.map { it.third }.average()

        val redBias = avg.first - avg.second

        val yellow =
            avg.first > avg.third + 20 &&
            avg.second > avg.third + 10

        val green =
            avg.second > avg.first + 8 &&
            avg.second > avg.third + 5

        val brown =
            redBias > 12 &&
            avg.second > avg.third + 5

        val bluePurple =
            avg.third > avg.first + 8 ||
            (
                avg.third > avg.second + 5 &&
                sat > 0.18
            )

        val scored = mutableListOf<Pair<String, Int>>()

        if (yellow && value > 0.55) {
            scored += "Food" to 62
        }

        if (
            yellow &&
            value > 0.72 &&
            avg.first > 185
        ) {
            scored += "Gold" to 70
        }

        if (brown) {
            scored += "Wood" to 64
        }

        if (
            green &&
            sat > 0.15
        ) {
            scored += "Food" to 58
        }

        if (bluePurple) {
            scored += "Ore" to 60
        }

        if (
            sat < 0.20 &&
            value < 0.72
        ) {
            scored += "Stone" to 62
        }

        if (scored.isEmpty()) return null

        scored.sortByDescending { it.second }

        val best = scored.first()
        val second = scored.getOrNull(1)?.second ?: 0

        val confidence =
            (
                best.second +
                min(18, best.second - second + 8)
            ).coerceIn(45, 90)

        if (
            second > 0 &&
            best.second - second < 8
        ) {
            return null
        }

        return TripleResult(
            best.first,
            confidence,
            avg
        )
    }

    private fun detectOccupation(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Boolean {

        val l = max(0, badge.minX - 70)
        val r = min(bitmap.width - 1, badge.maxX + 50)
        val t = max(0, badge.minY - 25)
        val b = min(bitmap.height - 1, badge.maxY + 90)

        var red = 0
        var strongRed = 0
        var total = 0

        for (y in t..b step 3) {
            for (x in l..r step 3) {

                val c = bitmap.getPixel(x, y)

                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)

                total++

                if (
                    rr > 180 &&
                    rr > gg * 1.45 &&
                    rr > bb * 1.45
                ) {
                    red++

                    if (
                        rr > 220 &&
                        gg < 90 &&
                        bb < 90
                    ) {
                        strongRed++
                    }
                }
            }
        }

        return total > 0 &&
            strongRed >= 8 &&
            red.toFloat() / total.toFloat() > 0.012f
    }

    private fun badgeConfidence(
        bitmap: Bitmap,
        box: BoundingBox
    ): Int {

        var good = 0
        var total = 0

        for (
            y in box.minY..box.maxY step 2
        ) {
            for (
                x in box.minX..box.maxX step 2
            ) {

                val c = bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                total++

                if (
                    b >= BLUE_MIN &&
                    b - r >= BLUE_RED_GAP &&
                    b - g >= BLUE_GREEN_GAP
                ) {
                    good++
                }
            }
        }

        return if (total == 0) {
            0
        } else {
            (good * 100 / total).coerceIn(0, 100)
        }
    }

    private fun deduplicate(
        items: List<RssDetection>
    ): List<RssDetection> {

        val out = mutableListOf<RssDetection>()

        for (
            item in items.sortedByDescending { it.confidence }
        ) {

            if (
                out.none {
                    abs(it.centerX - item.centerX) < 35 &&
                    abs(it.centerY - item.centerY) < 35
                }
            ) {
                out += item
            }
        }

        return out
    }

    private fun hsv(
        c: Triple<Int, Int, Int>
    ): Triple<Float, Float, Float> {

        val r = c.first / 255f
        val g = c.second / 255f
        val b = c.third / 255f

        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn

        val h = when {
            d == 0f -> 0f

            mx == r ->
                ((g - b) / d) % 6f

            mx == g ->
                (b - r) / d + 2f

            else ->
                (r - g) / d + 4f
        }

        return Triple(
            if (h < 0) h + 6f else h,
            if (mx == 0f) 0f else d / mx,
            mx
        )
    }

    private data class TripleResult(
        val first: String,
        val second: Int,
        val third: Triple<Int, Int, Int>
    )

    data class BoundingBox(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val width: Int
            get() = maxX - minX + 1

        val height: Int
            get() = maxY - minY + 1

        val centerX: Int
            get() = (minX + maxX) / 2

        val centerY: Int
            get() = (minY + maxY) / 2
    }

    data class RssDetection(
        val type: String,
        val level: Int,
        val centerX: Int,
        val centerY: Int,
        val boundingBox: BoundingBox,
        val confidence: Int,
        val occupied: Boolean,
        val dominantColor: Triple<Int, Int, Int>
    )
}
