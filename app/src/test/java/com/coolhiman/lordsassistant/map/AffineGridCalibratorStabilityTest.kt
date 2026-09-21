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
    fun acceptsTwoDimensionalCalibration() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(100f, 200f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(120f, 220f))
        calibrator.addSample(WorldCoordinate(355, 10, 21), ScreenPoint(80f, 220f))
        calibrator.addSample(WorldCoordinate(355, 11, 21), ScreenPoint(100f, 240f))
        assertNotNull(calibrator.fit())
    }
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
