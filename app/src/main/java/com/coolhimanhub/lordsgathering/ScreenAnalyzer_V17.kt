package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V17 - badge-first RSS detector.
 *
 * Rules:
 *  1) A compact blue level badge is mandatory.
 *  2) The white digit is measured only inside that badge.
 *  3) RSS artwork is searched in several LEFT/UP-LEFT windows around the badge.
 *  4) Green terrain alone can never create Food/Wood.
 *  5) Ambiguous artwork or ambiguous level is rejected.
 *  6) Occupied tiles are rejected by the caller; this detector still marks them.
 */
class ScreenAnalyzer {

    companion object {
        private const val BLUE_MIN = 90
        private const val BLUE_RED_GAP = 28
        private const val BLUE_GREEN_GAP = 8

        private const val BADGE_MIN_W = 12
        private const val BADGE_MAX_W = 52
        private const val BADGE_MIN_H = 12
        private const val BADGE_MAX_H = 46

        // 5x7 compact templates. Anti-aliasing is handled by white-density sampling.
        private val DIGITS = arrayOf(
            arrayOf("00100","01100","00100","00100","00100","00100","01110"), // 1
            arrayOf("11100","00010","00010","00100","01000","10000","11110"), // 2
            arrayOf("11100","00010","00010","01100","00010","00010","11100"), // 3
            arrayOf("00100","01100","10100","10100","11110","00100","00100"), // 4
            arrayOf("11110","10000","10000","11100","00010","00010","11100")  // 5
        )
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {
        if (bitmap.width < 100 || bitmap.height < 100) return emptyList()

        val badges = findLevelBadgeComponents(bitmap, expectedRegionX, expectedRegionY)
        val out = mutableListOf<RssDetection>()

        for (badge in badges) {
            val level = readBadgeLevelV17(bitmap, badge) ?: continue
            val artwork = classifyResourceArtworkV17(bitmap, badge) ?: continue
            val occupied = detectOccupation(bitmap, badge)

            val badgeScore = badgeConfidence(bitmap, badge)
            val confidence = (artwork.confidence * 0.78 + badgeScore * 0.22)
                .toInt().coerceIn(0, 100)

            // Conservative: caller can still apply its own >=75 safety gate.
            if (confidence < 58) continue

            out += RssDetection(
                type = artwork.name,
                level = level,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = artwork.avg
            )
        }

        return deduplicate(out)
    }

    private fun findLevelBadgeComponents(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {
        // 2-pixel sampling catches the small shield while remaining inexpensive.
        val step = 2
        val gw = (bitmap.width + step - 1) / step
        val gh = (bitmap.height + step - 1) / step
        val visited = BooleanArray(gw * gh)
        val result = mutableListOf<BoundingBox>()

        fun valid(gx: Int, gy: Int) = gx in 0 until gw && gy in 0 until gh

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
                b - g >= BLUE_GREEN_GAP &&
                b >= g + 8
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

                    minX = min(minX, cx); maxX = max(maxX, cx)
                    minY = min(minY, cy); maxY = max(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = cx + dx
                            val ny = cy + dy
                            if (!valid(nx, ny)) continue
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
                val aspect = w.toFloat() / h.toFloat()

                if (count >= 6 &&
                    w in BADGE_MIN_W..BADGE_MAX_W &&
                    h in BADGE_MIN_H..BADGE_MAX_H &&
                    aspect in 0.45f..1.9f
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

    private fun readBadgeLevelV17(bitmap: Bitmap, badge: BoundingBox): Int? {
        // Expand slightly because the blue component does not include the white digit.
        val l = max(0, badge.minX - 3)
        val r = min(bitmap.width - 1, badge.maxX + 3)
        val t = max(0, badge.minY - 3)
        val b = min(bitmap.height - 1, badge.maxY + 3)

        val w = r - l + 1
        val h = b - t + 1
        if (w < 5 || h < 7) return null

        // Keep only bright near-white pixels. Ignore the blue shield itself.
        val white = Array(h) { BooleanArray(w) }
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                val c = bitmap.getPixel(l + xx, t + yy)
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                val mn = min(rr, min(gg, bb))
                val mx = max(rr, max(gg, bb))
                white[yy][xx] = mn >= 160 && mx >= 185 && (mx - mn) <= 65
            }
        }

        // Find the compact white digit, not isolated shield highlights.
        var minX = w
        var minY = h
        var maxX = -1
        var maxY = -1
        for (yy in 0 until h) {
            for (xx in 0 until w) {
                if (!white[yy][xx]) continue
                minX = min(minX, xx); maxX = max(maxX, xx)
                minY = min(minY, yy); maxY = max(maxY, yy)
            }
        }
        if (maxX < minX || maxY < minY) return null

        val dw = maxX - minX + 1
        val dh = maxY - minY + 1
        if (dw !in 2..20 || dh !in 5..28) return null
        if (dw > dh * 1.8f) return null

        // Normalize to 5x7 by area occupancy.
        val sample = Array(7) { BooleanArray(5) }
        for (gy in 0 until 7) {
            val y0 = minY + gy * dh / 7
            val y1 = max(y0 + 1, minY + (gy + 1) * dh / 7)
            for (gx in 0 until 5) {
                val x0 = minX + gx * dw / 5
                val x1 = max(x0 + 1, minX + (gx + 1) * dw / 5)
                var on = 0
                var total = 0
                for (yy in y0 until min(y1, h)) {
                    for (xx in x0 until min(x1, w)) {
                        total++
                        if (white[yy][xx]) on++
                    }
                }
                sample[gy][gx] = total > 0 && on * 100 >= total * 18
            }
        }

        var best = -1
        var bestScore = Int.MAX_VALUE
        var second = Int.MAX_VALUE
        for (d in DIGITS.indices) {
            var score = 0
            for (yy in 0 until 7) {
                for (xx in 0 until 5) {
                    if (sample[yy][xx] != (DIGITS[d][yy][xx] == "1"[0])) score++
                }
            }
            if (score < bestScore) {
                second = bestScore
                bestScore = score
                best = d + 1
            } else if (score < second) {
                second = score
            }
        }

        if (best !in 1..5) return null
        // Stronger than V16: don't turn a noisy white highlight into a level.
        val margin = second - bestScore
        if (bestScore > 10) return null
        if (bestScore > 6 && margin < 2) return null

        return best
    }

    private fun classifyResourceArtworkV17(
        bitmap: Bitmap,
        badge: BoundingBox
    ): TypeResult? {
        /*
         * Test several windows because the RSS art moves slightly with map
         * perspective. All windows are left/up-left of the badge.
         */
        val windows = listOf(
            Window(badge.centerX - 78, badge.centerX - 12, badge.centerY - 52, badge.centerY + 8),
            Window(badge.centerX - 68, badge.centerX - 4,  badge.centerY - 42, badge.centerY + 20),
            Window(badge.centerX - 92, badge.centerX - 22, badge.centerY - 28, badge.centerY + 28)
        )

        var best: TypeResult? = null
        for (win in windows) {
            val result = scoreArtworkWindow(bitmap, badge, win)
            if (result != null && (best == null || result.confidence > best.confidence)) {
                best = result
            }
        }
        return best
    }

    private fun scoreArtworkWindow(
        bitmap: Bitmap,
        badge: BoundingBox,
        win: Window
    ): TypeResult? {
        val left = max(0, win.l)
        val right = min(bitmap.width - 1, win.r)
        val top = max(0, win.t)
        val bottom = min(bitmap.height - 1, win.b)
        if (right <= left || bottom <= top) return null

        var n = 0
        var brown = 0
        var cyan = 0
        var yellow = 0
        var brightYellow = 0
        var neutralRock = 0
        var green = 0
        var dark = 0
        var orange = 0
        var red = 0
        var highSat = 0

        var sr = 0L
        var sg = 0L
        var sb = 0L

        for (y in top..bottom step 2) {
            for (x in left..right step 2) {
                if (x in badge.minX..badge.maxX && y in badge.minY..badge.maxY) continue

                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val spread = mx - mn
                val sat = if (mx == 0) 0f else spread.toFloat() / mx

                sr += r; sg += g; sb += b
                n++

                if (sat > 0.35f) highSat++
                if (g > r * 1.08 && g > b * 1.08 && sat > 0.18f) green++

                // Timber/log colors.
                if (r > 75 && r > g * 1.12 && g > b * 1.08) brown++
                if (r > 105 && r > g * 1.18 && g > b * 1.15) orange++

                // Rich vein mineral colors.
                if (b > r * 1.12 && b > g * 1.02 && b > 85 && sat > 0.22f) cyan++

                // Wheat/food.
                if (r > 145 && g > 120 && b < g * 0.82 && r > b * 1.25) yellow++
                if (r > 195 && g > 170 && b < 125) brightYellow++

                // Grey/white rock.
                if (spread < 42 && mx in 85..225) neutralRock++

                if (mx < 70) dark++
                if (r > 170 && r > g * 1.35 && r > b * 1.35) red++
            }
        }

        if (n < 120) return null

        val avg = Triple((sr / n).toInt(), (sg / n).toInt(), (sb / n).toInt())
        val dn = n.toFloat()

        /*
         * Use concentration plus signature, rather than average color.
         * Grass is green, but green is explicitly a penalty for Food/Wood
         * unless there is a strong brown/yellow object signature.
         */
        val stone = (
            neutralRock / dn * 100f +
                min(25f, highSat * 0.5f)
            ).toInt()

        val ore = (
            cyan / dn * 125f +
                min(20f, orange * 1.2f)
            ).toInt()

        val wood = (
            brown / dn * 135f +
                min(18f, orange * 0.8f) -
                min(22f, green * 0.20f)
            ).toInt()

        val food = (
            yellow / dn * 145f +
                min(25f, brightYellow * 1.2f) -
                min(28f, green * 0.22f)
            ).toInt()

        val gold = (
            brightYellow / dn * 155f +
                min(18f, highSat * 0.4f)
            ).toInt()

        val candidates = mutableListOf<Pair<String, Int>>()
        if (stone >= 42 && neutralRock >= n * 0.20f) candidates += "Stone" to min(90, stone + 8)
        if (ore >= 44 && cyan >= n * 0.12f) candidates += "Ore" to min(92, ore + 8)
        if (wood >= 44 && brown >= n * 0.10f && brown > green * 0.55f) candidates += "Wood" to min(90, wood + 8)
        if (food >= 45 && yellow >= n * 0.09f && yellow > green * 0.45f) candidates += "Food" to min(91, food + 8)
        if (gold >= 55 && brightYellow >= n * 0.08f) candidates += "Gold" to min(92, gold + 5)

        if (candidates.isEmpty()) return null

        candidates.sortByDescending { it.second }
        val first = candidates[0]
        val second = candidates.getOrNull(1)?.second ?: 0

        // Don't guess when two resource signatures are close.
        if (second > 0 && first.second - second < 9) return null

        // Reject grass/soil-only windows.
        if (green > n * 0.55f && first.first != "Wood") return null
        if (dark > n * 0.72f) return null

        return TypeResult(first.first, first.second.coerceIn(45, 92), avg)
    }

    private fun detectOccupation(bitmap: Bitmap, badge: BoundingBox): Boolean {
        val l = max(0, badge.minX - 52)
        val r = min(bitmap.width - 1, badge.maxX + 38)
        val t = max(0, badge.minY - 35)
        val b = min(bitmap.height - 1, badge.maxY + 48)

        var strongRed = 0
        var total = 0
        for (y in t..b step 2) {
            for (x in l..r step 2) {
                val c = bitmap.getPixel(x, y)
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                total++
                if (rr > 205 && rr > gg * 1.65 && rr > bb * 1.65 && gg < 125 && bb < 125) {
                    strongRed++
                }
            }
        }
        return total > 0 && strongRed >= 6 && strongRed.toFloat() / total > 0.018f
    }

    private fun badgeConfidence(bitmap: Bitmap, badge: BoundingBox): Int {
        var blue = 0
        var total = 0
        for (y in badge.minY..badge.maxY step 2) {
            for (x in badge.minX..badge.maxX step 2) {
                val c = bitmap.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                total++
                if (b >= BLUE_MIN && b - r >= BLUE_RED_GAP && b - g >= BLUE_GREEN_GAP) blue++
            }
        }
        return if (total == 0) 0 else blue * 100 / total
    }

    private fun deduplicate(items: List<RssDetection>): List<RssDetection> {
        val out = mutableListOf<RssDetection>()
        for (item in items.sortedByDescending { it.confidence }) {
            if (out.none {
                abs(it.centerX - item.centerX) < 30 &&
                    abs(it.centerY - item.centerY) < 30
            }) out += item
        }
        return out
    }

    private data class Window(
        val l: Int,
        val r: Int,
        val t: Int,
        val b: Int
    )

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
