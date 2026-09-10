package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V20 - badge anchored, shape based RSS detector.
 *
 * The detector intentionally uses the level badge as the anchor.  It does not
 * scan the map for generic colours.  A candidate must satisfy:
 *   white level glyph -> nearby blue shield -> compact RSS artwork immediately
 *   left/up-left of that shield.
 *
 * Level recognition is based on stroke geometry rather than the old guessed
 * 5x7 templates.  This matters because the game glyphs are anti-aliased and
 * the old templates repeatedly turned 2 into 4/5.
 */
class ScreenAnalyzer {

    companion object {
        private const val BLUE_MIN = 70
        private const val BLUE_R_GAP = 14
        private const val BLUE_G_GAP = 2
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
            val level = readLevelByStrokeGeometry(bitmap, digit) ?: continue
            val badge = badgeAroundDigit(bitmap, digit) ?: continue
            val art = classifyArtworkNearBadge(bitmap, badge) ?: continue

            // Red/orange pixels are common in Ore artwork.  Occupancy is not
            // inferred from colour alone; this remains a conservative marker.
            val occupied = detectCompactMarchMarker(bitmap, badge)
            val badgeScore = badgeConfidence(bitmap, badge)
            val confidence = (art.confidence * 0.84 + badgeScore * 0.16)
                .toInt().coerceIn(0, 100)

            if (confidence < 68) continue

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

    // ===============================================================
    // 1. FIND THE ACTUAL WHITE GLYPH
    // ===============================================================

    private fun findWhiteDigitCandidates(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<DigitBox> {
        val w = bitmap.width
        val h = bitmap.height
        val visited = BooleanArray(w * h)
        val result = mutableListOf<DigitBox>()

        val minX = max(regionX.first, 145)
        val maxX = min(regionX.last, w - 1)
        val minY = max(regionY.first, 45)
        val maxY = min(regionY.last, h - 125)
        if (maxX <= minX || maxY <= minY) return emptyList()

        fun white(x: Int, y: Int): Boolean {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            val mx = max(r, max(g, b))
            val mn = min(r, min(g, b))
            // White digit with anti-aliasing/shadow.  This is deliberately
            // narrower than normal UI-white detection.
            return mn >= 150 && mx >= 178 && mx - mn <= 105
        }

        for (y in minY..maxY) for (x in minX..maxX) {
            val idx = y * w + x
            if (visited[idx] || !white(x, y)) continue

            val q = ArrayDeque<Int>()
            val pts = ArrayList<Int>(80)
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
                    if (!visited[ni] && white(nx, ny)) {
                        visited[ni] = true
                        q.add(ni)
                    }
                }
            }

            if (pts.size !in 20..260) continue

            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (p in pts) {
                val py = p / w
                val px = p % w
                x0 = min(x0, px); x1 = max(x1, px)
                y0 = min(y0, py); y1 = max(y1, py)
            }
            val bw = x1 - x0 + 1
            val bh = y1 - y0 + 1
            if (bw !in 4..18 || bh !in 10..28) continue
            if (bw.toFloat() / bh.toFloat() > 0.9f) continue

            val cx = (x0 + x1) / 2
            val cy = (y0 + y1) / 2
            // A real level glyph should have a blue shield very close to it.
            if (!hasBlueNear(bitmap, cx, cy, 22, 20)) continue

            result += DigitBox(x0, y0, x1, y1, cx, cy)
        }

        return result
    }

    // ===============================================================
    // 2. LEVEL: STROKE GEOMETRY, NOT THE OLD 5x7 GUESS
    // ===============================================================

    private fun readLevelByStrokeGeometry(bitmap: Bitmap, d: DigitBox): Int? {
        val w = d.width
        val h = d.height
        if (w <= 0 || h <= 0) return null

        // Build a compact normalized mask directly from the actual glyph.
        val rows = FloatArray(7)
        val cols = FloatArray(5)
        val pix = Array(7) { BooleanArray(5) }

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {
                val x0 = d.minX + gx * w / 5
                val x1 = max(x0 + 1, d.minX + (gx + 1) * w / 5)
                val y0 = d.minY + gy * h / 7
                val y1 = max(y0 + 1, d.minY + (gy + 1) * h / 7)
                var on = 0
                var total = 0
                for (y in y0 until min(d.minY + h, y1)) {
                    for (x in x0 until min(d.minX + w, x1)) {
                        total++
                        if (isWhiteGlyph(bitmap.getPixel(x, y))) on++
                    }
                }
                pix[gy][gx] = total > 0 && on * 100 >= total * 18
            }
        }

