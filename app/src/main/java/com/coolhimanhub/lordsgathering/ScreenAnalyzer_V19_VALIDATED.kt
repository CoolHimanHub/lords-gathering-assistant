package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V19 - white-digit-first RSS detector.
 *
 * Design validated against the supplied 1536x707 Lords Mobile screenshot:
 *  - find small white level numerals first
 *  - require a blue shield immediately around that numeral
 *  - classify only the compact RSS artwork to the left/up-left of the shield
 *  - never classify broad green terrain as an RSS type
 *  - keep confidence conservative and never perform actions
 */
class ScreenAnalyzer {

    companion object {
        private const val BLUE_MIN = 78
        private const val BLUE_R_GAP = 18
        private const val BLUE_G_GAP = 3

        private const val DIGIT_MIN_W = 4
        private const val DIGIT_MAX_W = 18
        private const val DIGIT_MIN_H = 9
        private const val DIGIT_MAX_H = 28

        // Compact 5x7 reference shapes for the Lords Mobile badge font.
        private val DIGITS = arrayOf(
            arrayOf("00100","01100","00100","00100","00100","00100","01110"), // 1
            arrayOf("11100","00010","00010","00100","01000","10000","11110"), // 2
            arrayOf("11110","00001","00001","01110","00001","00001","11110"), // 3
            arrayOf("10010","10010","10010","11111","00010","00010","00010"), // 4
            arrayOf("11111","10000","10000","11110","00001","00001","11110")  // 5
        )
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 100 || bitmap.height < 100) return emptyList()

        val digits = findWhiteDigitCandidates(bitmap, expectedRegionX, expectedRegionY)
        val out = mutableListOf<RssDetection>()

        for (digit in digits) {
            val level = readLevelFromDigit(bitmap, digit) ?: continue
            val badge = badgeAroundDigit(bitmap, digit) ?: continue
            val art = classifyArtwork(bitmap, badge) ?: continue

            // V19 intentionally does not infer occupation from red/orange art.
            // The existing auto-gather engine remains separately guarded.
            val occupied = detectCompactMarchMarker(bitmap, badge)
            val badgeScore = badgeConfidence(bitmap, badge)
            val confidence = (art.confidence * 0.82 + badgeScore * 0.18)
                .toInt().coerceIn(0, 100)

            if (confidence < 62) continue

            out += RssDetection(
                type = art.name,
                level = level,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = art.avg
            )
        }

