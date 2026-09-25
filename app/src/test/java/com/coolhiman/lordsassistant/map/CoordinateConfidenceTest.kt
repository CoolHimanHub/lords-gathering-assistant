package com.coolhiman.lordsassistant.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateConfidenceTest {
    @Test
    fun observedCoordinateIsActionAuthoritativeOnlyWhenAllGatesAreHealthy() {
        assertTrue(CoordinateConfidence.observed(true, true).actionAuthoritative)
        assertFalse(CoordinateConfidence.observed(false, true).actionAuthoritative)
        assertFalse(CoordinateConfidence.observed(true, false).actionAuthoritative)
    }

    @Test
    fun calibratedCoordinateNeverBecomesActionAuthoritative() {
        val confidence = CoordinateConfidence.calibrated(
            calibrationUsable = true,
            cameraStable = true,
            residualPx = 4.0
        )
        assertTrue(confidence.authority == CoordinateAuthority.CALIBRATED)
        assertFalse(confidence.actionAuthoritative)
    }

    @Test
    fun observedCoordinateCanCarryResidualWithoutLosingAuthority() {
        val confidence = CoordinateConfidence.observed(true, true, residualPx = 3.5)
        assertTrue(confidence.actionAuthoritative)
        assertTrue(confidence.residualPx == 3.5)
    }

    @Test
    fun noCoordinateHasZeroConfidence() {
        val confidence = CoordinateConfidence.none()
        assertTrue(confidence.authority == CoordinateAuthority.NONE)
        assertTrue(confidence.confidence == 0f)
        assertFalse(confidence.actionAuthoritative)
    }
}
