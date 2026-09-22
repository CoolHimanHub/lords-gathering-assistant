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
     * Detects blue/cyan march indicators as a secondary signal. Orange directional arrows are handled by OrangeMarchDetector.
     * It is deliberately only a signal; popup validation remains authoritative.
     */
    fun detect(bitmap: Bitmap): List<MarchSignal> {
        if (!OpenCvRuntime.ensureLoaded()) return emptyList()
        val rgba = Mat()
        val hsv = Mat()
        val mask = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)

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
                (rect.x + rect.width / 2f),
                (rect.y + rect.height / 2f),
                area,
                (0.55 + (area.coerceAtMost(300.0) / 300.0 * 0.35)).coerceAtMost(0.9).toFloat()
            )
        }

        contours.forEach { it.release() }
        rgba.release(); hsv.release(); mask.release()
        return output
    }
}
