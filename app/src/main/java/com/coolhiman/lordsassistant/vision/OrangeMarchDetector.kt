package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc

/**
 * Detects orange directional arrows used by Lords Mobile for active/incoming
 * march paths. This is evidence only and never authoritative occupancy.
 */
class OrangeMarchDetector {
    fun detect(bitmap: Bitmap): List<MarchSignal> {
        if (!OpenCvRuntime.ensureLoaded()) return emptyList()
        val rgba = Mat()
        val hsv = Mat()
        val mask = Mat()
        Utils.bitmapToMat(bitmap, rgba)
        Imgproc.cvtColor(rgba, hsv, Imgproc.COLOR_RGBA2RGB)
        Imgproc.cvtColor(hsv, hsv, Imgproc.COLOR_RGB2HSV)
        Core.inRange(hsv, Scalar(5.0, 120.0, 100.0), Scalar(30.0, 255.0, 255.0), mask)

        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(mask, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val output = contours.mapNotNull { contour ->
            val area = Imgproc.contourArea(contour)
            if (area < 6.0 || area > 1_200.0) return@mapNotNull null
            val rect = Imgproc.boundingRect(contour)
            if (rect.width < 3 || rect.height < 3) return@mapNotNull null
            val aspect = rect.width.toDouble() / rect.height.coerceAtLeast(1)
            if (aspect < 0.25 || aspect > 4.5) return@mapNotNull null

            if (rect.y < 110 || rect.y + rect.height > bitmap.height - 150) return@mapNotNull null
            if (rect.x < 120 || rect.x + rect.width > bitmap.width - 90) return@mapNotNull null

            val compactness = area /
                (rect.width.toDouble() * rect.height.toDouble()).coerceAtLeast(1.0)
            MarchSignal(
                (rect.x + rect.width / 2f),
                (rect.y + rect.height / 2f),
                area,
                (0.55 + compactness.coerceIn(0.0, 1.0) * 0.35).toFloat()
            )
        }

        contours.forEach { it.release() }
        rgba.release()
        hsv.release()
        mask.release()
        return output
    }
}
