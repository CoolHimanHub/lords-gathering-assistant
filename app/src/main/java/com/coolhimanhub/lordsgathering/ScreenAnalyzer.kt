package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V15
 *
 * Conservative Lords Mobile RSS detector.
 *
 * Pipeline:
 *   blue level badge
 *        -> isolate white number
 *        -> normalize digit
 *        -> inspect nearby tile artwork
 *        -> classify resource
 *        -> occupation check
 *        -> confidence
 *        -> deduplicate
 *
 * IMPORTANT:
 * Ambiguous detections are rejected.
 */
class ScreenAnalyzer {

    companion object {

        private const val STEP = 2

        private const val MIN_BLUE = 105
        private const val BLUE_RED_GAP = 32
        private const val BLUE_GREEN_GAP = 8

        /*
         * 5 x 7 templates for digits 1..5.
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

        val output = mutableListOf<RssDetection>()

        for (badge in badges) {

            val level = readLevel(bitmap, badge)
                ?: continue

            val resource = classifyResource(
                bitmap,
                badge
            )
                ?: continue

            val occupied = detectOccupation(
                bitmap,
                badge
            )

            val confidence =
                (
                    level.second * 0.45 +
                    resource.second * 0.55
                ).toInt().coerceIn(0, 100)

            if (confidence < 68) {
                continue
            }

            output += RssDetection(
                type = resource.first,
                level = level.first,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = resource.third
            )
        }

        return deduplicate(output)
    }

    // =========================================================
    // BLUE LEVEL BADGE DETECTION
    // =========================================================

    private fun findBlueBadges(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {

        val gw =
            (bitmap.width + STEP - 1) / STEP

        val gh =
            (bitmap.height + STEP - 1) / STEP

        val visited =
            BooleanArray(gw * gh)

        val result =
            mutableListOf<BoundingBox>()

        fun inside(
            x: Int,
            y: Int
        ): Boolean {
            return x in 0 until gw &&
                y in 0 until gh
        }

        fun isBlue(
            gx: Int,
            gy: Int
        ): Boolean {

            val x = gx * STEP
            val y = gy * STEP

            if (x !in regionX || y !in regionY) {
                return false
            }

            val c =
                bitmap.getPixel(x, y)

            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)

            return b >= MIN_BLUE &&
                b - r >= BLUE_RED_GAP &&
                b - g >= BLUE_GREEN_GAP
        }

        for (gy in 0 until gh) {
            for (gx in 0 until gw) {

                val index =
                    gy * gw + gx

                if (
                    visited[index] ||
                    !isBlue(gx, gy)
                ) {
                    continue
                }

                val qx =
                    IntArray(4096)

                val qy =
                    IntArray(4096)

                var head = 0
                var tail = 0

                qx[tail] = gx
                qy[tail] = gy
                tail++

                visited[index] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy

                var count = 0

                while (head < tail) {

                    val cx = qx[head]
                    val cy = qy[head]

                    head++

                    count++

                    minX = min(minX, cx)
                    maxX = max(maxX, cx)
                    minY = min(minY, cy)
                    maxY = max(maxY, cy)

                    for (dy in -1..1) {
                        for (dx in -1..1) {

                            if (
                                dx == 0 &&
                                dy == 0
                            ) {
                                continue
                            }

                            val nx =
                                cx + dx

                            val ny =
                                cy + dy

                            if (
                                !inside(nx, ny)
                            ) {
                                continue
                            }

                            val ni =
                                ny * gw + nx

                            if (
                                !visited[ni] &&
                                isBlue(nx, ny)
                            ) {

                                visited[ni] = true

                                if (
                                    tail <
                                    qx.size
                                ) {
                                    qx[tail] = nx
                                    qy[tail] = ny
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
                 * Level badges are compact.
                 *
                 * This rejects most blue UI elements.
                 */
                if (
                    count in 8..500 &&
                    width in 14..58 &&
                    height in 14..48 &&
                    width.toFloat() /
                    height.toFloat() in 0.45f..2.1f
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

    // =========================================================
    // V15 LEVEL READER
    // =========================================================

    private fun readLevel(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Pair<Int, Int>? {

        if (
            badge.width < 12 ||
            badge.height < 12
        ) {
            return null
        }

        /*
         * First find white pixels inside the badge.
         *
         * This is the important V15 change.
         */
        val whitePixels =
            mutableListOf<Pair<Int, Int>>()

        for (
            y in badge.minY..badge.maxY
        ) {
            for (
                x in badge.minX..badge.maxX
            ) {

                val c =
                    bitmap.getPixel(x, y)

                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)

                if (
                    r >= 165 &&
                    g >= 165 &&
                    b >= 165 &&
                    max(r, max(g, b)) -
                    min(r, min(g, b)) < 70
                ) {
                    whitePixels +=
                        Pair(x, y)
                }
            }
        }

        /*
         * No white numeral = not a valid level badge.
         */
        if (whitePixels.size < 5) {
            return null
        }

        var digitMinX =
            whitePixels.minOf { it.first }

        var digitMaxX =
            whitePixels.maxOf { it.first }

        var digitMinY =
            whitePixels.minOf { it.second }

        var digitMaxY =
            whitePixels.maxOf { it.second }

        /*
         * Remove tiny anti-aliasing noise by requiring
         * the digit to occupy a reasonable part of the badge.
         */
        val digitWidth =
            digitMaxX - digitMinX + 1

        val digitHeight =
            digitMaxY - digitMinY + 1

        if (
            digitWidth < 3 ||
            digitHeight < 5 ||
            digitWidth > badge.width ||
            digitHeight > badge.height
        ) {
            return null
        }

        /*
         * Slightly pad the extracted digit.
         */
        digitMinX =
            max(
                badge.minX,
                digitMinX - 1
            )

        digitMaxX =
            min(
                badge.maxX,
                digitMaxX + 1
            )

        digitMinY =
            max(
                badge.minY,
                digitMinY - 1
            )

        digitMaxY =
            min(
                badge.maxY,
                digitMaxY + 1
            )

        val samples =
            Array(7) {
                BooleanArray(5)
            }

        val dw =
            digitMaxX - digitMinX + 1

        val dh =
            digitMaxY - digitMinY + 1

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {

                val x0 =
                    digitMinX +
                        gx * dw / 5

                val x1 =
                    digitMinX +
                        (gx + 1) * dw / 5

                val y0 =
                    digitMinY +
                        gy * dh / 7

                val y1 =
                    digitMinY +
                        (gy + 1) * dh / 7

                var white = 0
                var total = 0

                for (
                    y in y0 until
                        max(y0 + 1, y1)
                ) {
                    for (
                        x in x0 until
                            max(x0 + 1, x1)
                    ) {

                        if (
                            x < 0 ||
                            y < 0 ||
                            x >= bitmap.width ||
                            y >= bitmap.height
                        ) {
                            continue
                        }

                        val c =
                            bitmap.getPixel(x, y)

                        val r =
                            Color.red(c)

                        val g =
                            Color.green(c)

                        val b =
                            Color.blue(c)

                        total++

                        if (
                            r >= 160 &&
                            g >= 160 &&
                            b >= 160 &&
                            max(r, max(g, b)) -
                            min(r, min(g, b)) < 75
                        ) {
                            white++
                        }
                    }
                }

                samples[gy][gx] =
                    total > 0 &&
                        white * 100 >=
                        total * 18
            }
        }

        var bestDigit = -1
        var bestScore = Int.MAX_VALUE
        var secondScore = Int.MAX_VALUE

        for (digit in 0..4) {

            var difference = 0

            for (y in 0 until 7) {
                for (x in 0 until 5) {

                    val expected =
                        DIGITS[digit][y][x] == '1'

                    if (
                        samples[y][x] !=
                        expected
                    ) {
                        difference++
                    }
                }
            }

            if (
                difference < bestScore
            ) {
                secondScore = bestScore
                bestScore = difference
                bestDigit = digit
            } else if (
                difference < secondScore
            ) {
                secondScore = difference
            }
        }

        if (bestDigit < 0) {
            return null
        }

        /*
         * Strict rejection.
         */
        if (bestScore > 11) {
            return null
        }

        /*
         * Do not guess between similar digits.
         */
        if (
            secondScore != Int.MAX_VALUE &&
            secondScore - bestScore < 2
        ) {
            return null
        }

        val confidence =
            (
                100 -
                    bestScore * 7
            ).coerceIn(70, 99)

        return Pair(
            bestDigit + 1,
            confidence
        )
    }

    // =========================================================
    // RESOURCE CLASSIFIER
    // =========================================================

    private fun classifyResource(
        bitmap: Bitmap,
        badge: BoundingBox
    ): TripleResult? {

        /*
         * The badge is normally near the upper-right of
         * the resource tile.
         *
         * Therefore inspect a compact region to its
         * LEFT and BELOW instead of averaging the whole
         * surrounding terrain.
         */
        val left =
            max(
                0,
                badge.centerX - 82
            )

        val right =
            min(
                bitmap.width - 1,
                badge.centerX + 8
            )

        val top =
            max(
                0,
                badge.maxY - 2
            )

        val bottom =
            min(
                bitmap.height - 1,
                badge.maxY + 62
            )

        var total = 0

        var yellow = 0
        var brightYellow = 0

        var green = 0
        var darkGreen = 0

        var brown = 0
        var darkBrown = 0

        var grey = 0
        var lightGrey = 0

        var cyan = 0
        var orange = 0

        var dark = 0
        var red = 0

        var sumR = 0
        var sumG = 0
        var sumB = 0

        for (
            y in top..bottom step 2
        ) {
            for (
                x in left..right step 2
            ) {

                val c =
                    bitmap.getPixel(x, y)

                val r =
                    Color.red(c)

                val g =
                    Color.green(c)

                val b =
                    Color.blue(c)

                /*
                 * Ignore the blue level badge.
                 */
                if (
                    b > r + 28 &&
                    b > g + 4
                ) {
                    continue
                }

                total++

                sumR += r
                sumG += g
                sumB += b

                val mx =
                    max(r, max(g, b))

                val mn =
                    min(r, min(g, b))

                val spread =
                    mx - mn

                if (
                    r > 145 &&
                    g > 125 &&
                    r > b + 28 &&
                    g > b + 18
                ) {
                    yellow++
                }

                if (
                    r > 185 &&
                    g > 160 &&
                    b < 125
                ) {
                    brightYellow++
                }

                if (
                    g > r + 6 &&
                    g > b + 6 &&
                    g > 70
                ) {
                    green++
                }

                if (
                    g > 85 &&
                    g > r + 12 &&
                    g > b + 8
                ) {
                    darkGreen++
                }

                if (
                    r > b + 12 &&
                    g > b &&
                    r > 60 &&
                    g > 40
                ) {
                    brown++
                }

                if (
                    r > b + 18 &&
                    g < 115 &&
                    r < 175
                ) {
                    darkBrown++
                }

                if (
                    spread < 38 &&
                    mx in 65..205
                ) {
                    grey++
                }

                if (
                    spread < 42 &&
                    mx > 125
                ) {
                    lightGrey++
                }

                /*
                 * Ore contains conspicuous cyan/blue
                 * mineral pieces.
                 */
                if (
                    b > r + 18 &&
                    b > g + 2 &&
                    b > 80
                ) {
                    cyan++
                }

                /*
                 * Ore also commonly has warm mineral pieces.
                 */
                if (
                    r > 135 &&
                    g in 55..175 &&
                    b < 115
                ) {
                    orange++
                }

                if (
                    mx < 65
                ) {
                    dark++
                }

                if (
                    r > 175 &&
                    r > g * 1.4 &&
                    r > b * 1.4
                ) {
                    red++
                }
            }
        }

        if (total < 60) {
            return null
        }

        val avgR =
            sumR / total

        val avgG =
            sumG / total

        val avgB =
            sumB / total

        /*
         * Monster/artwork rejection.
         */
        if (
            red.toFloat() /
                total.toFloat() >
                0.095f
        ) {
            return null
        }

        val scores =
            mutableListOf<Pair<String, Int>>()

        // -----------------------------------------------------
        // FOOD
        // -----------------------------------------------------

        if (
            yellow >= total * 0.16 &&
            brightYellow >= total * 0.04
        ) {

            scores +=
                "Food" to
                    (
                        70 +
                            min(
                                16,
                                yellow * 25 / total
                            )
                        )
        }

        // -----------------------------------------------------
        // GOLD
        // -----------------------------------------------------

        if (
            brightYellow >= total * 0.11 &&
            avgR > 150 &&
            avgG > 125 &&
            avgB < 130
        ) {

            scores +=
                "Gold" to
                    (
                        76 +
                            min(
                                16,
                                brightYellow * 30 / total
                            )
                        )
        }

        // -----------------------------------------------------
        // WOOD
        // -----------------------------------------------------

        if (
            brown >= total * 0.13 &&
            green >= total * 0.04 &&
            darkBrown >= total * 0.025
        ) {

            scores +=
                "Wood" to
                    (
                        73 +
                            min(
                                15,
                                brown * 25 / total
                            )
                        )
        }

        // -----------------------------------------------------
        // STONE
        // -----------------------------------------------------

        if (
            grey >= total * 0.24 &&
            lightGrey >= total * 0.08 &&
            abs(avgR - avgG) < 38 &&
            abs(avgG - avgB) < 45
        ) {

            scores +=
                "Stone" to
                    (
                        74 +
                            min(
                                15,
                                grey * 25 / total
                            )
                        )
        }

        // -----------------------------------------------------
        // ORE
        // -----------------------------------------------------

        if (
            cyan >= total * 0.055 &&
            orange >= total * 0.025
        ) {

            scores +=
                "Ore" to
                    (
                        79 +
                            min(
                                14,
                                cyan * 30 / total
                            )
                        )
        }

        if (scores.isEmpty()) {
            return null
        }

        scores.sortByDescending {
            it.second
        }

        val best =
            scores.first()

        val second =
            scores.getOrNull(1)?.second
                ?: 0

        /*
         * Important:
         * Do not convert uncertain artwork into
         * a target.
         */
        if (
            second > 0 &&
            best.second - second < 9
        ) {
            return null
        }

        /*
         * Additional terrain protection.
         *
         * A large green field with no resource
         * signature should not become Food/Wood.
         */
        if (
            best.first == "Food" &&
            yellow < total * 0.16
        ) {
            return null
        }

        if (
            best.first == "Wood" &&
            brown < total * 0.13
        ) {
            return null
        }

        if (
            best.first == "Stone" &&
            grey < total * 0.24
        ) {
            return null
        }

        if (
            best.first == "Ore" &&
            cyan < total * 0.055
        ) {
            return null
        }

        return TripleResult(
            best.first,
            best.second.coerceIn(68, 96),
            Triple(
                avgR,
                avgG,
                avgB
            )
        )
    }

    // =========================================================
    // OCCUPATION
    // =========================================================

    private fun detectOccupation(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Boolean {

        val left =
            max(
                0,
                badge.minX - 75
            )

        val right =
            min(
                bitmap.width - 1,
                badge.maxX + 45
            )

        val top =
            max(
                0,
                badge.minY - 35
            )

        val bottom =
            min(
                bitmap.height - 1,
                badge.maxY + 85
            )

        var red = 0
        var strongRed = 0
        var total = 0

        for (
            y in top..bottom step 3
        ) {
            for (
                x in left..right step 3
            ) {

                val c =
                    bitmap.getPixel(x, y)

                val r =
                    Color.red(c)

                val g =
                    Color.green(c)

                val b =
                    Color.blue(c)

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
            red.toFloat() /
            total.toFloat() >= 0.009f
    }

    // =========================================================
    // DEDUPLICATION
    // =========================================================

    private fun deduplicate(
        items: List<RssDetection>
    ): List<RssDetection> {

        val result =
            mutableListOf<RssDetection>()

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

                    dx < 30 &&
                        dy < 30
                }

            if (!duplicate) {
                result += item
            }
        }

        return result
    }

    // =========================================================
    // DATA
    // =========================================================

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
            get() =
                (minX + maxX) / 2

        val centerY: Int
            get() =
                (minY + maxY) / 2
    }

    data class RssDetection(
        val type: String,
        val level: Int,
        val centerX: Int,
        val centerY: Int,
        val boundingBox: BoundingBox,
        val confidence: Int,
        val occupied: Boolean,
        val dominantColor:
            Triple<Int, Int, Int>
    )
}
