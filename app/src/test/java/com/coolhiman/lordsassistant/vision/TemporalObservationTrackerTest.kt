package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.model.ScreenPoint
import org.junit.Assert.assertEquals
import org.junit.Test

class TemporalObservationTrackerTest {
    private fun observation(confidence: Float = 0.85f) = MapObservation(
        coordinate = WorldCoordinate(355, 10, 20),
        screenPoint = ScreenPoint(100f, 100f),
        label = "WOOD",
        level = 3,
        quantity = 100000L,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = confidence,
        timestampMs = 1000L
    )

    @Test
    fun requiresTwoConsistentSightings() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        assertEquals(0, tracker.update(listOf(observation()), 1000L).size)
        assertEquals(1, tracker.update(listOf(observation()), 1100L).size)
    }

    @Test
    fun strongObservationCanPassImmediately() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        assertEquals(1, tracker.update(listOf(observation(0.95f)), 1000L).size)
    }
}
