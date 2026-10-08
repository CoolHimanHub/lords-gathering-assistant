package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AffineGridCalibratorStabilityTest {
    @Test
    fun rejectsOneDimensionalCalibration() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(100f, 200f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(120f, 200f))
        calibrator.addSample(WorldCoordinate(355, 12, 20), ScreenPoint(140f, 200f))
        assertNull(calibrator.fit())
    }

    @Test
    fun rejectsMixedKingdomCalibration() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(100f, 200f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(120f, 220f))
        calibrator.addSample(WorldCoordinate(355, 10, 21), ScreenPoint(80f, 220f))
        calibrator.addSample(WorldCoordinate(356, 11, 21), ScreenPoint(100f, 240f))
        assertNull(calibrator.fit())
    }

    @Test
    fun readinessRejectsMixedKingdomSamples() {
        val calibrator = AffineGridCalibrator()
        repeat(6) { index ->
            calibrator.addSample(WorldCoordinate(355, 10 + index, 20), ScreenPoint(100f + index * 20f, 200f))
        }
        calibrator.addSample(WorldCoordinate(356, 16, 20), ScreenPoint(220f, 200f))
        assertFalse(calibrator.readiness().ready)
        assertEquals("MIXED_KINGDOM", calibrator.readiness().reason)
    }

    @Test
    fun readinessRequiresEnoughSamplesBeforeSyntheticProbing() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(100f, 200f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(120f, 220f))
        calibrator.addSample(WorldCoordinate(355, 10, 21), ScreenPoint(80f, 220f))
        val readiness = calibrator.readiness()
        assertFalse(readiness.ready)
        assertEquals("NEED_SAMPLES", readiness.reason)
    }

    @Test
    fun readinessAcceptsCleanSixSampleCalibration() {
        val calibrator = AffineGridCalibrator()
        for (y in 20..21) {
            for (x in 10..12) {
                calibrator.addSample(
                    WorldCoordinate(355, x, y),
                    ScreenPoint((100f + (x - 10) * 20f + (y - 20) * 8f), (200f + (x - 10) * 10f + (y - 20) * 20f))
                )
            }
        }
        val readiness = calibrator.readiness()
        assertTrue(readiness.ready)
        assertEquals("READY", readiness.reason)
    }

    @Test
    fun acceptsTwoDimensionalCalibration() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(100f, 200f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(120f, 220f))
        calibrator.addSample(WorldCoordinate(355, 10, 21), ScreenPoint(80f, 220f))
        calibrator.addSample(WorldCoordinate(355, 11, 21), ScreenPoint(100f, 240f))
        assertNotNull(calibrator.fit())
    }

    @Test
    fun inverseKeepsWorkingForLegacyUnboundedCalibration() {
        val calibration = Calibration(
            screenX = doubleArrayOf(2.0, 0.5, 100.0),
            screenY = doubleArrayOf(-0.25, 3.0, 200.0),
            rmsErrorPx = 0.0
        )

        assertEquals(
            WorldCoordinate(355, 7, 4),
            calibration.inverse(ScreenPoint(116f, 210.25f), 355)
        )
    }
}
