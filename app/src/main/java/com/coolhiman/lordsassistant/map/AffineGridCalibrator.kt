package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.abs

/**
 * Learns screen <-> world coordinates from observations.
 *
 * The calibration is deliberately affine rather than assuming a fixed
 * isometric pixel formula. A small robust fitting layer prevents one bad
 * popup/anchor pair from moving the transform used to generate the next
 * synthetic probe.
 */
class AffineGridCalibrator {
    companion object {
        private const val MIN_GEOMETRY_SCORE = 0.05
        private const val MAX_ROBUST_OUTLIERS = 2
        private const val MIN_OUTLIER_RESIDUAL_PX = 30.0
        private const val OUTLIER_RATIO = 1.5
        private const val REQUIRED_RMS_IMPROVEMENT = 0.65
    }

    private val samples = ArrayDeque<Pair<WorldCoordinate, ScreenPoint>>(32)

    fun addSample(world: WorldCoordinate, screen: ScreenPoint) {
        if (samples.size == 32) samples.removeFirst()
        samples.addLast(world to screen)
    }

    fun clear() = samples.clear()

    fun sampleCount(): Int = samples.size

    fun fit(): Calibration? {
        if (samples.size < 3) return null

        var working = samples.toList()
        var fit = fitLeastSquares(working) ?: return null

        // Probe coordinates come from the game popup, but the screen point is
        // still an observation. A single mis-tap, OCR association error, or
        // stale frame can therefore become a high-leverage affine outlier.
        // Iteratively discard only a very clear outlier when doing so produces
        // a substantial RMS improvement. The raw samples remain durable; only
        // the active model excludes the suspect point.
        repeat(MAX_ROBUST_OUTLIERS) {
            if (working.size < 5) return@repeat

            val residuals = working.map { (world, screen) ->
                val predicted = fit.predict(world)
                kotlin.math.hypot(
                    predicted.x.toDouble() - screen.x,
                    predicted.y.toDouble() - screen.y
                )
            }
            val worstIndex = residuals.indices.maxByOrNull { residuals[it] } ?: return@repeat
            val worst = residuals[worstIndex]
            val threshold = maxOf(MIN_OUTLIER_RESIDUAL_PX, fit.rmsErrorPx * OUTLIER_RATIO)
            if (worst <= threshold) return@repeat

            val candidate = working.filterIndexed { index, _ -> index != worstIndex }
            val candidateFit = fitLeastSquares(candidate) ?: return@repeat
            if (candidateFit.rmsErrorPx > fit.rmsErrorPx * REQUIRED_RMS_IMPROVEMENT) {
                return@repeat
            }

            working = candidate
            fit = candidateFit
        }

        return fit
    }

    private fun fitLeastSquares(
        source: List<Pair<WorldCoordinate, ScreenPoint>>
    ): Calibration? {
        if (source.size < 3) return null

        val xs = source.map { it.first.x.toDouble() }
        val ys = source.map { it.first.y.toDouble() }
        val xSpan = xs.maxOrNull()!! - xs.minOrNull()!!
        val ySpan = ys.maxOrNull()!! - ys.minOrNull()!!
        if (xSpan <= 0.0 || ySpan <= 0.0) return null

        val meanX = xs.average()
        val meanY = ys.average()
        var covXX = 0.0
        var covYY = 0.0
        var covXY = 0.0
        source.forEach {
            val dx = it.first.x - meanX
            val dy = it.first.y - meanY
            covXX += dx * dx
            covYY += dy * dy
            covXY += dx * dy
        }
        val covarianceDet = covXX * covYY - covXY * covXY
        if (covarianceDet < 1e-6) return null
        val geometryScore = covarianceDet / (covXX * covYY).coerceAtLeast(1e-12)
        if (geometryScore < MIN_GEOMETRY_SCORE) return null

        val a = Array(3) { DoubleArray(3) }
        val bx = DoubleArray(3)
        val by = DoubleArray(3)

        source.forEach { (w, p) ->
            val row = doubleArrayOf(w.x.toDouble(), w.y.toDouble(), 1.0)
            for (i in 0..2) {
                for (j in 0..2) a[i][j] += row[i] * row[j]
                bx[i] += row[i] * p.x
                by[i] += row[i] * p.y
            }
        }

        val cx = solve3(a, bx) ?: return null
        val cy = solve3(a, by) ?: return null

        var error = 0.0
        source.forEach { (w, p) ->
            val px = cx[0] * w.x + cx[1] * w.y + cx[2]
            val py = cy[0] * w.x + cy[1] * w.y + cy[2]
            error += (px - p.x) * (px - p.x) + (py - p.y) * (py - p.y)
        }

        return Calibration(
            screenX = cx,
            screenY = cy,
            rmsErrorPx = kotlin.math.sqrt(error / source.size),
            minWorldX = xs.minOrNull()!!.toInt(),
            maxWorldX = xs.maxOrNull()!!.toInt(),
            minWorldY = ys.minOrNull()!!.toInt(),
            maxWorldY = ys.maxOrNull()!!.toInt(),
            worldSpanX = xSpan,
            worldSpanY = ySpan,
            geometryScore = geometryScore
        )
    }