        for (y in 0 until 7) {
            var n = 0
            for (x in 0 until 5) if (pix[y][x]) n++
            rows[y] = n / 5f
        }
        for (x in 0 until 5) {
            var n = 0
            for (y in 0 until 7) if (pix[y][x]) n++
            cols[x] = n / 7f
        }

        // 1 is a narrow vertical glyph.
        if (w <= 6 && cols[2] >= 0.45f && cols[0] < 0.55f && cols[4] < 0.75f) return 1

        val top = (rows[0] + rows[1]) / 2f
        val middle = (rows[3] + rows[4]) / 2f
        val bottom = (rows[5] + rows[6]) / 2f
        val upperLeft = (cols[0] + cols[1]) / 2f
        val upperRight = (cols[3] + cols[4]) / 2f
        val lowerLeft = (cols[0] + cols[1]) / 2f
        val lowerRight = (cols[3] + cols[4]) / 2f

        // Actual Lords Mobile 2 has a strong bottom stroke and lower-left
        // diagonal mass, but a weak middle horizontal stroke.
        val score2 =
            (top * 30f) +
            (bottom * 35f) +
            (upperRight * 12f) +
            (lowerLeft * 18f) -
            (middle * 30f) -
            (lowerRight * 10f)

        // 3 has both middle and bottom strokes and remains right-heavy in the
        // lower half.
        val score3 =
            (top * 25f) +
            (middle * 30f) +
            (bottom * 30f) +
            (upperRight * 15f) +
            (lowerRight * 18f) -
            (lowerLeft * 16f)

        // 4: middle crossbar + strong right vertical + upper-left diagonal,
        // with no strong top/bottom horizontal bars.
        val score4 =
            (middle * 42f) +
            (upperRight * 30f) +
            (upperLeft * 16f) -
            (top * 24f) -
            (bottom * 20f)

        // 5: top + middle + bottom, with strong upper-left and lower-right.
        val score5 =
            (top * 28f) +
            (middle * 30f) +
            (bottom * 30f) +
            (upperLeft * 18f) +
            (lowerRight * 16f) -
            (upperRight * 8f)

        val scores = arrayOf(2 to score2, 3 to score3, 4 to score4, 5 to score5)
            .sortedByDescending { it.second }

