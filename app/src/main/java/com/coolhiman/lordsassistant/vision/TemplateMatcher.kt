package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

data class MatchResult(val score: Double, val x: Int, val y: Int, val width: Int, val height: Int)

class TemplateMatcher {
    fun match(screen: Bitmap, template: Bitmap, threshold: Double = 0.82): MatchResult? {
        val source = Mat()
        val needle = Mat()
        Utils.bitmapToMat(screen, source)
        Utils.bitmapToMat(template, needle)

        val gs = Mat()
        val gn = Mat()
        Imgproc.cvtColor(source, gs, Imgproc.COLOR_RGBA2GRAY)
        Imgproc.cvtColor(needle, gn, Imgproc.COLOR_RGBA2GRAY)

        if (gs.cols() < gn.cols() || gs.rows() < gn.rows()) return null

        val result = Mat()
        Imgproc.matchTemplate(gs, gn, result, Imgproc.TM_CCOEFF_NORMED)
        val max = Core.minMaxLoc(result).maxLoc
        val score = Core.minMaxLoc(result).maxVal

        return if (score >= threshold)
            MatchResult(score, max.x.toInt(), max.y.toInt(), gn.cols(), gn.rows())
        else null
    }
}
