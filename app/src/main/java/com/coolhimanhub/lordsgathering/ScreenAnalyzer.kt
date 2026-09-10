package com.coolhimanhub.lordsgatheringassistant

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * ScreenAnalyzer handles detection of RSS tiles from game screenshots.
 * Detects badge colors, levels, and occupancy status.
 */
class ScreenAnalyzer {

    companion object {
        // Color detection thresholds
        private const val RGB_THRESHOLD = 30
        
        // Badge colors for different resource types (RGB tuples)
        private val RESOURCE_COLORS = mapOf(
            "Emerging" to Triple(255, 215, 0),      // Gold
            "Gold" to Triple(255, 200, 0),          // Dark Gold
            "Ore" to Triple(128, 128, 128),         // Gray
            "Wood" to Triple(165, 42, 42),          // Brown
            "Food" to Triple(34, 139, 34),          // Forest Green
            "Stone" to Triple(192, 192, 192),       // Light Gray
            "Other" to Triple(200, 200, 200)        // Light Gray
        )

        // Occupied tile indicators (red flag/marker)
        private val OCCUPIED_RED = Triple(255, 0, 0)
        private val OCCUPIED_THRESHOLD = 40
    }

    /**
     * Analyzes a screenshot bitmap to detect RSS tile badges
     */
    fun analyzeScreenshot(
        bitmap: Bitmap,
        expectedRegionX: IntRange = 0 until bitmap.width,
        expectedRegionY: IntRange = 0 until bitmap.height
    ): List<RssDetection> {

        val detections = mutableListOf<RssDetection>()

        // Scan for badge-like circular regions
        val badgeRegions = findBadgeRegions(bitmap, expectedRegionX, expectedRegionY)

        for (region in badgeRegions) {
            val detection = analyzeRegion(bitmap, region)
            if (detection != null) {
                detections.add(detection)
            }
        }

        return detections
    }

    /**
     * Find potential badge regions by scanning for concentrated colored pixels
     */
    private fun findBadgeRegions(
        bitmap: Bitmap,
        regionX: IntRange,
        regionY: IntRange
    ): List<BoundingBox> {

        val regions = mutableListOf<BoundingBox>()
        val minBadgeSize = 30
        val maxBadgeSize = 150
        val badgeScanStep = 15

        var y = regionY.first
        while (y < regionY.last) {

            var x = regionX.first
            while (x < regionX.last) {

                // Check if pixel at (x, y) could be a badge center
                val colorIntensity = getColorIntensity(
                    bitmap,
                    x,
                    y
                )

                if (colorIntensity > 100) {
                    // Potential badge found, expand to find bounds
                    val bbox = expandBadgeRegion(
                        bitmap,
                        x,
                        y,
                        minBadgeSize,
                        maxBadgeSize
                    )

                    if (bbox != null && !regionsOverlap(bbox, regions)) {
                        regions.add(bbox)
                        x += bbox.width
                    }
                }

                x += badgeScanStep
            }

            y += badgeScanStep
        }

        return regions
    }

    /**
     * Expand from a center point to find badge boundaries
     */
    private fun expandBadgeRegion(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int,
        minSize: Int,
        maxSize: Int
    ): BoundingBox? {

        var minX = centerX
        var maxX = centerX
        var minY = centerY
        var maxY = centerY

        // Expand outward until color intensity drops
        for (radius in 1..maxSize step 2) {

            val borderIntensity = (0..7).map { angle ->
                val rad = Math.toRadians((angle * 45).toDouble())
                val px = centerX + (radius * kotlin.math.cos(rad)).toInt()
                val py = centerY + (radius * kotlin.math.sin(rad)).toInt()
                getColorIntensity(bitmap, px, py)
            }.average()

            if (borderIntensity < 50) {
                // Color dropped too much, use previous radius
                val finalRadius = maxOf(radius - 2, minSize / 2)
                minX = centerX - finalRadius
                maxX = centerX + finalRadius
                minY = centerY - finalRadius
                maxY = centerY + finalRadius
                break
            }
        }

        val width = maxX - minX
        val height = maxY - minY

        return if (width >= minSize && height >= minSize && width <= maxSize && height <= maxSize) {
            BoundingBox(minX, minY, maxX, maxY)
        } else {
            null
        }
    }

    /**
     * Analyze a specific region to determine resource type and level
     */
    private fun analyzeRegion(
        bitmap: Bitmap,
        bbox: BoundingBox
    ): RssDetection? {

        // Sample pixels from the region
        val samples = sampleRegionColors(bitmap, bbox)

        if (samples.isEmpty()) {
            return null
        }

        // Determine dominant color
        val dominantColor = findDominantColor(samples)
        val resourceType = matchResourceColor(dominantColor)

        // Check for occupation (red overlay)
        val isOccupied = detectOccupancy(samples)

        // Estimate level from badge characteristics
        val estimatedLevel = estimateLevel(bitmap, bbox, samples)

        // Calculate confidence based on color match
        val confidence = calculateColorConfidence(dominantColor, resourceType)

        return RssDetection(
            type = resourceType,
            level = estimatedLevel,
            centerX = bbox.centerX,
            centerY = bbox.centerY,
            boundingBox = bbox,
            confidence = confidence,
            occupied = isOccupied,
            dominantColor = dominantColor
        )
    }

