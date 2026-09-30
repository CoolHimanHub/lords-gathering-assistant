package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.CoordinateAuthority
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
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
        timestampMs = 1000L,
        coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 4.0)
    )

    @Test
    fun requiresTwoConsistentSightings() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        assertEquals(0, tracker.update(listOf(observation()), 1000L).size)
        assertEquals(1, tracker.update(listOf(observation()), 1100L).size)
    }

    @Test
    fun duplicateDetectionsInOneFrameCannotManufactureConfirmation() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val duplicate = observation()

        assertEquals(0, tracker.update(listOf(duplicate, duplicate.copy(timestampMs = 1001L)), 1000L).size)
        assertEquals(1, tracker.update(listOf(duplicate.copy(timestampMs = 1100L)), 1100L).size)
    }

    @Test
    fun observedProvenanceDoesNotLeakIntoLaterCalibratedFrame() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val observed = observation()
        val calibrated = observed.copy(
            confidence = 0.80f,
            coordinateConfidence = CoordinateConfidence.calibrated(
                calibrationUsable = true,
                cameraStable = true,
                residualPx = 6.0
            )
        )

        tracker.update(listOf(observed), 1000L)
        val stable = tracker.update(listOf(calibrated), 1100L).single()

        assertEquals(CoordinateAuthority.CALIBRATED, stable.coordinateConfidence.authority)
        assertEquals(6.0, stable.coordinateConfidence.residualPx)
    }

    @Test
    fun differentTargetLabelDoesNotReuseCoordinateDriftedTrack() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val wood = observation().copy(
            label = "WOOD",
            coordinate = WorldCoordinate(355, 10, 20),
            screenPoint = ScreenPoint(100f, 100f)
        )
        val stone = wood.copy(
            label = "STONE",
            coordinate = WorldCoordinate(355, 11, 21),
            screenPoint = ScreenPoint(104f, 97f)
        )

        assertEquals(0, tracker.update(listOf(wood), 1000L).size)
        assertEquals(0, tracker.update(listOf(stone), 1100L).size)
        assertEquals(1, tracker.update(listOf(stone), 1200L).size)
    }

    @Test
    fun semanticIdentityDropoutDoesNotCarryOldIdentityIntoConfirmation() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val wood = observation().copy(label = "WOOD")
        val unlabeled = wood.copy(label = null)

        assertEquals(0, tracker.update(listOf(wood), 1000L).size)
        assertEquals(0, tracker.update(listOf(unlabeled), 1100L).size)

        val reidentified = tracker.update(listOf(wood), 1200L)
        assertEquals(0, reidentified.size)
        assertEquals(1, tracker.update(listOf(wood), 1300L).size)
    }

    @Test
    fun semanticIdentityChangeRequiresFreshConfirmation() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val wood = observation().copy(label = "WOOD")
        val stone = wood.copy(label = "STONE")

        assertEquals(0, tracker.update(listOf(wood), 1000L).size)
        assertEquals(0, tracker.update(listOf(stone), 1100L).size)
        assertEquals(0, tracker.update(listOf(wood), 1200L).size)
        assertEquals(1, tracker.update(listOf(wood), 1300L).size)
    }

    @Test
    fun lowerConfidenceSemanticMismatchDoesNotInheritPreviousLabel() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val wood = observation(0.95f).copy(label = "WOOD")
        val stone = observation(0.40f).copy(label = "STONE")

        assertEquals(0, tracker.update(listOf(wood), 1000L).size)
        assertEquals(0, tracker.update(listOf(stone), 1100L).size)
        assertEquals(1, tracker.update(listOf(stone), 1200L).size)

        val stable = tracker.update(listOf(stone), 1300L).single()
        assertEquals("STONE", stable.label)
    }

    @Test
    fun lowerConfidenceFrameStillUpdatesFreshQuantity() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val first = observation(0.95f).copy(quantity = 100_000L)
        val second = observation(0.60f).copy(quantity = 850_000L)

        assertEquals(0, tracker.update(listOf(first), 1000L).size)
        val stable = tracker.update(listOf(second), 1100L).single()

        assertEquals(850_000L, stable.quantity)
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

    @Test
    fun ambiguousSemanticCoordinateDriftDoesNotReuseTemporalTrack() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val first = observation().copy(
            coordinate = WorldCoordinate(355, 10, 20),
            screenPoint = ScreenPoint(100f, 100f),
            label = "WOOD"
        )
        val second = first.copy(
            coordinate = WorldCoordinate(355, 11, 21),
            screenPoint = ScreenPoint(200f, 100f),
            label = "WOOD"
        )
        val ambiguous = first.copy(
            coordinate = WorldCoordinate(355, 12, 22),
            screenPoint = ScreenPoint(145f, 100f),
            label = "WOOD"
        )

        assertEquals(0, tracker.update(listOf(first, second), 1000L).size)
        assertEquals(0, tracker.update(listOf(ambiguous), 1100L).size)
        assertEquals(1, tracker.update(listOf(ambiguous), 1200L).size)
    }

    @Test
    fun coordinateDriftStillConfirmsSameVisibleNode() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val first = observation()
        val second = first.copy(
            coordinate = WorldCoordinate(355, 11, 21),
            screenPoint = ScreenPoint(104f, 97f)
        )

        assertEquals(0, tracker.update(listOf(first), 1000L).size)
        val stable = tracker.update(listOf(second), 1100L).single()

        assertEquals(second.coordinate, stable.coordinate)
        assertEquals(second.screenPoint, stable.screenPoint)
    }

    @Test
    fun semanticMismatchCannotReusePreviousFreeOrOccupiedEvidence() {
        val tracker = TemporalObservationTracker(confirmHits = 2)
        val wood = observation().copy(label = "WOOD", occupied = false, incomingTroops = false)
        val stoneOccupied = observation().copy(label = "STONE", occupied = true, incomingTroops = true)

        assertEquals(0, tracker.update(listOf(wood), 1000L).size)
        assertEquals(1, tracker.update(listOf(stoneOccupied), 1100L).size)

        val freeStone = stoneOccupied.copy(occupied = false, incomingTroops = false)
        assertEquals(true, tracker.update(listOf(freeStone), 1200L).single().occupied)
    }
}
