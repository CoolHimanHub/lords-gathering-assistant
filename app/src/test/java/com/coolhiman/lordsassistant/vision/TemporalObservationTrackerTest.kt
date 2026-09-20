package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.model.ScreenPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun highConfidenceUnknownDoesNotBecomeOccupied() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val unknown = observation(0.95f).copy(occupied = null, incomingTroops = null)
        assertEquals(0, tracker.update(listOf(unknown), 1000L).size)
        val stable = tracker.update(listOf(unknown), 1100L).single()
        assertNull(stable.occupied)
        assertNull(stable.incomingTroops)
    }
    @Test
    fun occupiedNodeNeedsRepeatedFreeEvidenceBeforeClearing() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val occupied = observation(0.90f).copy(occupied = true, incomingTroops = true)
        val free = observation(0.90f).copy(occupied = false, incomingTroops = false)

        assertEquals(1, tracker.update(listOf(occupied), 1000L).size)
        assertEquals(true, tracker.update(listOf(free), 1100L).first().occupied)
        assertEquals(false, tracker.update(listOf(free), 1200L).first().occupied)
    }

}