    /**
     * Sample colors from badge region
     */
    private fun sampleRegionColors(
        bitmap: Bitmap,
        bbox: BoundingBox
    ): List<Triple<Int, Int, Int>> {

        val colors = mutableListOf<Triple<Int, Int, Int>>()
        val stepSize = max(1, (bbox.width / 8))

        for (y in bbox.minY..bbox.maxY step stepSize) {
            for (x in bbox.minX..bbox.maxX step stepSize) {

                if (x >= 0 && x < bitmap.width && y >= 0 && y < bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    colors.add(Triple(r, g, b))
                }
            }
        }

        return colors
    }

    /**
     * Find the most common color in samples
     */
    private fun findDominantColor(
        samples: List<Triple<Int, Int, Int>>
    ): Triple<Int, Int, Int> {

        if (samples.isEmpty()) {
            return Triple(128, 128, 128)
        }

        // Group similar colors together
        val grouped = mutableMapOf<String, MutableList<Triple<Int, Int, Int>>>()

        for (sample in samples) {
            val key = "${sample.first / 20}-${sample.second / 20}-${sample.third / 20}"
            grouped.getOrPut(key) { mutableListOf() }.add(sample)
        }

        // Find the group with most samples
        val largestGroup = grouped.values.maxByOrNull { it.size } ?: samples

        // Average the group
        val avgR = largestGroup.map { it.first }.average().toInt()
        val avgG = largestGroup.map { it.second }.average().toInt()
        val avgB = largestGroup.map { it.third }.average().toInt()

        return Triple(avgR, avgG, avgB)
    }

    /**
     * Match detected color to resource type
     */
    private fun matchResourceColor(
        detectedColor: Triple<Int, Int, Int>
    ): String {

        var bestMatch = "Other"
        var bestDistance = 255 * 3 + 1

        for ((resourceType, refColor) in RESOURCE_COLORS) {
            val distance = colorDistance(detectedColor, refColor)

            if (distance < bestDistance) {
                bestDistance = distance
                bestMatch = resourceType
            }
        }

        return bestMatch
    }

    /**
     * Calculate Euclidean distance between two colors
     */
    private fun colorDistance(
        color1: Triple<Int, Int, Int>,
        color2: Triple<Int, Int, Int>
    ): Int {

        val dr = color1.first - color2.first
        val dg = color1.second - color2.second
        val db = color1.third - color2.third

        return kotlin.math.sqrt(
            (dr * dr + dg * dg + db * db).toDouble()
        ).toInt()
    }

    /**
     * Detect if tile is occupied (has red flag/marker)
     */
    private fun detectOccupancy(
        samples: List<Triple<Int, Int, Int>>
    ): Boolean {

        val redPixels = samples.count { (r, g, b) ->
            r > 200 && g < 100 && b < 100
        }

        return redPixels > samples.size * 0.1 // 10% of pixels are red
    }

    /**
     * Estimate resource level from visual characteristics
     */
    private fun estimateLevel(
        bitmap: Bitmap,
        bbox: BoundingBox,
        samples: List<Triple<Int, Int, Int>>
    ): Int {

        // Level is estimated by badge size and brightness
        val badgeSize = bbox.width
        val avgBrightness = samples.map { (r, g, b) ->
            (r + g + b) / 3
        }.average()

        return when {
            badgeSize >= 120 && avgBrightness > 200 -> 5
            badgeSize >= 100 && avgBrightness > 180 -> 4
            badgeSize >= 80 && avgBrightness > 160 -> 3
            badgeSize >= 60 && avgBrightness > 140 -> 2
            else -> 1
        }
    }

    /**
     * Calculate confidence of the detection (0-100)
     */
    private fun calculateColorConfidence(
        detectedColor: Triple<Int, Int, Int>,
        matchedType: String
    ): Int {

        val refColor = RESOURCE_COLORS[matchedType] ?: return 40

        val distance = colorDistance(detectedColor, refColor)
        val maxDistance = 150

        return max(0, 100 - (distance * 100 / maxDistance))
    }

    /**
     * Get overall color intensity at a pixel
     */
    private fun getColorIntensity(
        bitmap: Bitmap,
        x: Int,
        y: Int
    ): Int {

        if (x < 0 || x >= bitmap.width || y < 0 || y >= bitmap.height) {
            return 0
        }

        val pixel = bitmap.getPixel(x, y)
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        return (r + g + b) / 3
    }

    /**
     * Check if two regions overlap
     */
    private fun regionsOverlap(
        box: BoundingBox,
        others: List<BoundingBox>
    ): Boolean {

        return others.any { other ->
            box.minX < other.maxX &&
            box.maxX > other.minX &&
            box.minY < other.maxY &&
            box.maxY > other.minY
        }
    }

    // ==========================================================
    // DATA CLASSES
    // ==========================================================

    data class BoundingBox(
        val minX: Int,
        val minY: Int,
        val maxX: Int,
        val maxY: Int
    ) {
        val width: Int
            get() = maxX - minX

        val height: Int
            get() = maxY - minY

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
