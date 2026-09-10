package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * V16
 *
 * Strict Lords Mobile RSS detector.
 *
 * Detection pipeline:
 *
 *   BLUE LEVEL BADGE
 *          ↓
 *   WHITE DIGIT EXTRACTION
 *          ↓
 *   DIGIT SHAPE ANALYSIS
 *          ↓
 *   RESOURCE ARTWORK ANALYSIS
 *          ↓
 *   OCCUPATION CHECK
 *          ↓
 *   STRICT CONFIDENCE
 *          ↓
 *   DEDUPLICATION
 *
 * IMPORTANT:
 * This version prefers FALSE NEGATIVE over FALSE POSITIVE.
 * An uncertain object is rejected.
 */
class ScreenAnalyzer {

    companion object {

        private const val STEP = 2

        /*
         * Blue badge thresholds.
         */
        private const val MIN_BLUE = 110
        private const val BLUE_RED_GAP = 35
        private const val BLUE_GREEN_GAP = 10

        /*
         * Minimum/maximum badge dimensions.
         */
        private const val MIN_BADGE_W = 16
        private const val MAX_BADGE_W = 52
        private const val MIN_BADGE_H = 16
        private const val MAX_BADGE_H = 44
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

            /*
             * V16:
             * Level must be independently verified.
             */
            val levelResult =
                readLevelStrict(bitmap, badge)
                    ?: continue

            /*
             * V16:
             * Resource must also be independently verified.
             */
            val resource =
                classifyResourceStrict(bitmap, badge)
                    ?: continue

            val occupied =
                detectOccupation(bitmap, badge)

            val confidence =
                (
                    levelResult.confidence * 0.50 +
                    resource.confidence * 0.50
                ).toInt()

            if (confidence < 72) {
                continue
            }

            detections += RssDetection(
                type = resource.type,
                level = levelResult.level,
                centerX = badge.centerX,
                centerY = badge.centerY,
                boundingBox = badge,
                confidence = confidence,
                occupied = occupied,
                dominantColor = resource.color
            )
        }

