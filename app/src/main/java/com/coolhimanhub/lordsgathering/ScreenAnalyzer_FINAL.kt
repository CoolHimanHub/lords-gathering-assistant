package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Final conservative RSS detector.
 *
 * Pipeline:
 *   blue level-badge candidate -> isolate white digit -> 1..5 level
 *   -> inspect the resource artwork above/left of the badge
 *   -> classify type -> local occupation check -> confidence -> deduplicate
 *
 * A generic blue/grey/green map region is never considered an RSS tile by
 * itself. The small blue level badge is the mandatory first gate.
 */
class ScreenAnalyzer {

    companion object {
        private const val BLUE_MIN = 105
        private const val BLUE_RED_GAP = 35
        private const val BLUE_GREEN_GAP = 12

        private const val BADGE_MIN_W = 14
        private const val BADGE_MAX_W = 46
        private const val BADGE_MIN_H = 14
        private const val BADGE_MAX_H = 42

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
        val detections = mutableListOf<RssDetection>()

        for (badge in boxes) {
            val level = readBadgeLevel(bitmap, badge) ?: continue
            val type = classifyResourceArtwork(bitmap, badge) ?: continue
            val occupied = detectOccupation(bitmap, badge)

            val badgeScore = badgeConfidence(bitmap, badge)
            val confidence = (
                type.confidence * 0.72 +
                    badgeScore * 0.28
                ).toInt().coerceIn(0, 100)

            if (confidence < 55) continue

            detections += RssDetection(
                type = type.name,
                level = level,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = type.avg
            )
        }

        return deduplicate(detections)
    }

