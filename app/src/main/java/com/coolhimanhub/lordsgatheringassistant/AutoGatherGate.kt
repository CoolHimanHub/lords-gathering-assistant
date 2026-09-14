package com.coolhimanhub.lordsgatheringassistant

/**
 * V55.3 central gate for troop-action authorization.
 *
 * Keeps the hot screenshot path cheap: boolean checks only. The caller must
 * still run TilePanelVerifier immediately before pressing Gather.
 */
class AutoGatherGate(
    private val minGridSamples: Int = 10,
    private val maxMedianResidualPx: Float = 14f
) {
    data class GridStatus(val samples: Int, val medianResidualPx: Float, val locked: Boolean)

    fun gridLocked(samples: Int, medianResidualPx: Float): Boolean =
        samples >= minGridSamples && medianResidualPx <= maxMedianResidualPx

    fun authorize(
        enabled: Boolean,
        grid: GridStatus,
        tilePanelConfirmsGather: Boolean,
        targetStillValid: Boolean
    ): Boolean =
        enabled && grid.locked &&
            gridLocked(grid.samples, grid.medianResidualPx) &&
            tilePanelConfirmsGather && targetStillValid
}