        val best = scores[0]
        val second = scores[1]
        // Reject weak/ambiguous shapes.  It is better to miss a tile than send
        // the wrong level downstream.
        if (best.second < 18f) return null
        if (best.second - second.second < 4f) return null
        return best.first
    }

    private fun isWhiteGlyph(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        return mn >= 150 && mx >= 178 && mx - mn <= 105
    }

    // ===============================================================
    // 3. WHITE GLYPH -> BLUE BADGE
    // ===============================================================

    private fun hasBlueNear(bitmap: Bitmap, cx: Int, cy: Int, rx: Int, ry: Int): Boolean {
        var blue = 0
        var total = 0
        val l = max(0, cx - rx); val r = min(bitmap.width - 1, cx + rx)
        val t = max(45, cy - ry); val b = min(bitmap.height - 126, cy + ry)
        for (y in t..b) for (x in l..r) {
            total++
            if (isBadgeBlue(bitmap.getPixel(x, y))) blue++
        }
        return total > 0 && blue >= 60 && blue.toFloat() / total >= 0.10f
    }

    private fun badgeAroundDigit(bitmap: Bitmap, d: DigitBox): BoundingBox? {
        // The badge extends mostly left/down from the white digit in the game.
        val l = max(0, d.centerX - 26)
        val r = min(bitmap.width - 1, d.centerX + 15)
        val t = max(45, d.centerY - 17)
        val b = min(bitmap.height - 126, d.centerY + 18)
        if (r <= l || b <= t) return null

        var blue = 0
        var x0 = bitmap.width; var y0 = bitmap.height; var x1 = -1; var y1 = -1
        for (y in t..b) for (x in l..r) {
            if (isBadgeBlue(bitmap.getPixel(x, y))) {
                blue++
                x0 = min(x0, x); x1 = max(x1, x)
                y0 = min(y0, y); y1 = max(y1, y)
            }
        }
        if (blue < 60 || x1 < x0 || y1 < y0) return null

        val bw = x1 - x0 + 1
        val bh = y1 - y0 + 1
        if (bw !in 18..52 || bh !in 15..38) return null
        if (bw.toFloat() / bh.toFloat() !in 0.65f..2.6f) return null

        return BoundingBox(x0, y0, x1, y1)
    }

    private fun isBadgeBlue(c: Int): Boolean {
        val r = Color.red(c); val g = Color.green(c); val b = Color.blue(c)
        return b >= BLUE_MIN && b - r >= BLUE_R_GAP && b - g >= BLUE_G_GAP && b >= g
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

    // ===============================================================
    // 4. RESOURCE: ONE LOCAL OBJECT ZONE
    // ===============================================================

    private fun classifyArtworkNearBadge(bitmap: Bitmap, badge: BoundingBox): TypeResult? {
        // The object is immediately left/up-left of the badge.  Keep this zone
        // narrow enough that a neighbouring RSS cannot steal the badge.
        val l = max(0, badge.minX - 72)
        val r = min(bitmap.width - 1, badge.minX - 2)
        val t = max(45, badge.centerY - 48)
        val b = min(bitmap.height - 126, badge.maxY + 18)
        if (r <= l || b <= t) return null

        val stone = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
            val spread = mx - mn
            spread <= 48 && mx in 88..235 && rr >= 75 && gg >= 75 && bb >= 75
        }
        val wood = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 82 && rr > gg * 1.07 && gg > bb * 1.01 && rr - bb > 18
        }
        val yellow = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 145 && gg > 125 && bb < 135 && gg > bb * 1.12 && rr >= gg * .92
        }
        val cyan = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            bb > 88 && bb > rr * 1.08 && bb >= gg * .96 && max(rr, max(gg, bb)) - min(rr, min(gg, bb)) > 35
        }
        val orange = signatureComponents(bitmap, l, r, t, b) { rr, gg, bb ->
            rr > 125 && rr > gg * 1.08 && gg > bb * 1.02 && rr - bb > 42
        }

        // Candidate must be close to the badge and reasonably compact.
        val oreC = cyan.filter { it.area >= 10 && it.distanceToBadge(badge, l, t) <= 72f }
        val oreO = orange.filter { it.area >= 8 && it.distanceToBadge(badge, l, t) <= 72f }
        if (oreC.isNotEmpty() && oreO.isNotEmpty()) {
            val c = oreC.maxByOrNull { it.area }!!
            val o = oreO.maxByOrNull { it.area }!!
            val closeness = max(0f, 1f - c.distanceToBadge(badge, l, t) / 72f)
            val score = (70 + min(18, c.area / 8) + min(8, o.area / 10) + (closeness * 5f).toInt()).coerceAtMost(96)
            return TypeResult("Ore", score, dominantAverage(bitmap, l, r, t, b, "Ore"))
        }

        val stoneObj = stone
            .filter { it.area >= 55 && it.w >= 9 && it.h >= 7 && it.distanceToBadge(badge, l, t) <= 58f }
            .maxByOrNull { it.area }
        if (stoneObj != null) {
            val score = (69 + min(22, stoneObj.area / 18)).coerceAtMost(94)
            return TypeResult("Stone", score, dominantAverage(bitmap, l, r, t, b, "Stone"))
        }

        val woodObj = wood
            .filter { it.area >= 45 && it.w >= 8 && it.h >= 7 && it.distanceToBadge(badge, l, t) <= 60f }
            .maxByOrNull { it.area }
        if (woodObj != null) {
            val score = (68 + min(23, woodObj.area / 10)).coerceAtMost(94)
            return TypeResult("Wood", score, dominantAverage(bitmap, l, r, t, b, "Wood"))
        }

        val foodObj = yellow
            .filter { it.area >= 20 && it.w >= 6 && it.h >= 5 && it.distanceToBadge(badge, l, t) <= 60f }
            .maxByOrNull { it.area }
        if (foodObj != null) {
            val score = (68 + min(24, foodObj.area / 8)).coerceAtMost(94)
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
    ) {
        fun distanceToBadge(badge: BoundingBox, originL: Int, originT: Int): Float {
            val x = originL + cx
            val y = originT + cy
            return kotlin.math.sqrt(
                ((x - badge.minX).toDouble() * (x - badge.minX).toDouble()) +
                    ((y - badge.centerY).toDouble() * (y - badge.centerY).toDouble())
            ).toFloat()
        }
    }

    private fun signatureComponents(
        bitmap: Bitmap,
        l: Int, r: Int, t: Int, b: Int,
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
            q.add(idx); seen[idx] = true
            var area = 0
            var x0 = xx; var x1 = xx; var y0 = yy; var y1 = yy
            var sx = 0L; var sy = 0L
            while (q.isNotEmpty()) {
                val p = q.removeFirst()
                val py = p / w; val px = p % w
                area++; sx += px.toLong(); sy += py.toLong()
                x0 = min(x0, px); x1 = max(x1, px)
                y0 = min(y0, py); y1 = max(y1, py)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx; val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (!seen[ni] && mask[ni]) { seen[ni] = true; q.add(ni) }
                }
            }
            if (area >= 4) out += SignatureComponent(
                area, sx.toFloat() / area, sy.toFloat() / area,
                x1 - x0 + 1, y1 - y0 + 1
            )
        }
        return out
    }

    private fun dominantAverage(
        bitmap: Bitmap, l: Int, r: Int, t: Int, b: Int, type: String
    ): Triple<Int, Int, Int> {
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        for (y in t..b step 2) for (x in l..r step 2) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            val ok = when (type) {
                "Stone" -> {
                    val mx = max(rr, max(gg, bb)); val mn = min(rr, min(gg, bb))
                    mx - mn <= 48 && mx in 88..235
                }
                "Wood" -> rr > 82 && rr > gg * 1.07 && gg > bb * 1.01 && rr - bb > 18
                "Food" -> rr > 145 && gg > 125 && bb < 135 && gg > bb * 1.12
                "Ore" -> bb > 88 && bb > rr * 1.08
                else -> false
            }
            if (ok) { sr += rr; sg += gg; sb += bb; n++ }
        }
        return if (n == 0) Triple(0, 0, 0) else Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
    }

    // ===============================================================
    // 5. OCCUPATION - NEVER USE ORE COLOUR AS MARCH EVIDENCE
    // ===============================================================

    private fun detectCompactMarchMarker(bitmap: Bitmap, badge: BoundingBox): Boolean {
        val l = max(0, badge.centerX - 48)
        val r = min(bitmap.width - 1, badge.minX + 4)
        val t = max(45, badge.minY - 34)
        val b = min(bitmap.height - 126, badge.minY - 3)
        if (r <= l || b <= t) return false

        // Require a compact red component, not scattered red/orange artwork.
        val w = r - l + 1
        val h = b - t + 1
        val mask = BooleanArray(w * h)
        for (y in t..b) for (x in l..r) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c); val gg = Color.green(c); val bb = Color.blue(c)
            mask[(y - t) * w + (x - l)] = rr > 215 && rr > gg * 1.8 && rr > bb * 1.7 && gg < 115
        }

        val seen = BooleanArray(mask.size)
        for (yy in 0 until h) for (xx in 0 until w) {
            val idx = yy * w + xx
            if (!mask[idx] || seen[idx]) continue
            val q = ArrayDeque<Int>(); q.add(idx); seen[idx] = true
            var area = 0; var x0 = xx; var x1 = xx; var y0 = yy; var y1 = yy
            while (q.isNotEmpty()) {
                val p = q.removeFirst(); val py = p / w; val px = p % w
                area++; x0 = min(x0, px); x1 = max(x1, px); y0 = min(y0, py); y1 = max(y1, py)
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx; val ny = py + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (!seen[ni] && mask[ni]) { seen[ni] = true; q.add(ni) }
                }
            }
            if (area >= 18 && area <= 500 && (x1 - x0 + 1) <= 34 && (y1 - y0 + 1) <= 34) return true
        }
        return false
    }

    private fun deduplicate(items: List<RssDetection>): List<RssDetection> {
        val out = mutableListOf<RssDetection>()
        for (item in items.sortedByDescending { it.confidence }) {
            if (out.none { abs(it.centerX - item.centerX) < 28 && abs(it.centerY - item.centerY) < 28 }) {
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
