package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetValidationEngineTest {
    private val engine = TargetValidationEngine()
    private val coordinate = WorldCoordinate(355, 167, 511)

    private fun observation(occupied: Boolean? = false, incoming: Boolean? = false, evidence: Set<ObservationEvidence> = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)) =
        MapObservation(coordinate, ScreenPoint(100f, 200f), "WOOD", 3, 720000, occupied, incoming, TargetKind.RESOURCE, 0.95f, evidence)

    private fun popup() = PopupState(kind = TargetKind.RESOURCE, resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD, level = 3, quantity = 720000, occupied = false, incomingTroops = false, coordinate = coordinate, isPopup = true)

    @Test fun freeConfirmedMatchingPopupIsSafe() {
        val r = engine.validate(observation(), true, true, popup())
        assertTrue(r.safe)
        assertTrue(r.stage == TargetValidationStage.SAFE_TO_INTERACT)
    }

    @Test fun occupiedTargetIsBlocked() {
        val r = engine.validate(observation(true, true), true, true, popup())
        assertFalse(r.safe)
        assertTrue(TargetBlockReason.OCCUPIED in r.reasons)
        assertTrue(TargetBlockReason.INCOMING_TROOPS in r.reasons)
    }

    @Test fun unstableCameraBlocksEvenWithPopup() {
        val r = engine.validate(observation(), false, true, popup())
        assertFalse(r.safe)
        assertTrue(TargetBlockReason.CAMERA_UNSTABLE in r.reasons)
    }

    @Test fun popupMismatchBlocksInteraction() {
        val r = engine.validate(observation(), true, true, popup().copy(coordinate = WorldCoordinate(355, 168, 511)))
        assertFalse(r.safe)
        assertTrue(TargetBlockReason.POPUP_MISMATCH in r.reasons)
    }

    @Test fun unknownStateBlocksInteraction() {
        val r = engine.validate(observation(null, null), true, true, popup())
        assertFalse(r.safe)
        assertTrue(TargetBlockReason.STATE_UNKNOWN in r.reasons)
    }

    @Test fun missingTemporalConfirmationBlocksInteraction() {
        val r = engine.validate(observation(evidence = emptySet()), true, true, popup())
        assertFalse(r.safe)
        assertTrue(TargetBlockReason.STATE_UNKNOWN in r.reasons)
    }
}