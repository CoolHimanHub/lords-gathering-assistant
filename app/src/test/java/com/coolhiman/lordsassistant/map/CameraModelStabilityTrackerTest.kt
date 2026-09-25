package com.coolhiman.lordsassistant.map

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModelStabilityTrackerTest {
    private fun model(scale: Double, residual: Double = 2.0) =
        CameraModel(
            scale = scale,
            offsetX = 100.0,
            offsetY = -40.0,
            residualRmsPx = residual
        )

    @Test
    fun firstValidModelDoesNotAuthorizeContinuity() {
        val tracker = CameraModelStabilityTracker()
        assertFalse(tracker.update(model(1.5)))
    }

    @Test
    fun consecutiveEquivalentModelsAuthorizeContinuity() {
        val tracker = CameraModelStabilityTracker()
        assertFalse(tracker.update(model(1.5, 2.0)))
        assertTrue(tracker.update(model(1.5, 3.0)))
    }

    @Test
    fun normalPanChangingOffsetsPreservesContinuity() {
        val tracker = CameraModelStabilityTracker()
        assertFalse(tracker.update(model(1.5)))
        assertTrue(
            tracker.update(
                CameraModel(
                    scale = 1.5,
                    offsetX = 240.0,
                    offsetY = -160.0,
                    residualRmsPx = 3.0
                )
            )
        )
    }

    @Test
    fun abruptScaleChangeBreaksContinuity() {
        val tracker = CameraModelStabilityTracker()
        assertFalse(tracker.update(model(1.5)))
        assertFalse(tracker.update(model(1.6)))
        assertTrue(tracker.update(model(1.6)))
    }

    @Test
    fun unusableModelResetsContinuity() {
        val tracker = CameraModelStabilityTracker()
        assertFalse(tracker.update(model(1.5)))
        assertTrue(tracker.update(model(1.5)))
        assertFalse(
            tracker.update(
                CameraModel(
                    scale = 1.5,
                    offsetX = 100.0,
                    offsetY = -40.0,
                    residualRmsPx = 30.0
                )
            )
        )
        assertFalse(tracker.update(model(1.5)))
    }
}
