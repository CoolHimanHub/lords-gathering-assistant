package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AffineGridCalibratorTest {
    @Test
    fun fitsTwoDimensionalGridAndPredictsKnownNeighbour() {
        val calibrator = AffineGridCalibrator()
        val samples = listOf(
            WorldCoordinate(355, 100, 200) to ScreenPoint(500f, 300f),
            WorldCoordinate(355, 101, 200) to ScreenPoint(514f, 292f),
            WorldCoordinate(355, 100, 201) to ScreenPoint(522f, 314f),
            WorldCoordinate(355, 101, 201) to ScreenPoint(536f, 306f)
        )
        samples.forEach { (world, screen) -> calibrator.addSample(world, screen) }

        val fit = calibrator.fit()
        assertNotNull(fit)
        assertTrue(fit!!.rmsErrorPx < 0.01)

        val predicted = fit.predict(WorldCoordinate(355, 102, 202))
        assertEquals(572f, predicted.x, 0.01f)
        assertEquals(312f, predicted.y, 0.01f)
    }

    @Test
    fun inverseRoundsToNearestWorldCellWithinResidual() {
        val calibrator = AffineGridCalibrator()
        val samples = listOf(
            WorldCoordinate(355, 10, 20) to ScreenPoint(400f, 300f),
            WorldCoordinate(355, 11, 20) to ScreenPoint(414f, 292f),
            WorldCoordinate(355, 10, 21) to ScreenPoint(422f, 314f),
            WorldCoordinate(355, 11, 21) to ScreenPoint(436f, 306f)
        )
        samples.forEach { (world, screen) -> calibrator.addSample(world, screen) }

        val fit = calibrator.fit()
        assertNotNull(fit)

        val recovered = fit!!.inverse(ScreenPoint(414.8f, 291.4f), 355)
        assertNotNull(recovered)
        assertEquals(355, recovered!!.kingdom)
        assertEquals(11, recovered.x)
        assertEquals(20, recovered.y)
    }

    @Test
    fun rejectsOneDimensionalSamples() {
        val calibrator = AffineGridCalibrator()
        calibrator.addSample(WorldCoordinate(355, 10, 20), ScreenPoint(400f, 300f))
        calibrator.addSample(WorldCoordinate(355, 11, 20), ScreenPoint(414f, 292f))
        calibrator.addSample(WorldCoordinate(355, 12, 20), ScreenPoint(428f, 284f))

        assertTrue(calibrator.fit() == null)
    }
}
