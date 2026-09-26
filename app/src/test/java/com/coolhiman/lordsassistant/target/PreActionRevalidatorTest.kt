package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreActionRevalidatorTest {
    private val coordinate = WorldCoordinate(355, 167, 511)
    private val point = ScreenPoint(500f, 400f)

    private fun observation() = MapObservation(
        coordinate = coordinate,
        screenPoint = point,
        label = "WOOD",
        level = 3,
        quantity = 720000,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = 0.95f,
        coordinateConfidence = CoordinateConfidence.observed(true, true, 2.0),
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
    )

    private fun validation() = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )

    private fun action() = ActionButton(
        kind = ActionKind.GATHER,
        bounds = android.graphics.RectF(450f, 350f, 550f, 450f),
        point = point,
        confidence = 0.95f
    )

    private fun snapshot() = ActionTargetSnapshot(
        coordinate = coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = point,
        semanticIdentity = "WOOD"
    )

    @Test
    fun unchangedTargetRemainsSafe() {
        val result = PreActionRevalidator.revalidate(
            snapshot(),
            observation(),
            validation(),
            action()
        )

        assertTrue(result.safe)
        assertTrue(result.stage == TargetValidationStage.SAFE_TO_INTERACT)
    }

    @Test
    fun changedTargetIsBlocked() {
        val latest = observation().copy(coordinate = WorldCoordinate(355, 168, 511))

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.TARGET_CHANGED in result.reasons)
    }

    @Test
    fun changedSemanticIdentityIsBlocked() {
        val latest = observation().copy(label = "STONE")

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.TARGET_CHANGED in result.reasons)
    }

    @Test
    fun matchingSemanticIdentityIsCaseInsensitive() {
        val latest = observation().copy(label = "wood")

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertTrue(result.safe)
    }

    @Test
    fun occupiedLatestObservationBlocksEvenIfValidationClaimsSafe() {
        val latest = observation().copy(occupied = true)

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.OCCUPIED in result.reasons)
    }

    @Test
    fun incomingLatestObservationBlocksEvenIfValidationClaimsSafe() {
        val latest = observation().copy(incomingTroops = true)

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.INCOMING_TROOPS in result.reasons)
    }

    @Test
    fun unknownLatestStateBlocksEvenIfValidationClaimsSafe() {
        val latest = observation().copy(occupied = null, incomingTroops = null)

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.STATE_UNKNOWN in result.reasons)
    }

    @Test
    fun nonAuthoritativeLatestCoordinateBlocksEvenIfValidationClaimsSafe() {
        val latest = observation().copy(
            coordinateConfidence = CoordinateConfidence.calibrated(true, true, 1.0)
        )

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.COORDINATE_AUTHORITY_INVALID in result.reasons)
    }

    @Test
    fun missingTemporalConfirmationBlocksEvenIfValidationClaimsSafe() {
        val latest = observation().copy(evidence = emptySet())

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.STATE_UNKNOWN in result.reasons)
    }

    @Test
    fun movedObservationPointIsBlockedEvenIfActionPointIsUnchanged() {
        val latest = observation().copy(screenPoint = ScreenPoint(560f, 400f))

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            latest,
            validation(),
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.INTERACTION_POINT_INVALID in result.reasons)
    }

    @Test
    fun changedActionIsBlocked() {
        val latestAction = action().copy(kind = ActionKind.HUNT)

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            observation(),
            validation(),
            latestAction
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.ACTION_MISMATCH in result.reasons)
    }

    @Test
    fun movedInteractionPointIsBlocked() {
        val latestAction = action().copy(point = ScreenPoint(560f, 400f))

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            observation(),
            validation(),
            latestAction
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.INTERACTION_POINT_INVALID in result.reasons)
    }

    @Test
    fun latestValidationFailureAlwaysBlocks() {
        val latestValidation = TargetValidationResult(
            safe = false,
            stage = TargetValidationStage.POPUP_CONFIRMED,
            reasons = setOf(TargetBlockReason.OCCUPIED)
        )

        val result = PreActionRevalidator.revalidate(
            snapshot(),
            observation(),
            latestValidation,
            action()
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.OCCUPIED in result.reasons)
    }
}
