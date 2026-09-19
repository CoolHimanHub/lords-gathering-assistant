package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.ViewportCalibration
import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.abs

class CoordinateTracker {
    private val anchors = ArrayDeque<Pair<WorldCoordinate, ScreenPoint>>(8)

    fun observe(coordinate: WorldCoordinate, point: ScreenPoint) {
        if (anchors.size == 8) anchors.removeFirst()
        anchors.addLast(coordinate to point)
    }

    fun calibration(): ViewportCalibration? {
        if (anchors.size < 2) return null
        val first = anchors.first()
        val last = anchors.last()
        val dx = last.first.x - first.first.x
        val dy = last.first.y - first.first.y
        val px = last.second.x - first.second.x
        val py = last.second.y - first.second.y
        val denominator = (abs(dx) + abs(dy)).coerceAtLeast(1)
        return ViewportCalibration(
            anchor = last.first,
            anchorPx = last.second,
            pixelsPerTileX = abs(px) / denominator,
            pixelsPerTileY = abs(py) / denominator
        )
    }
}