    private fun findLevelBadgeComponents(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {
        // 2x sampling is enough for the small level shield while keeping CPU use low.
        val step = 2
        val gw = (bitmap.width + step - 1) / step
        val gh = (bitmap.height + step - 1) / step
        val visited = BooleanArray(gw * gh)
        val result = mutableListOf<BoundingBox>()

        fun inside(x: Int, y: Int) = x in 0 until gw && y in 0 until gh

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
                val start = gy * gw + gx
                if (visited[start] || !isBlue(gx, gy)) continue

                val qx = IntArray(1024)
                val qy = IntArray(1024)
                var head = 0
                var tail = 0

                qx[tail] = gx
                qy[tail++] = gy
                visited[start] = true

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

                // Real level shields are compact. This rejects large HUD/map blue areas.
                if (
                    count >= 8 &&
                    w in BADGE_MIN_W..BADGE_MAX_W &&
                    h in BADGE_MIN_H..BADGE_MAX_H &&
                    w.toFloat() / h.toFloat() in 0.65f..1.65f
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

    /**
     * Finds the white numeral inside the badge instead of matching the entire
     * blue shield. This is much more stable for Lv1/Lv2/Lv3/Lv4/Lv5.
     */
    private fun readBadgeLevel(bitmap: Bitmap, badge: BoundingBox): Int? {
        val l = badge.minX + badge.width / 5
        val r = badge.maxX - badge.width / 5
        val t = badge.minY + badge.height / 6
        val b = badge.maxY - badge.height / 6

        val white = Array(b - t + 1) { BooleanArray(r - l + 1) }

        for (y in t..b) {
            for (x in l..r) {
                val c = bitmap.getPixel(x, y)
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                val brightness = (rr + gg + bb) / 3
                white[y - t][x - l] =
                    rr >= 185 && gg >= 185 && bb >= 185 && brightness >= 192
            }
        }

        var minX = white[0].size
        var minY = white.size
        var maxX = -1
        var maxY = -1

        for (y in white.indices) {
            for (x in white[y].indices) {
                if (white[y][x]) {
                    minX = min(minX, x)
                    maxX = max(maxX, x)
                    minY = min(minY, y)
                    maxY = max(maxY, y)
                }
            }
        }

        if (maxX < minX || maxY < minY) return null

        val digitW = maxX - minX + 1
        val digitH = maxY - minY + 1

        if (digitW !in 3..badge.width || digitH !in 5..badge.height) return null
        if (digitW > digitH * 2) return null

        val samples = Array(7) { BooleanArray(5) }
        for (gy in 0 until 7) {
            for (gx in 0 until 5) {
                val x0 = minX + gx * digitW / 5
                val x1 = max(x0 + 1, minX + (gx + 1) * digitW / 5)
                val y0 = minY + gy * digitH / 7
                val y1 = max(y0 + 1, minY + (gy + 1) * digitH / 7)

                var on = 0
                var total = 0
                for (yy in y0 until min(y1, white.size)) {
                    for (xx in x0 until min(x1, white[yy].size)) {
                        total++
                        if (white[yy][xx]) on++
                    }
                }
                samples[gy][gx] = total > 0 && on * 100 >= total * 20
            }
        }

        var bestDigit = -1
        var bestDiff = Int.MAX_VALUE
        var secondBest = Int.MAX_VALUE

        for (d in DIGITS.indices) {
            var diff = 0
            for (y in 0 until 7) {
                for (x in 0 until 5) {
                    if (samples[y][x] != (DIGITS[d][y][x] == '1')) diff++
                }
            }

            if (diff < bestDiff) {
                secondBest = bestDiff
                bestDiff = diff
                bestDigit = d + 1
            } else if (diff < secondBest) {
                secondBest = diff
            }
        }

        // Strong match OR clear winner. Do not guess between levels.
        val margin = secondBest - bestDiff
        if (bestDigit !in 1..5) return null
        if (bestDiff > 12) return null
        if (margin < 2 && bestDiff > 7) return null

        return bestDigit
    }

    private fun classifyResourceArtwork(
        bitmap: Bitmap,
        badge: BoundingBox
    ): TypeResult? {
        // In the actual game layout the resource art sits mostly above/left of
        // the level shield. Never use the lower-right HUD/terrain area.
        val left = max(0, badge.centerX - 82)
        val right = min(bitmap.width - 1, badge.centerX + 26)
        val top = max(0, badge.minY - 78)
        val bottom = min(bitmap.height - 1, badge.maxY + 8)

        val samples = mutableListOf<Triple<Int, Int, Int>>()
        var brown = 0
        var cyan = 0
        var yellow = 0
        var green = 0
        var grey = 0
        var brightYellow = 0
        var highContrast = 0

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

                // Skip map/UI blues and weak background pixels.
                if (b - r > 42 && b - g > 15) continue
                if (mx < 55) continue

                val saturation = if (mx == 0) 0f else spread.toFloat() / mx

                samples += Triple(r, g, b)
                if (spread > 60) highContrast++

                if (r > g * 1.15 && g > b * 1.05 && r > 85) brown++
                if (b > r * 1.18 && b > g * 1.03 && b > 95) cyan++
                if (r > 150 && g > 125 && b < g * 0.85) yellow++
                if (g > r * 1.06 && g > b * 1.08 && saturation > 0.18f) green++
                if (spread < 38 && mx in 80..210) grey++
                if (r > 195 && g > 175 && b < 120) brightYellow++
            }
        }

        if (samples.size < 45) return null

        val avg = Triple(
            samples.sumOf { it.first } / samples.size,
            samples.sumOf { it.second } / samples.size,
            samples.sumOf { it.third } / samples.size
        )

        // Require a meaningful icon signature, not just grass/soil.
        val denom = samples.size.toFloat()
        val scores = mutableListOf<Pair<String, Int>>()

        // Stone: mostly neutral grey/white rock pixels + strong local contrast.
        val stoneScore = (
            (grey / denom * 100f).toInt() +
                min(20, highContrast * 2)
            ).coerceIn(0, 100)
        if (grey >= samples.size * 0.22f && stoneScore >= 45) {
            scores += "Stone" to min(86, stoneScore + 18)
        }

        // Ore / Rich Vein: blue-cyan minerals are noticeably more saturated than stone.
        val oreScore = (
            (cyan / denom * 110f).toInt() +
                min(20, highContrast)
            ).coerceIn(0, 100)
        if (cyan >= samples.size * 0.16f && oreScore >= 48) {
            scores += "Ore" to min(88, oreScore + 12)
        }

        // Woods: brown timber/trunks with some green foliage.
        val woodScore = (
            (brown / denom * 105f).toInt() +
                min(18, (green * 18f / samples.size).toInt())
            ).coerceIn(0, 100)
        if (brown >= samples.size * 0.12f && woodScore >= 45) {
            scores += "Wood" to min(86, woodScore + 12)
        }

        // Food: wheat/yellow field signature.
        val foodScore = (
            (yellow / denom * 115f).toInt() +
                min(18, brightYellow * 20 / samples.size.coerceAtLeast(1))
            ).coerceIn(0, 100)
        if (yellow >= samples.size * 0.11f && foodScore >= 46) {
            scores += "Food" to min(88, foodScore + 10)
        }

        // Gold is much brighter/yellower than normal food.
        val goldScore = (
            (brightYellow / denom * 125f).toInt() +
                min(15, highContrast)
            ).coerceIn(0, 100)
        if (brightYellow >= samples.size * 0.08f && avg.first > 150 && goldScore >= 52) {
            scores += "Gold" to min(92, goldScore + 14)
        }

        if (scores.isEmpty()) return null
        scores.sortByDescending { it.second }

        val best = scores.first()
        val second = scores.getOrNull(1)?.second ?: 0
        val margin = best.second - second

        // Ambiguous icons are rejected instead of guessed.
        if (second > 0 && margin < 10) return null

        return TypeResult(
            name = best.first,
            confidence = best.second.coerceIn(45, 92),
            avg = avg
        )
    }

    /**
     * A march/occupancy marker is local to a resource. Global red HUD dots are
     * outside this small window and therefore do not mark a tile occupied.
     */
    private fun detectOccupation(bitmap: Bitmap, badge: BoundingBox): Boolean {
        val l = max(0, badge.minX - 48)
        val r = min(bitmap.width - 1, badge.maxX + 36)
        val t = max(0, badge.minY - 30)
        val b = min(bitmap.height - 1, badge.maxY + 45)

        var strongRed = 0
        var total = 0

        for (y in t..b step 2) {
            for (x in l..r step 2) {
                val c = bitmap.getPixel(x, y)
                val rr = Color.red(c)
                val gg = Color.green(c)
                val bb = Color.blue(c)
                total++

                if (rr > 205 && rr > gg * 1.65 && rr > bb * 1.65 && gg < 120 && bb < 120) {
                    strongRed++
                }
            }
        }

        return strongRed >= 6 && strongRed.toFloat() / total.toFloat() > 0.018f
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

        return if (total == 0) 0 else (blue * 100 / total).coerceIn(0, 100)
    }

    private fun deduplicate(items: List<RssDetection>): List<RssDetection> {
        val out = mutableListOf<RssDetection>()
        for (item in items.sortedByDescending { it.confidence }) {
            if (out.none {
                    abs(it.centerX - item.centerX) < 32 &&
                        abs(it.centerY - item.centerY) < 32
                }) {
                out += item
            }
        }
        return out
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
