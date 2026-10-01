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
        // The live HUD can expose X/Y without a reliable K value; OcrParser
        // represents that missing kingdom as 0. A semantic probe remains useful
        // because the popup coordinate is authoritative. Predicted-grid probes
        // must never use this wildcard because their expected cell comes from
        // the established calibration.
        if (expected.kingdom == 0 && source != "predicted-grid") return true
        if (expected.kingdom != actual.kingdom) return false

        return if (source == "predicted-grid") {
            expected == actual
        } else {
            abs(expected.x - actual.x) <= 1 &&
                abs(expected.y - actual.y) <= 1
        }
    }
}
