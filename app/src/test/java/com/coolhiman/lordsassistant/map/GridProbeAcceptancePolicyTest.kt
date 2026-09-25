package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridProbeAcceptancePolicyTest {
    private val expected = WorldCoordinate(355, 100, 200)

    @Test
    fun semanticProbeAllowsOneTileExpectationDrift() {
        assertTrue(GridProbeAcceptancePolicy.accepts(expected, WorldCoordinate(355, 101, 201), "semantic"))
    }

    @Test
    fun predictedGridProbeRequiresExactCoordinate() {
        assertFalse(GridProbeAcceptancePolicy.accepts(expected, WorldCoordinate(355, 101, 200), "predicted-grid"))
    }

    @Test
    fun predictedGridProbeAcceptsExactCoordinate() {
        assertTrue(GridProbeAcceptancePolicy.accepts(expected, expected, "predicted-grid"))
    }

    @Test
    fun kingdomMismatchAlwaysRejects() {
        assertFalse(GridProbeAcceptancePolicy.accepts(expected, WorldCoordinate(356, 100, 200), "semantic"))
    }

    @Test
    fun expectationFreeProbeAcceptsPopupCoordinate() {
        assertTrue(GridProbeAcceptancePolicy.accepts(null, WorldCoordinate(355, 100, 200), "semantic"))
    }

    @Test
    fun missingPopupCoordinateIsRejected() {
        assertFalse(GridProbeAcceptancePolicy.accepts(expected, null, "semantic"))
    }
}
