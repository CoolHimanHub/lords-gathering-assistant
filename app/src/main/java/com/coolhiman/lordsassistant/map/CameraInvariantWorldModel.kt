package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * V0.7.0 camera-invariant world-coordinate boundary.
 *
 * The learned affine calibration provides the stable map geometry. A live
 * camera frame may then apply uniform zoom plus screen translation. Instead
 * of refitting the world geometry for every camera movement, this component
 * estimates only that camera state from known world/screen anchors.
 *
 * Rotation/skew changes are deliberately not absorbed here; they invalidate
 * the current camera model and should remain fail-closed until recalibrated.
 */
data class CameraWorldAnchor(
    val world: WorldCoordinate,
    val screen: ScreenPoint
)

data class CameraModel(
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val residualRmsPx: Double
) {
    fun isUsable(maxResidualPx: Double = 25.0): Boolean =
        scale.isFinite() &&
            scale > 1e-6 &&
            offsetX.isFinite() &&
            offsetY.isFinite() &&
            residualRmsPx.isFinite() &&
            residualRmsPx <= maxResidualPx
}

class CameraInvariantWorldModel(
    private val baseCalibration: Calibration,
    private val maxAnchorResidualPx: Double = 25.0
) {
    companion object {
        private const val MIN_TRIANGLE_AREA = 25.0
        private const val MAX_ROBUST_OUTLIERS = 1
        private const val MIN_OUTLIER_RESIDUAL_PX = 30.0
        private const val OUTLIER_RATIO = 2.5
        private const val REQUIRED_RMS_IMPROVEMENT = 0.65
    }

    fun fit(anchors: List<CameraWorldAnchor>): CameraModel? {
        if (anchors.size < 3) return null

        val basePoints = anchors.map { baseCalibration.predict(it.world) }
        val currentPoints = anchors.map { it.screen }
        var active = anchors.indices.toList()
        var best = fitLeastSquares(basePoints, currentPoints, active) ?: return null

        repeat(MAX_ROBUST_OUTLIERS) {
            if (active.size <= 3) return@repeat

            val worst = active.maxByOrNull { index ->
                residualPx(best, basePoints[index], currentPoints[index])
            } ?: return@repeat
            val worstResidual = residualPx(best, basePoints[worst], currentPoints[worst])
            if (worstResidual <= MIN_OUTLIER_RESIDUAL_PX) return@repeat

            // Leave-one-out fitting avoids allowing a few bad anchors to inflate
            // the global RMS enough that the bad anchor passes the threshold.
            val candidates = active
                .filter { it != worst }
                .mapNotNull { removed ->
                    val candidateActive = active.filterNot { it == removed }
                    fitLeastSquares(basePoints, currentPoints, candidateActive)
                        ?.let { removed to it }
                }
            val (bestRemoved, candidate) = candidates.minByOrNull { it.second.residualRmsPx }
                ?: return@repeat
            val removedResidual = residualPx(
                best,
                basePoints[bestRemoved],
                currentPoints[bestRemoved]
            )
            if (removedResidual <= MIN_OUTLIER_RESIDUAL_PX ||
                candidate.residualRmsPx > best.residualRmsPx * REQUIRED_RMS_IMPROVEMENT
            ) {
                return@repeat
            }

            active = active.filterNot { it == bestRemoved }
            best = candidate
        }

        return best.takeIf { it.isUsable(maxAnchorResidualPx) }
    }

    private fun fitLeastSquares(
        basePoints: List<com.coolhiman.lordsassistant.model.ScreenPoint>,
        currentPoints: List<com.coolhiman.lordsassistant.model.ScreenPoint>,
        active: List<Int>
    ): CameraModel? {
        if (active.size < 3) return null

        var maxTriangleArea = 0.0
        for (a in 0 until active.size) {
            for (b in a + 1 until active.size) {
                for (c in b + 1 until active.size) {
                    val i = active[a]
                    val j = active[b]
                    val k = active[c]
                    val ax = basePoints[j].x - basePoints[i].x
                    val ay = basePoints[j].y - basePoints[i].y
                    val bx = basePoints[k].x - basePoints[i].x
                    val by = basePoints[k].y - basePoints[i].y
                    maxTriangleArea = maxOf(
                        maxTriangleArea,
                        kotlin.math.abs(ax * by - ay * bx).toDouble()
                    )
                }
            }
        }
        if (maxTriangleArea < MIN_TRIANGLE_AREA) return null

        val baseMeanX = active.map { basePoints[it].x.toDouble() }.average()
        val baseMeanY = active.map { basePoints[it].y.toDouble() }.average()
        val currentMeanX = active.map { currentPoints[it].x.toDouble() }.average()
        val currentMeanY = active.map { currentPoints[it].y.toDouble() }.average()

        var denominator = 0.0
        var numerator = 0.0
        for (index in active) {
            val bx = basePoints[index].x - baseMeanX.toFloat()
            val by = basePoints[index].y - baseMeanY.toFloat()
            val cx = currentPoints[index].x - currentMeanX.toFloat()
            val cy = currentPoints[index].y - currentMeanY.toFloat()
            denominator += bx * bx + by * by
            numerator += bx * cx + by * cy
        }

        if (denominator < 1e-6) return null
        val scale = numerator / denominator
        if (!scale.isFinite() || scale <= 1e-6) return null

        val offsetX = currentMeanX - scale * baseMeanX
        val offsetY = currentMeanY - scale * baseMeanY

        var squaredError = 0.0
        for (index in active) {
            val residual = residualPx(
                scale,
                offsetX,
                offsetY,
                basePoints[index],
                currentPoints[index]
            )
            squaredError += residual * residual
        }

        return CameraModel(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            residualRmsPx = sqrt(squaredError / active.size)
        )
    }

    private fun residualPx(
        model: CameraModel,
        base: com.coolhiman.lordsassistant.model.ScreenPoint,
        current: com.coolhiman.lordsassistant.model.ScreenPoint
    ): Double = residualPx(model.scale, model.offsetX, model.offsetY, base, current)

    private fun residualPx(
        scale: Double,
        offsetX: Double,
        offsetY: Double,
        base: com.coolhiman.lordsassistant.model.ScreenPoint,
        current: com.coolhiman.lordsassistant.model.ScreenPoint
    ): Double = hypot(
        scale * base.x + offsetX - current.x,
        scale * base.y + offsetY - current.y
    )

    fun resolve(
        screen: ScreenPoint,
        kingdom: Int,
        model: CameraModel,
        maxResidualPx: Double = 35.0
    ): WorldCoordinate? {
        if (!model.isUsable(maxAnchorResidualPx)) return null
        val normalized = ScreenPoint(
            ((screen.x - model.offsetX) / model.scale).toFloat(),
            ((screen.y - model.offsetY) / model.scale).toFloat()
        )
        return baseCalibration.inverse(
            normalized,
            kingdom,
            maxResidualPx.coerceAtMost(maxAnchorResidualPx)
        )
    }
}
