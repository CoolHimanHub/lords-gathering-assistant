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
    fun fit(anchors: List<CameraWorldAnchor>): CameraModel? {
        if (anchors.size < 3) return null

        val basePoints = anchors.map { baseCalibration.predict(it.world) }
        val currentPoints = anchors.map { it.screen }

        val baseMeanX = basePoints.map { it.x.toDouble() }.average()
        // At least three non-collinear anchors are required. Two points can
        // always be explained by a misleading scale/translation fit and cannot
        // distinguish ordinary zoom from rotation or other geometry changes.
        var maxTriangleArea = 0.0
        for (i in 0 until basePoints.size) {
            for (j in i + 1 until basePoints.size) {
                for (k in j + 1 until basePoints.size) {
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
        if (maxTriangleArea < 25.0) return null

        val baseMeanY = basePoints.map { it.y.toDouble() }.average()
        val currentMeanX = currentPoints.map { it.x.toDouble() }.average()
        val currentMeanY = currentPoints.map { it.y.toDouble() }.average()

        var denominator = 0.0
        var numerator = 0.0
        anchors.indices.forEach { i ->
            val bx = basePoints[i].x - baseMeanX.toFloat()
            val by = basePoints[i].y - baseMeanY.toFloat()
            val cx = currentPoints[i].x - currentMeanX.toFloat()
            val cy = currentPoints[i].y - currentMeanY.toFloat()
            denominator += bx * bx + by * by
            numerator += bx * cx + by * cy
        }

        if (denominator < 1e-6) return null
        val scale = numerator / denominator
        if (!scale.isFinite() || scale <= 1e-6) return null

        val offsetX = currentMeanX - scale * baseMeanX
        val offsetY = currentMeanY - scale * baseMeanY

        var squaredError = 0.0
        anchors.indices.forEach { i ->
            val predictedX = scale * basePoints[i].x + offsetX
            val predictedY = scale * basePoints[i].y + offsetY
            squaredError += hypot(
                predictedX - currentPoints[i].x,
                predictedY - currentPoints[i].y
            ).let { it * it }
        }

        val model = CameraModel(
            scale = scale,
            offsetX = offsetX,
            offsetY = offsetY,
            residualRmsPx = sqrt(squaredError / anchors.size)
        )
        return model.takeIf { it.isUsable(maxAnchorResidualPx) }
    }

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
        return baseCalibration.inverse(normalized, kingdom, maxResidualPx / model.scale)
    }
}
