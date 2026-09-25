package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.WorldCoordinate
import kotlin.math.abs

/**
 * Decides whether a popup coordinate is safe to feed back into grid learning.
 *
 * Semantic probes may be off by one tile because their coordinate is only an
 * expectation. Predicted-grid probes are different: their expected world cell
 * came from the current calibration, so accepting a one-tile error would let
 * the frontier drift one cell at a time and eventually poison the learned map.
 */
object GridProbeAcceptancePolicy {
    fun accepts(
        expected: WorldCoordinate?,
        actual: WorldCoordinate?,
        source: String
    ): Boolean {
        if (actual == null) return false
        if (expected == null) return true
        if (expected.kingdom != actual.kingdom) return false

        return if (source == "predicted-grid") {
            expected == actual
        } else {
            abs(expected.x - actual.x) <= 1 &&
                abs(expected.y - actual.y) <= 1
        }
    }
}
