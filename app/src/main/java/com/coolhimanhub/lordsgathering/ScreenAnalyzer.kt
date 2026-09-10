package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V14 — conservative tile/resource detector.
 *
 * Pipeline:
 * blue level badge -> exact badge number -> resource artwork -> type
 * -> occupation -> confidence -> deduplicate
 *
 * Unknown/ambiguous objects are rejected.
 */
class ScreenAnalyzer {

    companion object {
        private const val STEP = 2

        private const val MIN_BLUE = 100
        private const val BLUE_RED_GAP = 35
        private const val BLUE_GREEN_GAP = 10

        /*
         * 5 x 7 digit templates.
         * Index 0 = Lv1, index 4 = Lv5.
         */
        private val DIGITS = arrayOf(
            arrayOf(
                "00100",
                "01100",
                "00100",
                "00100",
                "00100",
                "00100",
                "01110"
            ),
            arrayOf(
                "11100",
                "00010",
                "00010",
                "00100",
                "01000",
                "10000",
                "11110"
            ),
            arrayOf(
                "11100",
                "00010",
                "00010",
                "01100",
                "00010",
                "00010",
                "11100"
            ),
            arrayOf(
                "00010",
                "00110",
                "01010",
                "10010",
                "11111",
                "00010",
                "00010"
            ),
            arrayOf(
                "11110",
                "10000",
                "10000",
                "11100",
                "00010",
                "00010",
                "11100"
            )
        )
    }

    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 55 until bitmap.height
    ): List<RssDetection> {

        if (bitmap.width < 200 || bitmap.height < 200) {
            return emptyList()
        }

        val badges = findBlueBadges(
            bitmap,
            expectedRegionX,
            expectedRegionY
        )

        val detections = mutableListOf<RssDetection>()

        for (badge in badges) {

            val levelResult = readLevel(bitmap, badge)
                ?: continue

            val resource = classifyResource(
                bitmap,
                badge
            ) ?: continue

            val occupied = detectOccupation(
                bitmap,
                badge
            )

            val confidence =
                (
                    levelResult.second * 0.40 +
                    resource.second * 0.60
                ).toInt().coerceIn(0, 100)

            if (confidence < 65) {
                continue
            }

            detections += RssDetection(
                type = resource.first,
                level = levelResult.first,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = resource.third
            )
        }

        return deduplicate(detections)
    }

    // ---------------------------------------------------------
    // BLUE LEVEL BADGES
    // ---------------------------------------------------------

    private fun findBlueBadges(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {

        val gw = (bitmap.width + STEP - 1) / STEP
        val gh = (bitmap.height + STEP - 1) / STEP

        val visited = BooleanArray(gw * gh)
        val result = mutableListOf<BoundingBox>()

        fun valid(gx: Int, gy: Int): Boolean {
            return gx in 0 until gw && gy in 0 until gh
        }

        fun blue(gx: Int, gy: Int): Boolean {

            val x = gx * STEP
            val y = gy * STEP

            if (x !in regionX || y !in regionY) {
                return false
            }

            val c = bitmap.getPixel(x, y)

            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)

            return b >= MIN_BLUE &&
                b - r >= BLUE_RED_GAP &&
                b - g >= BLUE_GREEN_GAP
        }

        for (gy in 0 until gh) {
            for (gx in 0 until gw) {

                val index = gy * gw + gx

                if (visited[index] || !blue(gx, gy)) {
                    continue
                }

                val queueX = IntArray(4096)
                val queueY = IntArray(4096)

                var head = 0
                var tail = 0

                queueX[tail] = gx
                queueY[tail] = gy
                tail++

                visited[index] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                var pixels = 0

                while (head < tail) {

                    val cx = queueX[head]
                    val cy = queueY[head]
                    head++

                    pixels++

                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {

                            if (dx == 0 && dy == 0) {
                                continue
                            }

                            val nx = cx + dx
                            val ny = cy + dy

                            if (!valid(nx, ny)) {
                                continue
                            }

                            val ni = ny * gw + nx

                            if (!visited[ni] && blue(nx, ny)) {

                                visited[ni] = true

                                if (tail < queueX.size) {
                                    queueX[tail] = nx
                                    queueY[tail] = ny
                                    tail++
                                }
                            }
                        }
                    }
                }

                val width =
                    (maxX - minX + 1) * STEP

                val height =
                    (maxY - minY + 1) * STEP

                /*
                 * Real level badges are small.
                 * Large blue UI panels/buttons are rejected.
                 */
                if (
                    pixels >= 8 &&
                    pixels <= 450 &&
                    width in 14..55 &&
                    height in 14..45 &&
                    width.toFloat() / height.toFloat() in 0.45f..2.2f
                ) {

                    result += BoundingBox(
                        minX * STEP,
                        minY * STEP,
                        min(
                            bitmap.width - 1,
                            (maxX + 1) * STEP - 1
                        ),
                        min(
                            bitmap.height - 1,
                            (maxY + 1) * STEP - 1
                        )
                    )
                }
            }
        }

        return result
    }

    // ---------------------------------------------------------
    // LEVEL READING
    // ---------------------------------------------------------

    private fun readLevel(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Pair<Int, Int>? {

        val w = badge.width
        val h = badge.height

        if (w < 14 || h < 14) {
            return null
        }

        /*
         * Do NOT compare the entire blue badge with a digit.
         *
         * Instead find the white digit inside it.
         */
        val samples = Array(7) {
            BooleanArray(5)
        }

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {

                val x0 =
                    badge.minX +
                        gx * w / 5

                val x1 =
                    badge.minX +
                        (gx + 1) * w / 5

                val y0 =
                    badge.minY +
                        gy * h / 7

                val y1 =
                    badge.minY +
                        (gy + 1) * h / 7

                var white = 0
                var total = 0

                for (y in y0 until max(y0 + 1, y1)) {
                    for (x in x0 until max(x0 + 1, x1)) {

                        if (
                            x < 0 ||
                            y < 0 ||
                            x >= bitmap.width ||
                            y >= bitmap.height
                        ) {
                            continue
                        }

                        val c = bitmap.getPixel(x, y)

                        val r = Color.red(c)
                        val g = Color.green(c)
                        val b = Color.blue(c)

                        total++

                        /*
                         * White number.
                         * Gold/grey anti-aliasing is allowed.
                         */
                        if (
                            r >= 175 &&
                            g >= 175 &&
                            b >= 175 &&
                            max(r, max(g, b)) -
                                min(r, min(g, b)) < 55
                        ) {
                            white++
                        }
                    }
                }

                samples[gy][gx] =
                    total > 0 &&
                        white * 100 >= total * 20
            }
        }

        var bestDigit = -1
        var bestDiff = Int.MAX_VALUE
        var secondBest = Int.MAX_VALUE

        for (digit in 0..4) {

            var diff = 0

            for (y in 0 until 7) {
                for (x in 0 until 5) {

                    val expected =
                        DIGITS[digit][y][x] == '1'

                    if (samples[y][x] != expected) {
                        diff++
                    }
                }
            }

            if (diff < bestDiff) {
                secondBest = bestDiff
                bestDiff = diff
                bestDigit = digit
            } else if (diff < secondBest) {
                secondBest = diff
            }
        }

        if (bestDigit < 0) {
            return null
        }

        /*
         * Reject ambiguous digits.
         */
        if (bestDiff > 13) {
            return null
        }

        if (
            secondBest != Int.MAX_VALUE &&
            secondBest - bestDiff < 2
        ) {
            return null
        }

        val confidence =
            (100 - bestDiff * 6).coerceIn(65, 98)

        return Pair(
            bestDigit + 1,
            confidence
        )
    }

    // ---------------------------------------------------------
    // RESOURCE CLASSIFICATION
    // ---------------------------------------------------------

    private fun classifyResource(
        bitmap: Bitmap,
        badge: BoundingBox
    ): TripleResult? {

        /*
         * Resource artwork is normally LEFT/BOTTOM of the
         * level badge.
         *
         * Keep the window tight so terrain does not dominate.
         */
        val left =
            max(0, badge.centerX - 75)

        val right =
            min(bitmap.width - 1, badge.centerX + 15)

        val top =
            max(0, badge.minY - 5)

        val bottom =
            min(bitmap.height - 1, badge.maxY + 65)

        var total = 0

        var yellow = 0
        var brightYellow = 0

        var green = 0
        var brown = 0

        var grey = 0
        var cyan = 0
        var orange = 0

        var red = 0

        var sumR = 0
        var sumG = 0
        var sumB = 0

        for (y in top..bottom step 3) {
            for (x in left..right step 3) {

                val c = bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                /*
                 * Ignore blue level badge itself.
                 */
                if (
                    b - r > 30 &&
                    b - g > 8
                ) {
                    continue
                }

                total++

                sumR += r
                sumG += g
                sumB += b

                val mx = max(r, max(g, b))
                val mn = min(r, min(g, b))
                val saturation = mx - mn

                if (
                    r > 145 &&
                    g > 125 &&
                    r > b + 30 &&
                    g > b + 20
                ) {
                    yellow++
                }

                if (
                    r > 190 &&
                    g > 165 &&
                    b < 120
                ) {
                    brightYellow++
                }

                if (
                    g > r + 8 &&
                    g > b + 8 &&
                    g > 75
                ) {
                    green++
                }

                if (
                    r > b + 15 &&
                    g > b &&
                    r > 65 &&
                    g > 45
                ) {
                    brown++
                }

                if (
                    saturation < 35 &&
                    mx in 70..210
                ) {
                    grey++
                }

                if (
                    b > r + 25 &&
                    b > g + 5
                ) {
                    cyan++
                }

                if (
                    r > 150 &&
                    g in 60..180 &&
                    b < 100
                ) {
                    orange++
                }

                if (
                    r > 175 &&
                    r > g * 1.45 &&
                    r > b * 1.45
                ) {
                    red++
                }
            }
        }

        if (total < 35) {
            return null
        }

        /*
         * Monsters have strong red/dark signatures.
         * Reject them before resource classification.
         */
        if (
            red.toFloat() / total > 0.10f
        ) {
            return null
        }

        val avgR = sumR / total
        val avgG = sumG / total
        val avgB = sumB / total

        val scores = mutableListOf<Pair<String, Int>>()

        // FOOD — wheat/fields
        if (
            yellow >= total * 0.18 &&
            brightYellow >= total * 0.05
        ) {
            scores += "Food" to
                (70 + min(15, yellow * 20 / total))
        }

        // GOLD — bright yellow/gold
        if (
            brightYellow >= total * 0.12 &&
            avgR > 150 &&
            avgG > 125 &&
            avgB < 125
        ) {
            scores += "Gold" to
                (78 + min(12, brightYellow * 25 / total))
        }

        // WOOD — brown trunks + green foliage
        if (
            brown >= total * 0.15 &&
            green >= total * 0.05
        ) {
            scores += "Wood" to
                (72 + min(13, brown * 20 / total))
        }

        // STONE — grey, low saturation
        if (
            grey >= total * 0.28 &&
            avgB >= avgR - 15 &&
            abs(avgR - avgG) < 35
        ) {
            scores += "Stone" to
                (74 + min(12, grey * 20 / total))
        }

        // ORE — cyan/blue stones mixed with orange/brown
        if (
            cyan >= total * 0.06 &&
            orange >= total * 0.03
        ) {
            scores += "Ore" to
                (78 + min(12, cyan * 25 / total))
        }

        if (scores.isEmpty()) {
            return null
        }

        scores.sortByDescending { it.second }

        val best = scores[0]
        val second =
            scores.getOrNull(1)?.second ?: 0

        /*
         * If two resource types look almost identical,
         * reject rather than guessing.
         */
        if (
            second > 0 &&
            best.second - second < 7
        ) {
            return null
        }

        val confidence =
            best.second.coerceIn(65, 95)

        return TripleResult(
            best.first,
            confidence,
            Triple(avgR, avgG, avgB)
        )
    }

    // ---------------------------------------------------------
    // OCCUPATION
    // ---------------------------------------------------------

    private fun detectOccupation(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Boolean {

        /*
         * Incoming/assigned march indicators are commonly red.
         * Use a reasonably broad local window.
         */
        val left =
            max(0, badge.minX - 75)

        val right =
            min(bitmap.width - 1, badge.maxX + 45)

        val top =
            max(0, badge.minY - 35)

        val bottom =
            min(bitmap.height - 1, badge.maxY + 80)

        var red = 0
        var strongRed = 0
        var total = 0

        for (y in top..bottom step 3) {
            for (x in left..right step 3) {

                val c = bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                total++

                if (
                    r > 165 &&
                    r > g * 1.35 &&
                    r > b * 1.35
                ) {
                    red++

                    if (
                        r > 215 &&
                        g < 105 &&
                        b < 105
                    ) {
                        strongRed++
                    }
                }
            }
        }

        if (total == 0) {
            return false
        }

        return strongRed >= 5 &&
            red.toFloat() / total >= 0.009f
    }

    // ---------------------------------------------------------
    // DEDUPLICATION
    // ---------------------------------------------------------

    private fun deduplicate(
        items: List<RssDetection>
    ): List<RssDetection> {

        val result = mutableListOf<RssDetection>()

        for (
            item in items.sortedByDescending {
                it.confidence
            }
        ) {

            val duplicate =
                result.any {

                    val dx =
                        abs(
                            it.centerX -
                                item.centerX
                        )

                    val dy =
                        abs(
                            it.centerY -
                                item.centerY
                        )

                    dx < 32 && dy < 32
                }

            if (!duplicate) {
                result += item
            }
        }

        return result
    }

    // ---------------------------------------------------------
    // DATA
    // ---------------------------------------------------------

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
