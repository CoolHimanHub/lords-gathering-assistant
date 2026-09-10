package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V23 RSS detector.
 *
 * Detection is deliberately split into independent stages:
 * 1. Find compact blue level badges.
 * 2. Read the white level digit from the badge.
 * 3. Associate the badge with the compact artwork immediately to its left.
 * 4. Reject occupation/combat markers and moving indicators.
 *
 * No tap or accessibility action is performed by this class.
 */
class ScreenAnalyzer {

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
        val dominantColor: Int,
        val moving: Boolean = false,
        val movingScore: Int = 0
    )

    private data class Badge(val box: BoundingBox, val blueRatio: Float, val digit: Int)

    private data class Artwork(
        val type: String,
        val confidence: Int,
        val box: BoundingBox,
        val dominant: Int,
        val foregroundRatio: Float
    )

    companion object {
        private const val OCCUPIED_THRESHOLD = 60
        private const val MOVING_THRESHOLD = 62
        private const val MIN_CONFIDENCE = 64
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 600 || bitmap.height < 400) return emptyList()

        // Full map area; only the fixed HUD/status bands are excluded.
        val left = max(0, expectedRegionX.first)
        val right = min(bitmap.width - 1, expectedRegionX.last)
        val top = max(55, expectedRegionY.first)
        val bottom = min(bitmap.height - 120, expectedRegionY.last)
        if (right <= left || bottom <= top) return emptyList()

        val result = ArrayList<RssDetection>()
        for (badge in findBadges(bitmap, left, right, top, bottom)) {
            val artwork = classifyArtwork(bitmap, badge.box, left, top, right, bottom) ?: continue

            val flag = flagScore(bitmap, badge.box, left, top, right, bottom)
            val occupied = flag >= OCCUPIED_THRESHOLD

            val movement = movingScore(bitmap, badge.box, left, top, right, bottom)
            val moving = movement >= MOVING_THRESHOLD

            // A compact RSS footprint is required. Large combat/monster structures
            // and arbitrary map decorations are intentionally rejected.
            if (artwork.foregroundRatio !in 0.025f..0.48f) continue

            var confidence = (
                badge.blueRatio * 100f * 0.22f +
                    artwork.confidence * 0.62f +
                    artwork.foregroundRatio.coerceIn(0f, 0.20f) * 80f
                ).toInt()

            if (occupied) confidence -= 25
            if (moving) confidence -= 30
            confidence = confidence.coerceIn(0, 100)

            // Hard safety gate. Blocked/moving objects never become candidates.
            if (confidence < MIN_CONFIDENCE || occupied || moving) continue

            result += RssDetection(
                type = artwork.type,
                level = badge.digit,
                centerX = artwork.box.centerX,
                centerY = artwork.box.centerY,
                boundingBox = artwork.box,
                confidence = confidence,
                occupied = false,
                dominantColor = artwork.dominant,
                moving = false,
                movingScore = movement
            )
        }

        return dedupe(result)
    }

    /** Connected-component badge detection rather than overlapping fixed windows. */
    private fun findBadges(
        bitmap: Bitmap,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): List<Badge> {
        val step = 2
        val gw = (right - left) / step + 1
        val gh = (bottom - top) / step + 1
        val visited = BooleanArray(gw * gh)
        val queue = ArrayDeque<Int>()
        val found = ArrayList<Badge>()

        fun isBlueAt(x: Int, y: Int): Boolean {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            return b >= 78 && b - r >= 14 && b >= g * 0.92f && b >= r * 1.06f
        }

        for (gy in 0 until gh) for (gx in 0 until gw) {
            val start = gy * gw + gx
            if (visited[start]) continue
            val sx = min(right, left + gx * step)
            val sy = min(bottom, top + gy * step)
            if (!isBlueAt(sx, sy)) {
                visited[start] = true
                continue
            }

            queue.clear()
            queue.add(start)
            visited[start] = true
            var minGX = gx
            var maxGX = gx
            var minGY = gy
            var maxGY = gy
            var count = 0

            while (queue.isNotEmpty()) {
                val p = queue.removeFirst()
                val py = p / gw
                val px = p % gw
                count++
                minGX = min(minGX, px)
                maxGX = max(maxGX, px)
                minGY = min(minGY, py)
                maxGY = max(maxGY, py)

                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = px + dx
                    val ny = py + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val ni = ny * gw + nx
                    if (visited[ni]) continue
                    val xx = min(right, left + nx * step)
                    val yy = min(bottom, top + ny * step)
                    if (isBlueAt(xx, yy)) {
                        visited[ni] = true
                        queue.add(ni)
                    }
                }
            }

            if (count !in 14..280) continue
            val box = BoundingBox(
                max(left, left + minGX * step - 2),
                max(top, top + minGY * step - 2),
                min(right, left + (maxGX + 1) * step + 2),
                min(bottom, top + (maxGY + 1) * step + 2)
            )
            if (box.width !in 18..58 || box.height !in 12..42) continue
            val aspect = box.width.toFloat() / box.height.toFloat()
            if (aspect !in 0.65f..2.9f) continue

            val ratio = blueRatio(bitmap, box)
            if (ratio < 0.14f) continue
            val digit = readBadgeDigit(bitmap, box) ?: continue
            found += Badge(box, ratio, digit)
        }
        return found
    }

    /**
     * Reads only the inner white glyph, avoiding the blue badge border and nearby UI.
     * The glyph is scored against simplified 2/3/4/5 stroke layouts.
     */
    private fun readBadgeDigit(bitmap: Bitmap, box: BoundingBox): Int? {
        val inner = BoundingBox(
            max(box.minX + 3, box.centerX - 9),
            max(box.minY + 2, box.centerY - 10),
            min(box.maxX - 3, box.centerX + 9),
            min(box.maxY - 2, box.centerY + 10)
        )
        if (inner.width < 7 || inner.height < 9) return null

        val white = ArrayList<Pair<Int, Int>>()
        for (y in inner.minY..inner.maxY) for (x in inner.minX..inner.maxX) {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (r >= 150 && g >= 150 && b >= 150 && max(r, max(g, b)) - min(r, min(g, b)) < 115) {
                white += x to y
            }
        }
        if (white.size < 12) return null

        val x0 = white.minOf { it.first }
        val x1 = white.maxOf { it.first }
        val y0 = white.minOf { it.second }
        val y1 = white.maxOf { it.second }
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w !in 3..17 || h !in 8..24) return null

        val grid = Array(7) { BooleanArray(5) }
        for (gy in 0 until 7) for (gx in 0 until 5) {
            val xa = x0 + gx * w / 5
            val xb = max(xa + 1, x0 + (gx + 1) * w / 5)
            val ya = y0 + gy * h / 7
            val yb = max(ya + 1, y0 + (gy + 1) * h / 7)
            var on = 0
            var total = 0
            for (yy in ya until min(y0 + h, yb)) for (xx in xa until min(x0 + w, xb)) {
                total++
                val c = bitmap.getPixel(xx, yy)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (r >= 150 && g >= 150 && b >= 150 && max(r, max(g, b)) - min(r, min(g, b)) < 115) on++
            }
            grid[gy][gx] = total > 0 && on * 100 >= total * 16
        }

        fun row(y: Int) = grid[y].count { it } / 5f
        fun col(x: Int) = (0 until 7).count { grid[it][x] } / 7f
        val top = (row(0) + row(1)) / 2f
        val mid = (row(3) + row(4)) / 2f
        val bot = (row(5) + row(6)) / 2f
        val leftUpper = (col(0) + col(1)) / 2f
        val rightUpper = (col(3) + col(4)) / 2f
        val leftLower = leftUpper
        val rightLower = rightUpper

        val scores = listOf(
            2 to (top * 26 + mid * 28 + bot * 30 + rightUpper * 16 + leftLower * 17 - leftUpper * 8 - rightLower * 6),
            3 to (top * 25 + mid * 30 + bot * 27 + rightUpper * 20 + rightLower * 18 - leftUpper * 12),
            4 to (mid * 34 + rightUpper * 32 + leftUpper * 17 + rightLower * 12 - top * 12 - bot * 14),
            5 to (top * 29 + mid * 29 + bot * 29 + leftUpper * 20 + rightLower * 15 - rightUpper * 8)
        ).sortedByDescending { it.second }

        if (scores[0].second < 14f) return null
        if (scores[0].second - scores[1].second < 2.0f) return null
        return scores[0].first
    }

    /** Associate artwork with the badge using a smaller, centred footprint. */
    private fun classifyArtwork(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Artwork? {
        val l = max(left, badge.centerX - 72)
        val r = min(right, badge.centerX - 5)
        val t = max(top, badge.centerY - 34)
        val b = min(bottom, badge.centerY + 34)
        if (r <= l || b <= t) return null

        var green = 0
        var cyan = 0
        var warm = 0
        var yellow = 0
        var gray = 0
        var foreground = 0
        var total = 0
        var sr = 0
        var sg = 0
        var sb = 0
        var minX = r
        var minY = b
        var maxX = l
        var maxY = t

        for (y in t..b step 2) for (x in l..r step 2) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c)
            val gg = Color.green(c)
            val bb = Color.blue(c)
            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            val chroma = mx - mn
            total++
            sr += rr
            sg += gg
            sb += bb

            val isGrass = gg > rr * 1.05f && gg > bb * 1.05f && gg > 65
            if (isGrass) {
                green++
                continue
            }
            if (mx < 55) continue
            foreground++
            if (bb > rr * 1.08f && bb >= gg * .96f && chroma > 28) cyan++
            if (rr > gg * 1.08f && gg > bb * 1.02f && rr > 80) warm++
            if (rr > 145 && gg > 125 && bb < 140 && gg > bb * 1.10f) yellow++
            if (chroma < 52 && mx in 80..225) gray++
            if (chroma > 18) {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }

        if (total < 40 || foreground < 12 || maxX <= minX || maxY <= minY) return null
        val fr = foreground.toFloat() / total
        val cr = cyan.toFloat() / foreground
        val wr = warm.toFloat() / foreground
        val yr = yellow.toFloat() / foreground
        val gr = gray.toFloat() / foreground

        // Resource families are primarily separated by their non-grass signature.
        val candidates = listOf(
            "Ore" to (cr * 100f + wr * 18f - gr * 10f),
            "Wood" to (wr * 100f + yr * 12f - cr * 8f),
            "Food" to (yr * 100f + wr * 18f - gr * 8f),
            "Stone" to (gr * 100f + cr * 8f - wr * 6f)
        ).sortedByDescending { it.second }

        val best = candidates[0]
        val second = candidates[1]
        if (best.second < 24f || best.second - second.second < 6f) return null

        val confidence = (58f + best.second * .38f + min(fr, .20f) * 45f).toInt().coerceIn(0, 94)
        return Artwork(
            type = best.first,
            confidence = confidence,
            box = BoundingBox(minX, minY, maxX, maxY),
            dominant = Color.rgb(sr / total, sg / total, sb / total),
            foregroundRatio = fr
        )
    }

    /** Red triangular/occupation marker heuristic. */
    private fun flagScore(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val l = max(left, badge.minX - 28)
        val r = min(right, badge.maxX + 30)
        val t = max(top, badge.minY - 42)
        val b = min(bottom, badge.minY + 12)
        var red = 0
        var strong = 0
        var upper = 0
        var n = 0
        for (y in t..b step 2) for (x in l..r step 2) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c)
            val gg = Color.green(c)
            val bb = Color.blue(c)
            n++
            if (rr > 155 && rr > gg * 1.35f && rr > bb * 1.35f) {
                red++
                if (rr > 205) strong++
                if (y < badge.centerY) upper++
            }
        }
        if (n == 0) return 0
        var score = 0
        if (red >= 5) score += 18
        if (red >= 12) score += 18
        if (strong >= 4) score += 12
        if (upper >= 4) score += 15
        if (red > 100) score -= 20
        return score.coerceIn(0, 100)
    }

    /** Conservative directional/line signature for moving troop/player indicators. */
    private fun movingScore(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val cx = badge.centerX
        val cy = badge.centerY
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
            intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(1, -1), intArrayOf(-1, -1)
        )
        var best = 0
        for (d in dirs) {
            var hits = 0
            var run = 0
            var maxRun = 0
            for (distance in 18..105 step 3) {
                val x = cx + d[0] * distance
                val y = cy + d[1] * distance
                if (x !in left..right || y !in top..bottom) break
                var hit = false
                for (oy in -2..2) for (ox in -2..2) {
                    val xx = x + ox
                    val yy = y + oy
                    if (xx !in left..right || yy !in top..bottom) continue
                    val c = bitmap.getPixel(xx, yy)
                    val rr = Color.red(c)
                    val gg = Color.green(c)
                    val bb = Color.blue(c)
                    val mx = max(rr, max(gg, bb))
                    val mn = min(rr, min(gg, bb))
                    if (mx - mn > 70 && mx > 130) hit = true
                }
                if (hit) {
                    hits++
                    run++
                    maxRun = max(maxRun, run)
                } else run = 0
            }
            best = max(best, (hits * 5 + maxRun * 5).coerceAtMost(100))
        }
        return best
    }

    private fun blueRatio(bitmap: Bitmap, box: BoundingBox): Float {
        var blue = 0
        var total = 0
        for (y in box.minY..box.maxY step 2) for (x in box.minX..box.maxX step 2) {
            total++
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (b >= 78 && b - r >= 14 && b >= g * .92f && b >= r * 1.06f) blue++
        }
        return if (total == 0) 0f else blue.toFloat() / total
    }

    private fun dedupe(input: List<RssDetection>): List<RssDetection> {
        val output = ArrayList<RssDetection>()
        for (detection in input) {
            if (output.none {
                    abs(detection.centerX - it.centerX) + abs(detection.centerY - it.centerY) < 24
                }) output += detection
        }
        return output.sortedWith(compareByDescending<RssDetection> { it.level }.thenByDescending { it.confidence })
    }
}