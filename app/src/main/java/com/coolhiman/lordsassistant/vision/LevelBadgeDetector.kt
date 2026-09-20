package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

data class LevelBadge(
    val tileClass: TileClass,
    val bounds: RectF,
    val confidence: Double
)

/**
 * Detects the small blue resource / red monster level badges visible on the
 * world map. It is deliberately geometry- and color-constrained so large UI
 * panels are not treated as map nodes.
 */
class LevelBadgeDetector(
    private val minArea: Double = 12.0,
    private val maxArea: Double = 900.0
) {
    fun detect(bitmap: Bitmap): List<LevelBadge> {
        if (bitmap.isRecycled) return emptyList()
        val src = Mat()
        val hsv = Mat()
        val blue = Mat()
        val red1 = Mat()
        val red2 = Mat()
        val red = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        return try {
            Utils.bitmapToMat(bitmap, src)
            Imgproc.cvtColor(src, hsv, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

            Core.inRange(hsv, Scalar(95.0, 100.0, 90.0), Scalar(135.0, 255.0, 255.0), blue)
            Core.inRange(hsv, Scalar(0.0, 110.0, 90.0), Scalar(12.0, 255.0, 255.0), red1)
            Core.inRange(hsv, Scalar(165.0, 110.0, 90.0), Scalar(179.0, 255.0, 255.0), red2)
            Core.bitwise_or(red1, red2, red)

            detectMask(blue, TileClass.RESOURCE, bitmap.width, bitmap.height, contours) +
                detectMask(red, TileClass.MONSTER, bitmap.width, bitmap.height, contours)
        } finally {
            hierarchy.release()
            contours.forEach { it.release() }
            src.release(); hsv.release(); blue.release(); red1.release(); red2.release(); red.release()
        }
    }

    private fun detectMask(
        mask: Mat,
        tileClass: TileClass,
        width: Int,
        height: Int,
        reusable: MutableList<MatOfPoint>
    ): List<LevelBadge> {
        reusable.clear()
        Imgproc.findContours(mask, reusable, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        return reusable.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area < minArea || area > maxArea) return@mapNotNull null
            val r = Imgproc.boundingRect(contour)
            val w = r.width.toFloat()
            val h = r.height.toFloat()
            if (w < 5f || h < 5f || w > 70f || h > 50f) return@mapNotNull null
            val aspect = w / max(1f, h)
            if (aspect < 0.45f || aspect > 3.8f) return@mapNotNull null

            // Exclude fixed HUD/chat/action-bar bands seen in the supplied recordings.
            if (r.y < 105 || r.y > height - 155 || r.x < 105 || r.x > width - 85) {
                return@mapNotNull null
            }
            val rectangularity = area / (r.width.toDouble() * r.height.toDouble()).coerceAtLeast(1.0)
            if (rectangularity < 0.28) return@mapNotNull null

            val confidence = (0.55 + 0.35 * rectangularity).coerceIn(0.0, 0.95)
            LevelBadge(tileClass, RectF(r.x.toFloat(), r.y.toFloat(),
                (r.x + r.width).toFloat(), (r.y + r.height).toFloat()), confidence)
        }
    }
}
