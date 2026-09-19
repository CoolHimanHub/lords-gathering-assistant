package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

data class MarchSignal(
    val x: Float,
    val y: Float,
    val area: Double,
    val confidence: Float
)

class BlueMarchDetector {
    /**
     * Heuristic detector for blue directional/march indicators.
     * It is deliberately only a signal; popup validation remains authoritative.
     */
    fun detect(bitmap: Bitmap): List<MarchSignal> {
        val rgba = Mat()
        val hsv = Mat()
        val mask = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2HSV)

        // Broad blue/cyan range; tune from recorded frames during V0.3 labeling.
        Core.inRange(hsv, Scalar(85.0, 90.0, 80.0), Scalar(135.0, 255.0, 255.0), mask)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val output = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area < 18.0 || area > 20_000.0) return@mapNotNull null
            val rect = Imgproc.boundingRect(contour)
            val aspect = rect.width.toDouble() / rect.height.coerceAtLeast(1)
            if (aspect < 0.5 || aspect > 8.0) return@mapNotNull null
            MarchSignal(
                rect.centerX().toFloat(),
                rect.centerY().toFloat(),
                area,
                (0.55f + (area.coerceAtMost(300.0) / 300.0 * 0.35f)).coerceAtMost(0.9f)
            )
        }

        contours.forEach { it.release() }
        rgba.release(); hsv.release(); mask.release()
        return output
    }
}
