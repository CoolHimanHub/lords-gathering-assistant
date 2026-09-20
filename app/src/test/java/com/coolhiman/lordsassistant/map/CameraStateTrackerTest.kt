package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraStateTrackerTest {
    private fun obs(x: Float, y: Float, worldX: Int): MapObservation =
        MapObservation(
            coordinate = WorldCoordinate(1, worldX, 100),
            screenPoint = ScreenPoint(x, y),
            label = "WOOD",
            level = 3,
            quantity = 1000L,
            occupied = false,
            incomingTroops = false,
            kind = TargetKind.RESOURCE,
            confidence = 0.9f
        )

    @Test
    fun consistentLargeShiftIsPanning() {
        val tracker = CameraStateTracker()
        tracker.update(listOf(obs(100f, 100f, 1), obs(200f, 200f, 2), obs(300f, 300f, 3)))
        val result = tracker.update(listOf(obs(180f, 100f, 1), obs(280f, 200f, 2), obs(380f, 300f, 3)))
        assertEquals(CameraState.PANNING, result.state)
    }

    @Test
    fun contradictoryShiftsAreUnstable() {
        val tracker = CameraStateTracker()
        tracker.update(listOf(obs(100f, 100f, 1), obs(200f, 200f, 2), obs(300f, 300f, 3)))
        val result = tracker.update(listOf(obs(300f, 100f, 1), obs(200f, 200f, 2), obs(200f, 300f, 3)))
        assertEquals(CameraState.UNSTABLE, result.state)
    }
}