    private fun solve3(input: Array<DoubleArray>, rhs: DoubleArray): DoubleArray? {
        val m = Array(3) { i ->
            DoubleArray(4) { j -> if (j < 3) input[i][j] else rhs[i] }
        }

        for (col in 0..2) {
            var pivot = col
            for (row in col + 1..2) {
                if (abs(m[row][col]) > abs(m[pivot][col])) pivot = row
            }
            if (abs(m[pivot][col]) < 1e-8) return null
            val tmp = m[col]
            m[col] = m[pivot]
            m[pivot] = tmp

            val divisor = m[col][col]
            for (j in col..3) m[col][j] /= divisor

            for (row in 0..2) {
                if (row == col) continue
                val factor = m[row][col]
                for (j in col..3) m[row][j] -= factor * m[col][j]
            }
        }
        return doubleArrayOf(m[0][3], m[1][3], m[2][3])
    }
}

data class Calibration(
    val screenX: DoubleArray,
    val screenY: DoubleArray,
    val rmsErrorPx: Double,
    val minWorldX: Int = Int.MIN_VALUE,
    val maxWorldX: Int = Int.MAX_VALUE,
    val minWorldY: Int = Int.MIN_VALUE,
    val maxWorldY: Int = Int.MAX_VALUE,
    val worldSpanX: Double = 0.0,
    val worldSpanY: Double = 0.0,
    val geometryScore: Double = 0.0
) {
    fun predict(world: WorldCoordinate): ScreenPoint =
        ScreenPoint(
            (screenX[0] * world.x + screenX[1] * world.y + screenX[2]).toFloat(),
            (screenY[0] * world.x + screenY[1] * world.y + screenY[2]).toFloat()
        )

    fun inverse(screen: ScreenPoint, kingdom: Int, maxResidualPx: Double = 35.0): WorldCoordinate? {
        val det = screenX[0] * screenY[1] - screenX[1] * screenY[0]
        if (abs(det) < 1e-8) return null
        val sx = screen.x.toDouble() - screenX[2]
        val sy = screen.y.toDouble() - screenY[2]
        val worldX = (sx * screenY[1] - screenX[1] * sy) / det
        val worldY = (screenX[0] * sy - sx * screenY[0]) / det
        val candidateX = kotlin.math.round(worldX).toInt()
        val candidateY = kotlin.math.round(worldY).toInt()
        val boundedX = minWorldX != Int.MIN_VALUE && maxWorldX != Int.MAX_VALUE
        val boundedY = minWorldY != Int.MIN_VALUE && maxWorldY != Int.MAX_VALUE
        if (boundedX && candidateX !in (minWorldX - 1)..(maxWorldX + 1)) return null
        if (boundedY && candidateY !in (minWorldY - 1)..(maxWorldY + 1)) return null
        val candidate = WorldCoordinate(kingdom, candidateX, candidateY)
        val predicted = predict(candidate)
        val residual = kotlin.math.hypot(
            predicted.x.toDouble() - screen.x,
            predicted.y.toDouble() - screen.y
        )
        return candidate.takeIf { residual <= maxResidualPx }
    }

    fun isUsable(maxRmsPx: Double = 35.0, minGeometryScore: Double = 0.05): Boolean =
        rmsErrorPx <= maxRmsPx && geometryScore >= minGeometryScore
}