        return deduplicate(detections)
    }

    // =========================================================
    // BLUE BADGE DETECTION
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

        fun inside(x: Int, y: Int): Boolean {
            return x in 0 until gw &&
                y in 0 until gh
        }

        fun bluePixel(gx: Int, gy: Int): Boolean {

            val x = gx * STEP
            val y = gy * STEP

            if (x !in regionX || y !in regionY) {
                return false
            }

            if (
                x < 0 ||
                y < 0 ||
                x >= bitmap.width ||
                y >= bitmap.height
            ) {
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
                    !bluePixel(gx, gy)
                ) {
                    continue
                }

                val queueX =
                    IntArray(2048)

                val queueY =
                    IntArray(2048)

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

                var count = 0

                while (head < tail) {

                    val x =
                        queueX[head]

                    val y =
                        queueY[head]

                    head++

                    count++

                    minX =
                        min(minX, x)

                    maxX =
                        max(maxX, x)

                    minY =
                        min(minY, y)

                    maxY =
                        max(maxY, y)

                    for (dy in -1..1) {
                        for (dx in -1..1) {

                            if (
                                dx == 0 &&
                                dy == 0
                            ) {
                                continue
                            }

                            val nx =
                                x + dx

                            val ny =
                                y + dy

                            if (
                                !inside(nx, ny)
                            ) {
                                continue
                            }

                            val ni =
                                ny * gw + nx

                            if (
                                !visited[ni] &&
                                bluePixel(nx, ny)
                            ) {

                                visited[ni] = true

                                if (
                                    tail <
                                    queueX.size
                                ) {
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
                 * Strict badge geometry.
                 */
                if (
                    count in 10..420 &&
                    width in MIN_BADGE_W..MAX_BADGE_W &&
                    height in MIN_BADGE_H..MAX_BADGE_H &&
                    width.toFloat() /
                    height.toFloat() in 0.55f..1.8f
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
    // STRICT LEVEL READER
    // =========================================================

    private fun readLevelStrict(
        bitmap: Bitmap,
        badge: BoundingBox
    ): LevelResult? {

        /*
         * Collect bright neutral pixels.
         */
        val pixels =
            mutableListOf<Pair<Int, Int>>()

        for (y in badge.minY..badge.maxY) {
            for (x in badge.minX..badge.maxX) {

                val c =
                    bitmap.getPixel(x, y)

                val r =
                    Color.red(c)

                val g =
                    Color.green(c)

                val b =
                    Color.blue(c)

                val maximum =
                    max(r, max(g, b))

                val minimum =
                    min(r, min(g, b))

                /*
                 * White/silver digit.
                 */
                if (
                    r >= 175 &&
                    g >= 175 &&
                    b >= 175 &&
                    maximum - minimum <= 65
                ) {
                    pixels +=
                        Pair(x, y)
                }
            }
        }

        if (pixels.size < 6) {
            return null
        }

        val minX =
            pixels.minOf { it.first }

        val maxX =
            pixels.maxOf { it.first }

        val minY =
            pixels.minOf { it.second }

        val maxY =
            pixels.maxOf { it.second }

        val width =
            maxX - minX + 1

        val height =
            maxY - minY + 1

        /*
         * A numeral must be reasonably tall.
         */
        if (
            width < 3 ||
            height < 7 ||
            height < width
        ) {
            return null
        }

        /*
         * Reject if the white area consumes almost
         * the entire badge.
         */
        if (
            width >
            badge.width * 0.90
        ) {
            return null
        }

        /*
         * Normalize the digit into 7 x 5 cells.
         */
        val grid =
            Array(7) {
                BooleanArray(5)
            }

        for (gy in 0 until 7) {
            for (gx in 0 until 5) {

                val x0 =
                    minX +
                        gx * width / 5

                val x1 =
                    minX +
                        (gx + 1) * width / 5

                val y0 =
                    minY +
                        gy * height / 7

                val y1 =
                    minY +
                        (gy + 1) * height / 7

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

                        val mx =
                            max(r, max(g, b))

                        val mn =
                            min(r, min(g, b))

                        if (
                            r >= 160 &&
                            g >= 160 &&
                            b >= 160 &&
                            mx - mn <= 75
                        ) {
                            white++
                        }
                    }
                }

                grid[gy][gx] =
                    total > 0 &&
                    white * 100 >=
                    total * 20
            }
        }

        /*
         * Calculate structural features.
         */
        val rowCount =
            IntArray(7)

        val colCount =
            IntArray(5)

        for (y in 0 until 7) {
            for (x in 0 until 5) {
                if (grid[y][x]) {
                    rowCount[y]++
                    colCount[x]++
                }
            }
        }

        val active =
            rowCount.sum()

        if (active < 5) {
            return null
        }

        /*
         * Identify digit by structure.
         *
         * We intentionally do NOT use the old
         * nearest-template method.
         */

        val scores =
            mutableMapOf<Int, Int>()

        // -----------------------------------------------------
        // 1
        // -----------------------------------------------------

        run {
            var score = 0

            if (colCount[2] >= 4) score += 35
            if (colCount[1] >= 2) score += 10
            if (rowCount[6] >= 2) score += 15

            if (rowCount[0] <= 2) score += 8
            if (colCount[0] <= 2) score += 8
            if (colCount[4] <= 2) score += 8

            scores[1] = score
        }

        // -----------------------------------------------------
        // 2
        // -----------------------------------------------------

        run {
            var score = 0

            if (rowCount[0] >= 3) score += 18
            if (rowCount[3] >= 2) score += 14
            if (rowCount[6] >= 3) score += 18

            if (colCount[0] >= 2) score += 8
            if (colCount[4] >= 2) score += 8

            if (colCount[2] >= 2) score += 5

            scores[2] = score
        }

        // -----------------------------------------------------
        // 3
        // -----------------------------------------------------

        run {
            var score = 0

            if (rowCount[0] >= 3) score += 18
            if (rowCount[3] >= 3) score += 18
            if (rowCount[6] >= 3) score += 18

            if (colCount[4] >= 4) score += 15

            scores[3] = score
        }

        // -----------------------------------------------------
        // 4
        // -----------------------------------------------------

        run {
            var score = 0

            if (rowCount[4] >= 3) score += 20
            if (colCount[4] >= 4) score += 20
            if (colCount[2] >= 4) score += 15

            if (colCount[0] >= 2) score += 5

            scores[4] = score
        }

        // -----------------------------------------------------
        // 5
        // -----------------------------------------------------

        run {
            var score = 0

            if (rowCount[0] >= 3) score += 18
            if (rowCount[3] >= 2) score += 16
            if (rowCount[6] >= 3) score += 18

            if (colCount[0] >= 3) score += 14

            scores[5] = score
        }

        val sorted =
            scores.entries
                .sortedByDescending { it.value }

        if (sorted.isEmpty()) {
            return null
        }

        val best =
            sorted[0]

        val second =
            sorted.getOrNull(1)

        /*
         * Require a meaningful separation.
         */
        if (
            second != null &&
            best.value - second.value < 7
        ) {
            return null
        }

        /*
         * Absolute minimum structural score.
         */
        if (best.value < 38) {
            return null
        }

        /*
         * Additional rejection for obvious
         * wide/solid non-numeral shapes.
         */
        if (
            active > 24 &&
            best.key == 1
        ) {
            return null
        }

        val confidence =
            (
                70 +
                    min(
                        27,
                        best.value / 2
                    )
            ).coerceIn(70, 97)

        return LevelResult(
            level = best.key,
            confidence = confidence
        )
    }

    // =========================================================
    // STRICT RESOURCE CLASSIFIER
    // =========================================================

    private fun classifyResourceStrict(
        bitmap: Bitmap,
        badge: BoundingBox
    ): ResourceResult? {

        /*
         * The resource artwork is normally left/below
         * the level badge.
         *
         * We use a relatively small region to avoid
         * terrain contamination.
         */
        val left =
            max(
                0,
                badge.centerX - 78
            )

        val right =
            min(
                bitmap.width - 1,
                badge.centerX + 6
            )

        val top =
            min(
                bitmap.height - 1,
                badge.maxY + 1
            )

        val bottom =
            min(
                bitmap.height - 1,
                badge.maxY + 58
            )

        if (
            left >= right ||
            top >= bottom
        ) {
            return null
        }

        var total = 0

        var yellow = 0
        var brightYellow = 0

        var brown = 0
        var darkBrown = 0

        var grey = 0
        var lightGrey = 0

        var cyan = 0
        var orange = 0

        var green = 0

        var red = 0

        var blue = 0

        var saturated = 0

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
                 * Skip blue badge pixels.
                 */
                if (
                    b > r + 30 &&
                    b > g + 8
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

                if (spread > 55) {
                    saturated++
                }

                // FOOD / WHEAT
                if (
                    r > 150 &&
                    g > 125 &&
                    r > b + 35 &&
                    g > b + 20
                ) {
                    yellow++

                    if (
                        r > 190 &&
                        g > 165 &&
                        b < 130
                    ) {
                        brightYellow++
                    }
                }

                // WOOD / LOGS
                if (
                    r > b + 25 &&
                    g > b + 12 &&
                    r > g - 10 &&
                    r in 65..190 &&
                    g in 45..165
                ) {
                    brown++
                }

                if (
                    r in 45..125 &&
                    g in 30..100 &&
                    b in 20..80 &&
                    r > b + 15
                ) {
                    darkBrown++
                }

                // STONE
                if (
                    abs(r - g) < 22 &&
                    abs(g - b) < 22 &&
                    r in 75..190
                ) {
                    grey++
                }

                if (
                    abs(r - g) < 18 &&
                    abs(g - b) < 18 &&
                    r in 120..220
                ) {
                    lightGrey++
                }

                // ORE
                if (
                    b > r + 12 &&
                    b > g - 5 &&
                    b > 75
                ) {
                    cyan++
                    blue++
                }

                if (
                    r > 150 &&
                    g in 70..180 &&
                    b < 100
                ) {
                    orange++
                }

                // GREEN VEGETATION
                if (
                    g > r + 8 &&
                    g > b + 5 &&
                    g > 70
                ) {
                    green++
                }

                if (
                    r > 170 &&
                    r > g * 1.5 &&
                    r > b * 1.5
                ) {
                    red++
                }
            }
        }

        if (total < 40) {
            return null
        }

        val avgR =
            sumR / total

        val avgG =
            sumG / total

        val avgB =
            sumB / total

        val yellowRatio =
            yellow.toFloat() / total

        val brightYellowRatio =
            brightYellow.toFloat() / total

        val brownRatio =
            brown.toFloat() / total

        val darkBrownRatio =
            darkBrown.toFloat() / total

        val greyRatio =
            grey.toFloat() / total

        val lightGreyRatio =
            lightGrey.toFloat() / total

        val cyanRatio =
            cyan.toFloat() / total

        val orangeRatio =
            orange.toFloat() / total

        val greenRatio =
            green.toFloat() / total

        val saturatedRatio =
            saturated.toFloat() / total

        /*
         * Candidate scores.
         */
        val candidates =
            mutableListOf<Pair<String, Int>>()

        // =====================================================
        // FOOD
        // =====================================================

        if (
            yellowRatio >= 0.14f &&
            brightYellowRatio >= 0.05f &&
            avgR > avgB + 35
        ) {

            val score =
                (
                    70 +
                        min(
                            20,
                            (yellowRatio * 100).toInt()
                        )
                )

            candidates +=
                "Food" to score
        }

        // =====================================================
        // WOOD
        // =====================================================

        if (
            brownRatio >= 0.13f &&
            darkBrownRatio >= 0.03f &&
            avgR > avgB + 18
        ) {

            val score =
                (
                    69 +
                        min(
                            20,
                            (brownRatio * 100).toInt()
                        )
                )

            candidates +=
                "Wood" to score
        }

        // =====================================================
        // STONE
        // =====================================================

        /*
         * Stone must be genuinely grey.
         *
         * This is much stricter than V15.
         */
        if (
            greyRatio >= 0.22f &&
            lightGreyRatio >= 0.05f &&
            saturatedRatio < 0.45f
        ) {

            val score =
                (
                    68 +
                        min(
                            21,
                            (greyRatio * 80).toInt()
                        )
                )

            candidates +=
                "Stone" to score
        }

        // =====================================================
        // ORE
        // =====================================================

        /*
         * Ore should contain coloured mineral pixels.
         * Grey terrain alone is NOT enough.
         */
        if (
            cyanRatio >= 0.08f &&
            orangeRatio >= 0.02f &&
            saturatedRatio >= 0.18f
        ) {

            val score =
                (
                    70 +
                        min(
                            19,
                            (saturatedRatio * 50).toInt()
                        )
                )

            candidates +=
                "Ore" to score
        }

        /*
         * A second Ore signature for blue/cyan mineral
         * clusters.
         */
        if (
            cyanRatio >= 0.14f &&
            saturatedRatio >= 0.22f
        ) {

            candidates +=
                "Ore" to 73
        }

        /*
         * Gold is deliberately strict.
         */
        if (
            brightYellowRatio >= 0.12f &&
            yellowRatio >= 0.22f &&
            avgR > 170 &&
            avgG > 145 &&
            avgB < 135
        ) {

            candidates +=
                "Gold" to 82
        }

        /*
         * Vegetation alone must NEVER become Food.
         */
        if (
            greenRatio > 0.35f &&
            yellowRatio < 0.12f
        ) {
            candidates.removeAll {
                it.first == "Food"
            }
        }

        /*
         * If the artwork is mostly green terrain,
         * reject completely.
         */
        if (
            greenRatio > 0.48f
        ) {
            return null
        }

        if (candidates.isEmpty()) {
            return null
        }

        val sorted =
            candidates
                .groupBy { it.first }
                .map {
                    it.key to
                        it.value.maxOf { pair ->
                            pair.second
                        }
                }
                .sortedByDescending {
                    it.second
                }

        val best =
            sorted.first()

        val second =
            sorted.getOrNull(1)

        /*
         * Ambiguous resource classification = reject.
         */
        if (
            second != null &&
            best.second - second.second < 9
        ) {
            return null
        }

        /*
         * Minimum score.
         */
        if (best.second < 68) {
            return null
        }

        return ResourceResult(
            type = best.first,
            confidence =
                best.second.coerceIn(68, 95),
            color =
                Triple(
                    avgR,
                    avgG,
                    avgB
                )
        )
    }

    // =========================================================
    // OCCUPATION / MARCH DETECTION
    // =========================================================

    private fun detectOccupation(
        bitmap: Bitmap,
        badge: BoundingBox
    ): Boolean {

        /*
         * Search around the resource for the red
         * occupied/march indicator.
         */
        val left =
            max(
                0,
                badge.minX - 72
            )

        val right =
            min(
                bitmap.width - 1,
                badge.maxX + 52
            )

        val top =
            max(
                0,
                badge.minY - 30
            )

        val bottom =
            min(
                bitmap.height - 1,
                badge.maxY + 75
            )

        var strongRed = 0
        var red = 0
        var total = 0

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

                total++

                if (
                    r > 175 &&
                    r > g * 1.45 &&
                    r > b * 1.45
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

        return strongRed >= 7 &&
            red.toFloat() /
            total.toFloat() >= 0.010f
    }

    // =========================================================
    // DEDUPLICATION
    // =========================================================

    private fun deduplicate(
        detections: List<RssDetection>
    ): List<RssDetection> {

        val output =
            mutableListOf<RssDetection>()

        /*
         * Highest confidence first.
         */
        for (
            item in
            detections.sortedByDescending {
                it.confidence
            }
        ) {

            val duplicate =
                output.any {

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

                    dx < 42 &&
                        dy < 42
                }

            if (!duplicate) {
                output += item
            }
        }

        return output
    }

    // =========================================================
    // DATA CLASSES
    // =========================================================

    private data class LevelResult(
        val level: Int,
        val confidence: Int
    )

    private data class ResourceResult(
        val type: String,
        val confidence: Int,
        val color: Triple<Int, Int, Int>
    )

    data class BoundingBox(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {

        val width: Int
            get() =
                maxX - minX + 1

        val height: Int
            get() =
                maxY - minY + 1

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
        val dominantColor: Triple<Int, Int, Int>
    )
}