        return deduplicate(out)
    }

    // ---------------------------------------------------------------
    // 1. WHITE DIGIT FIRST
    // ---------------------------------------------------------------

    private fun findWhiteDigitCandidates(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<DigitBox> {
        val step = 1
        val w = bitmap.width
        val h = bitmap.height
        val visited = BooleanArray(w * h)
        val result = mutableListOf<DigitBox>()

        fun isWhiteDigitPixel(x: Int, y: Int): Boolean {
            if (x !in regionX || y !in regionY) return false
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            return mn >= 145 && mx >= 175 && mx - mn <= 95
        }

        // Resource badges are on the map; ignore the fixed left control panel
        // and the bottom HUD where there is lots of white text/icons.
        val minX = max(regionX.first, 140)
        val maxX = min(regionX.last, w - 1)
        val minY = max(regionY.first, 45)
        val maxY = min(regionY.last, h - 125)

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val idx = y * w + x
                if (visited[idx] || !isWhiteDigitPixel(x, y)) continue

                val q = ArrayDeque<Int>()
                val pts = mutableListOf<Int>()
                q.add(idx)
                visited[idx] = true

                while (q.isNotEmpty()) {
                    val p = q.removeFirst()
                    pts += p
                    val py = p / w
                    val px = p % w
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = px + dx
                        val ny = py + dy
                        if (nx !in minX..maxX || ny !in minY..maxY) continue
                        val ni = ny * w + nx
                        if (!visited[ni] && isWhiteDigitPixel(nx, ny)) {
                            visited[ni] = true
                            q.add(ni)
                        }
                    }
                }

                if (pts.size !in 18..220) continue
                var bx0 = w; var by0 = h; var bx1 = -1; var by1 = -1
                var sx = 0L; var sy = 0L
                for (p in pts) {
                    val py = p / w
                    val px = p % w
                    bx0 = min(bx0, px); bx1 = max(bx1, px)
                    by0 = min(by0, py); by1 = max(by1, py)
                    sx += px.toLong(); sy += py.toLong()
                }
                val bw = bx1 - bx0 + 1
                val bh = by1 - by0 + 1
                if (bw !in DIGIT_MIN_W..DIGIT_MAX_W || bh !in DIGIT_MIN_H..DIGIT_MAX_H) continue
                if (bw.toFloat() / bh.toFloat() > 1.35f) continue

                result += DigitBox(bx0, by0, bx1, by1, sx.toInt() / pts.size, sy.toInt() / pts.size)
            }
        }
        return result
    }

    private fun readLevelFromDigit(bitmap: Bitmap, d: DigitBox): Int? {
        val l = d.minX
        val t = d.minY
        val w = d.width
        val h = d.height
        val sample = Array(7) { BooleanArray(5) }

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {
                val x0 = l + gx * w / 5
                val x1 = max(x0 + 1, l + (gx + 1) * w / 5)
                val y0 = t + gy * h / 7
                val y1 = max(y0 + 1, t + (gy + 1) * h / 7)
                var on = 0
                var total = 0
                for (y in y0 until min(t + h, y1)) {
                    for (x in x0 until min(l + w, x1)) {
                        total++
                        val c = bitmap.getPixel(x, y)
                        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
                        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
                        if (mn >= 145 && mx >= 175 && mx - mn <= 95) on++
                    }
                }
                sample[gy][gx] = total > 0 && on * 100 >= total * 14
            }
        }

        var best = -1
        var bestScore = Int.MAX_VALUE
        var second = Int.MAX_VALUE
        for (i in DIGITS.indices) {
            var score = 0
            for (y in 0 until 7) for (x in 0 until 5) {
                if (sample[y][x] != (DIGITS[i][y][x] == '1')) score++
            }
            if (score < bestScore) {
                second = bestScore
                bestScore = score
                best = i + 1
            } else if (score < second) {
                second = score
            }
        }

        val margin = second - bestScore
        if (best !in 1..5) return null
        if (bestScore > 13) return null
        if (bestScore > 8 && margin < 2) return null
        return best
    }

    // ---------------------------------------------------------------
    // 2. DIGIT -> BLUE SHIELD ASSOCIATION
    // ---------------------------------------------------------------

    private fun badgeAroundDigit(bitmap: Bitmap, d: DigitBox): BoundingBox? {
        // In the supplied screenshot the white digit sits inside a ~30x20
        // blue shield. Build a box around the digit and verify blue density.
        val l = max(0, d.centerX - 18)
        val r = min(bitmap.width - 1, d.centerX + 18)
        val t = max(45, d.centerY - 14)
        val b = min(bitmap.height - 126, d.centerY + 15)
        if (r <= l || b <= t) return null

        var blue = 0
        var total = 0
        for (y in t..b step 1) {
            for (x in l..r step 1) {
                total++
                if (isBadgeBlue(bitmap.getPixel(x, y))) blue++
            }
        }
        if (blue < 120 || blue.toFloat() / total < 0.22f) return null

        // Tighten to the blue pixels around the digit.
        var bx0 = bitmap.width; var by0 = bitmap.height
        var bx1 = -1; var by1 = -1
        for (y in t..b) for (x in l..r) {
            if (!isBadgeBlue(bitmap.getPixel(x, y))) continue
            bx0 = min(bx0, x); bx1 = max(bx1, x)
            by0 = min(by0, y); by1 = max(by1, y)
        }
        if (bx1 < bx0 || by1 < by0) return null
        val bw = bx1 - bx0 + 1
        val bh = by1 - by0 + 1
        if (bw !in 20..48 || bh !in 16..34) return null
        if (bw.toFloat() / bh.toFloat() !in 0.85f..2.2f) return null

        return BoundingBox(bx0, by0, bx1, by1)
    }

    private fun isBadgeBlue(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return b >= BLUE_MIN && b - r >= BLUE_R_GAP && b - g >= BLUE_G_GAP && b >= g + 3
    }

    private fun badgeConfidence(bitmap: Bitmap, badge: BoundingBox): Int {
        var blue = 0
        var total = 0
        for (y in badge.minY..badge.maxY step 2) for (x in badge.minX..badge.maxX step 2) {
            total++
            if (isBadgeBlue(bitmap.getPixel(x, y))) blue++
        }
        return if (total == 0) 0 else (blue * 100 / total).coerceIn(0, 100)
    }

    // ---------------------------------------------------------------
    // 3. RESOURCE ARTWORK - LOCAL COMPONENTS, NOT BROAD TERRAIN
    // ---------------------------------------------------------------

    private fun classifyArtwork(bitmap: Bitmap, badge: BoundingBox): TypeResult? {
        // In the game the RSS artwork is immediately LEFT / UP-LEFT of its
        // level shield. Keep this ROI tight so neighbouring tiles and terrain
        // cannot dominate the decision.
        val l = max(0, badge.centerX - 88)
        val r = min(bitmap.width - 1, badge.centerX - 4)
        val t = max(45, badge.centerY - 58)
        val b = min(bitmap.height - 126, badge.centerY + 30)
        if (r <= l || b <= t) return null

        val stone = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            mx - mn <= 42 && mx in 82..225
        }
        val wood = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 78 && rr > gg * 1.08 && gg > bb * 1.02 && rr > 100
        }
        val food = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 145 && gg > 120 && bb < 125 && rr > gg * 0.95 && gg > bb * 1.12
        }
        val cyan = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            bb > 88 && bb > rr * 1.08 && bb >= gg && mx > 100 && mx - mn > mx * 0.18f
        }
        val orange = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 120 && rr > gg * 1.10 && gg > bb * 1.04 && rr - bb > 45
        }

        // Important: orange/brown pixels occur in both Ore and Wood. Ore is
        // therefore accepted only when cyan AND orange signatures occur in the
        // same local object zone. This prevents a nearby Ore from stealing a
        // Woods badge.
        val oreC = cyan.filter { it.area >= 20 && it.cx in 15f..70f }
        val oreO = orange.filter { it.area >= 20 && it.cx in 15f..70f }
        if (oreC.isNotEmpty() && oreO.isNotEmpty()) {
            val score = (62 + min(28, oreC.maxOf { it.area } / 8) +
                min(10, oreO.maxOf { it.area } / 20)).coerceAtMost(96)
            return TypeResult("Ore", score, dominantAverage(bitmap, l, r, t, b, "Ore"))
        }

        // Stone is a large neutral-grey compact object. The position test is
        // critical: isolated grey map rocks elsewhere in the ROI are ignored.
        val stoneObj = stone.filter {
            it.area >= 300 && it.cx in 15f..75f && it.w >= 20 && it.h >= 18
        }.maxByOrNull { it.area }
        if (stoneObj != null) {
            val score = (62 + min(30, stoneObj.area / 90)).coerceAtMost(94)
            return TypeResult("Stone", score, dominantAverage(bitmap, l, r, t, b, "Stone"))
        }

        // Woods have substantial brown timber/stump components in the local
        // zone. Green foliage is deliberately NOT used as the primary signal.
        val woodObj = wood.filter {
            it.area >= 120 && it.cx in 10f..70f && it.w >= 10 && it.h >= 10
        }.maxByOrNull { it.area }
        if (woodObj != null) {
            val score = (60 + min(30, woodObj.area / 18)).coerceAtMost(94)
            return TypeResult("Wood", score, dominantAverage(bitmap, l, r, t, b, "Wood"))
        }

        // Food requires a concentrated yellow field signature. Green terrain
        // alone can never satisfy this test.
        val foodObj = food.filter {
            it.area >= 50 && it.cx in 10f..70f && it.w >= 8 && it.h >= 8
        }.maxByOrNull { it.area }
        if (foodObj != null) {
            val score = (58 + min(34, foodObj.area / 12)).coerceAtMost(94)
            return TypeResult("Food", score, dominantAverage(bitmap, l, r, t, b, "Food"))
        }

        return null
    }

    private data class SignatureComponent(
        val area: Int,
        val cx: Float,
        val cy: Float,
        val w: Int,
        val h: Int
    )

    private fun signatureComponents(
        bitmap: Bitmap,
        l: Int,
        r: Int,
        t: Int,
        b: Int,
        predicate: (Int, Int, Int) -> Boolean
    ): List<SignatureComponent> {
        val w = r - l + 1
        val h = b - t + 1
        val mask = BooleanArray(w * h)
        for (y in t..b) for (x in l..r) {
            val c = bitmap.getPixel(x, y)
            mask[(y - t) * w + (x - l)] = predicate(Color.red(c), Color.green(c), Color.blue(c))
        }

        val seen = BooleanArray(mask.size)
        val out = mutableListOf<SignatureComponent>()
        for (yy in 0 until h) for (xx in 0 until w) {
            val idx = yy * w + xx
            if (!mask[idx] || seen[idx]) continue
            val q = ArrayDeque<Int>()
            q.add(idx)
            seen[idx] = true
            var area = 0
            var x0 = xx; var x1 = xx; var y0 = yy; var y1 = yy
            var sx = 0L; var sy = 0L
            while (q.isNotEmpty()) {
                val p = q.removeFirst()
                val py = p / w
                val px = p % w
                area++
                sx += px.toLong(); sy += py.toLong()
                x0 = min(x0, px); x1 = max(x1, px)
                y0 = min(y0, py); y1 = max(y1, py)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx; val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (!seen[ni] && mask[ni]) {
                        seen[ni] = true
                        q.add(ni)
                    }
                }
            }
            if (area >= 4) {
                out += SignatureComponent(
                    area = area,
                    cx = sx.toFloat() / area,
                    cy = sy.toFloat() / area,
                    w = x1 - x0 + 1,
                    h = y1 - y0 + 1
                )
            }
        }
        return out
    }

    private fun dominantAverage(
        bitmap: Bitmap,
        l: Int, r: Int, t: Int, b: Int,
        type: String
    ): Triple<Int, Int, Int> {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (y in t..b step 2) for (x in l..r step 2) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            val ok = when (type) {
                "Stone" -> {
                    val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
                    mx - mn <= 42 && mx in 82..225
                }
                "Wood" -> rr > 78 && rr > gg * 1.08 && gg > bb * 1.02 && rr > 100
                "Food" -> rr > 145 && gg > 120 && bb < 125 && rr > gg * .95 && gg > bb * 1.12
                "Ore" -> bb > 88 && bb > rr * 1.08
                else -> false
            }
            if (ok) { sr += rr; sg += gg; sb += bb; n++ }
        }
        return if (n == 0) Triple(0, 0, 0)
        else Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    // ---------------------------------------------------------------
    // 4. OCCUPATION - VERY CONSERVATIVE
    // ---------------------------------------------------------------

    private fun detectCompactMarchMarker(bitmap: Bitmap, badge: BoundingBox): Boolean {
        // Red/orange pixels are common in Ore artwork. Only accept a compact
        // red component ABOVE the artwork and clearly separated from the badge.
        val l = max(0, badge.centerX - 60)
        val r = min(bitmap.width - 1, badge.centerX + 22)
        val t = max(45, badge.centerY - 48)
        val b = min(bitmap.height - 126, badge.centerY - 8)
        if (r <= l || b <= t) return false

        var redCount = 0
        for (y in t..b) for (x in l..r) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            if (rr > 205 && rr > gg * 1.7 && rr > bb * 1.6 && gg < 125) redCount++
        }
        return redCount >= 55
    }

    private fun deduplicate(items: List<RssDetection>): List<RssDetection> {
        val out = mutableListOf<RssDetection>()
        for (item in items.sortedByDescending { it.confidence }) {
            if (out.none { abs(it.centerX - item.centerX) < 34 && abs(it.centerY - item.centerY) < 34 }) {
                out += item
            }
        }
        return out
    }

    private data class DigitBox(
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
        val centerX: Int, val centerY: Int
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
    }

    private data class TypeResult(
        val name: String,
        val confidence: Int,
        val avg: Triple<Int, Int, Int>
    )

    data class BoundingBox(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val centerX: Int get() = (minX + maxX) / 2
        val centerY: Int get() = (minY + maxY) / 2
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
