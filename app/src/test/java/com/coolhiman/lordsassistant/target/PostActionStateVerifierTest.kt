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

class PostActionStateVerifierTest {
    private val coordinate = WorldCoordinate(355, 167, 511)
    private val target = ActionTargetSnapshot(
        coordinate = coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(500f, 400f)
    )

    private fun observation(
        occupied: Boolean? = false,
        incoming: Boolean? = false
    ) = MapObservation(
        coordinate = coordinate,
        screenPoint = ScreenPoint(500f, 400f),
        label = "WOOD",
        level = 3,
        quantity = 720000,
        occupied = occupied,
        incomingTroops = incoming,
        kind = TargetKind.RESOURCE,
        confidence = 0.95f,
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
    )

    private fun popup(isPopup: Boolean) = PopupState(
        isPopup = isPopup,
        coordinate = coordinate,
        kind = TargetKind.RESOURCE,
        resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
        level = 3,
        quantity = 720000,
        occupied = false,
        incomingTroops = false
    )

    @Test
    fun popupDisappearanceIsDetected() {
        val evidence = PostActionStateVerifier.collectEvidence(
            target, observation(), observation(), popup(true), popup(false)
        )
        assertTrue(PostActionEvidence.POPUP_DISAPPEARED in evidence)
    }

    @Test
    fun occupiedTargetIsDetected() {
        val evidence = PostActionStateVerifier.collectEvidence(
            target, observation(), observation(occupied = true, incoming = true),
            popup(true), popup(true)
        )
        assertTrue(PostActionEvidence.TARGET_OCCUPIED in evidence)
    }

    @Test
    fun removedTargetIsDetected() {
        val other = observation().copy(
            coordinate = WorldCoordinate(355, 168, 511)
        )
        val evidence = PostActionStateVerifier.collectEvidence(
            target, observation(), other, popup(true), popup(false)
        )
        assertTrue(PostActionEvidence.TARGET_REMOVED in evidence)
    }

    @Test
    fun unrelatedAfterStateDoesNotBecomeOccupiedEvidence() {
        val other = observation(occupied = true).copy(
            coordinate = WorldCoordinate(355, 168, 511)
        )
        val evidence = PostActionStateVerifier.collectEvidence(
            target, observation(), other, popup(true), popup(true)
        )
        assertFalse(PostActionEvidence.TARGET_OCCUPIED in evidence)
    }

    @Test
    fun popupDisappearanceAloneDoesNotClaimSuccess() {
        val evidence = PostActionStateVerifier.collectEvidence(
            target, observation(), observation(), popup(true), popup(false)
        )
        assertTrue(PostActionEvidence.POPUP_DISAPPEARED in evidence)
        assertFalse(PostActionEvidence.TARGET_OCCUPIED in evidence)
        assertFalse(PostActionEvidence.TARGET_REMOVED in evidence)
    }
}
