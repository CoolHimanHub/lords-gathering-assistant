package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V22 - conservative RSS detector with occupancy + movement blocking.
 *
 * Rules:
 * 1) A compact blue level badge must be paired with plausible RSS artwork.
 * 2) A red triangular occupation marker blocks the tile.
 * 3) A visible directional/line-like moving marker near the tile blocks it.
 *
 * IMPORTANT:
 * A single screenshot cannot prove that an object is moving. V22 therefore
 * only reports "moving=true" when a conservative line/arrow signature is
 * visible. It never assumes movement when the signature is weak.
 *
 * Coordinates returned are SCREEN PIXELS.
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

    private data class Badge(
        val box: BoundingBox,
        val blueRatio: Float,
        val digit: Int
    )

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 600 || bitmap.height < 400) return emptyList()

        val left = max(105, expectedRegionX.first)
        val right = min(1290, min(bitmap.width - 1, expectedRegionX.last))
        val top = max(65, expectedRegionY.first)
        val bottom = min(bitmap.height - 120, expectedRegionY.last)
        if (right <= left || bottom <= top) return emptyList()

        val badges = findBadges(bitmap, left, right, top, bottom)
        val out = ArrayList<RssDetection>()

        for (badge in badges) {
            val art = classifyArtwork(bitmap, badge.box, left, top, right, bottom)
                ?: continue

            val flag = flagScore(bitmap, badge.box, left, top, right, bottom)
            val occupied = flag >= OCCUPIED_THRESHOLD

            val movement = movingScore(
                bitmap,
                badge.box,
                left,
                top,
                right,
                bottom
            )
            val moving = movement >= MOVING_THRESHOLD

            val local = localQuality(bitmap, badge.box, art.box)

            var confidence = (
                badge.blueRatio * 100f * 0.28f +
                    art.confidence * 0.56f +
                    local * 0.16f
                ).toInt()

            if (occupied) confidence -= 8
            if (moving) confidence -= 5

            confidence = confidence.coerceIn(0, 100)
            if (confidence < 72) continue

            out += RssDetection(
                type = art.type,
                level = badge.digit,
                centerX = badge.box.centerX,
                centerY = badge.box.centerY,
                boundingBox = badge.box,
                confidence = confidence,
                occupied = occupied,
                dominantColor = art.dominant,
                moving = moving,
                movingScore = movement
            )
        }

        return dedupe(out)
    }

    private fun findBadges(
        bitmap: Bitmap,
        left: Int,
        right: Int,
        top: Int,
        bottom: Int
    ): List<Badge> {
        val step = 2
        val gw = (right - left + step) / step
        val gh = (bottom - top + step) / step
        val visited = BooleanArray(gw * gh)
        val q = ArrayDeque<Int>()
        val found = ArrayList<Badge>()

        fun blue(x: Int, y: Int): Boolean {
            val c = bitmap.getPixel(x, y)
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            return b >= 82 &&
                b - r >= 16 &&
                b >= g * 0.94f &&
                b >= r * 1.08f
        }

        for (gy in 0 until gh) for (gx in 0 until gw) {
            val idx = gy * gw + gx
            if (visited[idx]) continue

            val sx = min(right, left + gx * step)
            val sy = min(bottom, top + gy * step)

            if (!blue(sx, sy)) {
                visited[idx] = true
                continue
            }

            q.clear()
            q.add(idx)
            visited[idx] = true

            var minGX = gx
            var maxGX = gx
            var minGY = gy
            var maxGY = gy
            var count = 0

            while (q.isNotEmpty()) {
                val p = q.removeFirst()
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

                    if (blue(xx, yy)) {
                        visited[ni] = true
                        q.add(ni)
                    }
                }
            }

            if (count !in 18..220) continue

            val box = BoundingBox(
                max(left, left + minGX * step - 2),
                max(top, top + minGY * step - 2),
                min(right, left + (maxGX + 1) * step + 2),
                min(bottom, top + (maxGY + 1) * step + 2)
            )

            if (box.width !in 18..58 || box.height !in 12..42) continue

            val aspect = box.width.toFloat() / box.height.toFloat()
            if (aspect !in 0.65f..2.7f) continue

            val digit = readBadgeDigit(bitmap, box) ?: continue
            val ratio = blueRatio(bitmap, box)
            if (ratio < 0.16f) continue

            found += Badge(box, ratio, digit)
        }

        return found
    }

    private fun readBadgeDigit(bitmap: Bitmap, box: BoundingBox): Int? {
        val cx = box.centerX()
        val cy = box.centerY()
        val samples = ArrayList<Pair<Int, Int>>()

        for (y in max(box.minY, cy - 12)..min(box.maxY, cy + 12)) {
            for (x in max(box.minX, cx - 12)..min(box.maxX, cx + 12)) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (
                    r >= 155 &&
                    g >= 155 &&
                    b >= 155 &&
                    max(r, max(g, b)) - min(r, min(g, b)) < 110
                ) {
                    samples += x to y
                }
            }
        }

        if (samples.size < 12) return null

        val x0 = samples.minOf { it.first }
        val x1 = samples.maxOf { it.first }
        val y0 = samples.minOf { it.second }
        val y1 = samples.maxOf { it.second }

        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        if (w !in 3..17 || h !in 8..25) return null

        val grid = Array(7) { BooleanArray(5) }

        for (gy in 0 until 7) for (gx in 0 until 5) {
            val xa = x0 + gx * w / 5
            val xb = max(xa + 1, x0 + (gx + 1) * w / 5)
            val ya = y0 + gy * h / 7
            val yb = max(ya + 1, y0 + (gy + 1) * h / 7)

            var on = 0
            var total = 0

            for (yy in ya until min(y0 + h, yb))
                for (xx in xa until min(x0 + w, xb)) {
                    total++
                    val c = bitmap.getPixel(xx, yy)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)

                    if (
                        r >= 155 &&
                        g >= 155 &&
                        b >= 155 &&
                        max(r, max(g, b)) - min(r, min(g, b)) < 110
                    ) on++
                }

            grid[gy][gx] = total > 0 && on * 100 >= total * 18
        }

        fun row(y: Int) = grid[y].count { it } / 5f
        fun col(x: Int) = (0 until 7).count { grid[it][x] } / 7f

        val top = (row(0) + row(1)) / 2f
        val mid = (row(3) + row(4)) / 2f
        val bot = (row(5) + row(6)) / 2f
        val ul = (col(0) + col(1)) / 2f
        val ur = (col(3) + col(4)) / 2f
        val ll = ul
        val lr = ur

        if (w <= 6 && col(2) >= .45f && col(0) < .55f) return 1

        val scores = listOf(
            2 to (top * 30 + bot * 34 + ur * 12 + ll * 18 - mid * 28 - lr * 8),
            3 to (top * 24 + mid * 31 + bot * 30 + ur * 18 + lr * 14 - ll * 15),
            4 to (mid * 42 + ur * 30 + ul * 15 - top * 24 - bot * 18),
            5 to (top * 30 + mid * 30 + bot * 30 + ul * 18 + lr * 16 - ur * 8)
        ).sortedByDescending { it.second }

        if (scores[0].second < 16f) return null
        if (scores[0].second - scores[1].second < 3.5f) return null

        return scores[0].first
    }

    private data class Art(
        val type: String,
        val confidence: Int,
        val box: BoundingBox,
        val dominant: Int
    )

    private fun classifyArtwork(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Art? {
        val l = max(left, badge.minX - 82)
        val r = min(right, badge.minX - 3)
        val t = max(top, badge.centerY - 48)
        val b = min(bottom, badge.maxY + 20)

        if (r <= l || b <= t) return null

        var blue = 0
        var cyan = 0
        var warm = 0
        var yellow = 0
        var gray = 0
        var total = 0

        var minX = r
        var minY = b
        var maxX = l
        var maxY = t

        var sumR = 0
        var sumG = 0
        var sumB = 0

        for (y in t..b step 2) for (x in l..r step 2) {
            if (insideLikelyHud(x, y)) continue

            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c)
            val gg = Color.green(c)
            val bb = Color.blue(c)

            val mx = max(rr, max(gg, bb))
            val mn = min(rr, min(gg, bb))
            val ch = mx - mn

            total++
            sumR += rr
            sumG += gg
            sumB += bb

            if (bb > rr * 1.10f && bb >= gg * .96f && bb > 85) blue++
            if (bb > rr * 1.08f && bb >= gg * .96f && ch > 32) cyan++
            if (rr > gg * 1.08f && gg > bb * 1.02f && rr > 80) warm++
            if (rr > 145 && gg > 125 && bb < 135 && gg > bb * 1.12f) yellow++
            if (ch < 48 && mx in 80..225) gray++

            if (ch > 20 && (rr > 70 || gg > 70 || bb > 70)) {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
        }

        if (total < 30 || maxX <= minX || maxY <= minY) return null

        val cyanR = cyan.toFloat() / total
        val warmR = warm.toFloat() / total
        val yellowR = yellow.toFloat() / total
        val grayR = gray.toFloat() / total
        val blueR = blue.toFloat() / total

        val candidates = listOf(
            "Ore" to (cyanR * 120 + warmR * 30 - blueR * 20),
            "Wood" to (warmR * 105 + yellowR * 20 - blueR * 15),
            "Food" to (yellowR * 120 + warmR * 20 - blueR * 20),
            "Stone" to (grayR * 115 + (1f - blueR) * 20 - warmR * 10)
        ).sortedByDescending { it.second }

        val best = candidates[0]
        val second = candidates[1]

        if (best.second < 10f || best.second - second.second < 4f) return null

        val conf = (55 + best.second * 1.35f).toInt().coerceIn(0, 94)
        val dominant = Color.rgb(
            sumR / total,
            sumG / total,
            sumB / total
        )

        return Art(
            best.first,
            conf,
            BoundingBox(minX, minY, maxX, maxY),
            dominant
        )
    }

    /**
     * Detects the triangular red occupation marker.
     * This is intentionally conservative.
     */
    private fun flagScore(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val l = max(left, badge.minX - 24)
        val r = min(right, badge.maxX + 30)
        val t = max(top, badge.minY - 40)
        val b = min(bottom, badge.minY + 10)

        var red = 0
        var strong = 0
        var samples = 0
        var redTop = 0

        for (y in t..b step 2) for (x in l..r step 2) {
            val c = bitmap.getPixel(x, y)
            val rr = Color.red(c)
            val gg = Color.green(c)
            val bb = Color.blue(c)
            samples++

            if (rr >= 150 && rr > gg * 1.35f && rr > bb * 1.25f) {
                red++
                if (rr >= 190 && rr > gg * 1.45f && rr > bb * 1.35f) strong++
                if (y < badge.centerY - 8) redTop++
            }
        }

        if (samples == 0) return 0

        var score = 0
        if (red >= 5) score += 18
        if (red >= 12) score += 18
        if (strong >= 3) score += 12
        if (redTop >= 3) score += 15
        if (red > 100) score -= 20

        return score.coerceIn(0, 100)
    }

    /**
     * Conservative single-frame movement/approach detector.
     *
     * It looks for a thin, high-chroma line-like run extending outward from
     * the badge area. It does NOT claim movement from ordinary terrain or
     * from a broad coloured object.
     */
    private fun movingScore(
        bitmap: Bitmap,
        badge: BoundingBox,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int {
        val cx = badge.centerX()
        val cy = badge.centerY()

        // 8 directions; each ray begins outside the badge.
        val directions = arrayOf(
            intArrayOf(1, 0),
            intArrayOf(-1, 0),
            intArrayOf(0, 1),
            intArrayOf(0, -1),
            intArrayOf(1, 1),
            intArrayOf(-1, 1),
            intArrayOf(1, -1),
            intArrayOf(-1, -1)
        )

        var best = 0

        for (d in directions) {
            var chroma = 0
            var bright = 0
            var narrow = 0
            var run = 0
            var maxRun = 0

            for (dist in 18..105 step 3) {
                val x = cx + d[0] * dist
                val y = cy + d[1] * dist

                if (x !in left..right || y !in top..bottom) break

                var localHit = false

                // Small perpendicular thickness: moving arrows/lines are thin.
                for (w in -2..2) {
                    val xx = x + if (d[1] != 0) w else 0
                    val yy = y + if (d[0] != 0) w else 0

                    if (xx !in left..right || yy !in top..bottom) continue

                    val c = bitmap.getPixel(xx, yy)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)

                    val mx = max(r, max(g, b))
                    val mn = min(r, min(g, b))
                    val ch = mx - mn

                    // Ignore near-neutral terrain/roads.
                    if (ch >= 45 && mx >= 100) {
                        localHit = true
                        chroma++
                        if (mx >= 145) bright++
                    }
                }

                if (localHit) {
                    narrow++
                    run++
                    maxRun = max(maxRun, run)
                } else {
                    run = 0
                }
            }

            // A genuine-looking line needs repeated thin hits and continuity.
            val score =
                narrow * 2 +
                    chroma +
                    bright / 2 +
                    maxRun * 3

            best = max(best, score)
        }

        return best.coerceIn(0, 100)
    }

    private fun localQuality(
        bitmap: Bitmap,
        badge: BoundingBox,
        art: BoundingBox
    ): Int {
        val gap = abs(badge.minX - art.maxX)
        if (gap > 34) return 20
        if (art.width !in 12..100 || art.height !in 10..70) return 25
        return 85
    }

    private fun blueRatio(
        bitmap: Bitmap,
        box: BoundingBox
    ): Float {
        var n = 0
        var b = 0

        for (y in box.minY..box.maxY step 2)
            for (x in box.minX..box.maxX step 2) {
                n++
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val bb = Color.blue(c)

                if (bb >= 82 && bb - r >= 16 && bb >= g * .94f) b++
            }

        return if (n == 0) 0f else b.toFloat() / n
    }

    private fun insideLikelyHud(x: Int, y: Int): Boolean =
        y < 72 || x < 105 || x > 1290 || y > 600

    private fun dedupe(
        input: List<RssDetection>
    ): List<RssDetection> {
        val out = ArrayList<RssDetection>()

        for (c in input) {
            val i = out.indexOfFirst {
                abs(it.centerX - c.centerX) < 38 &&
                    abs(it.centerY - c.centerY) < 38
            }

            if (i < 0) {
                out += c
            } else if (c.confidence > out[i].confidence) {
                out[i] = c
            }
        }

        return out.sortedWith(
            compareByDescending<RssDetection> { it.confidence }
                .thenByDescending { it.level }
        )
    }

    companion object {
        private const val OCCUPIED_THRESHOLD = 58
        private const val MOVING_THRESHOLD = 48
    }
}
