package com.coolhiman.lordsassistant

import com.coolhiman.lordsassistant.map.AffineGridCalibrator
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffineGridCalibratorTest {
    @Test
    fun fitsPanAndGridTransform() {
        val calibrator = AffineGridCalibrator()
        val k = 355
        calibrator.addSample(WorldCoordinate(k, 100, 200), ScreenPoint(500f, 600f))
        calibrator.addSample(WorldCoordinate(k, 101, 200), ScreenPoint(540f, 610f))
        calibrator.addSample(WorldCoordinate(k, 100, 201), ScreenPoint(480f, 630f))

        val result = calibrator.fit()
        assertNotNull(result)
        assertTrue(result!!.rmsErrorPx < 0.01)
        assertEquals(1.0, result.worldSpanX, 0.01)
        assertEquals(1.0, result.worldSpanY, 0.01)
        val predicted = result.predict(WorldCoordinate(k, 102, 202))
        assertEquals(540f, predicted.x, 0.01f)
        assertEquals(680f, predicted.y, 0.01f)

        assertEquals(WorldCoordinate(k, 102, 202), result.inverse(ScreenPoint(540f, 680f), k))
        assertNull(result.inverse(ScreenPoint(900f, 900f), k))
    }
    @Test
    fun rejectsNearlyCollinearCalibration() {
        val calibrator = AffineGridCalibrator()
        val k = 355
        calibrator.addSample(WorldCoordinate(k, 100, 100), ScreenPoint(500f, 500f))
        calibrator.addSample(WorldCoordinate(k, 101, 101), ScreenPoint(540f, 540f))
        calibrator.addSample(WorldCoordinate(k, 102, 102), ScreenPoint(580f, 580f))
        calibrator.addSample(WorldCoordinate(k, 103, 103), ScreenPoint(620f, 620f))

        assertNull(calibrator.fit())
    }

}
