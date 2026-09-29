package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.MarchSignal
import com.coolhiman.lordsassistant.vision.PopupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostActionEvidenceRecordTest {
    private val selected = ActionTargetSnapshot(
        WorldCoordinate(355, 167, 511),
        TargetKind.RESOURCE,
        3,
        ActionKind.GATHER,
        ScreenPoint(900f, 600f),
        semanticIdentity = "WOOD"
    )

    @Test
    fun successfulOwnMarchEvidenceRecordsItsSourceAndTargetIdentity() {
        val observation = MapObservation(
            coordinate = selected.coordinate,
            kind = selected.kind,
            level = selected.level,
            screenPoint = selected.point,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false,
            label = "WOOD",
            coordinateConfidence = com.coolhiman.lordsassistant.model.CoordinateConfidence.observed(true, true, residualPx = 2.0),
            evidence = setOf(com.coolhiman.lordsassistant.model.ObservationEvidence.TEMPORALLY_CONFIRMED),
            timestampMs = 10_000L
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = ResourceType.WOOD,
            level = 3,
            quantity = 720000L,
            occupied = false,
            incomingTroops = false,
            coordinate = selected.coordinate,
            isPopup = true
        )

        val orchestrator = ActionOrchestrator()
        val baseMs = System.currentTimeMillis()
        val currentObservation = observation.copy(timestampMs = baseMs)
        val validation = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT, validatedAtMs = baseMs)
        orchestrator.request(true, selected, validation, currentObservation, popup, emptyList(), baseMs, captureSessionId = 601L)
        orchestrator.revalidate(currentObservation, validation, ActionButton(ActionKind.GATHER, selected.point, 0.95f), nowMs = baseMs, captureSessionId = 601L)
        orchestrator.dispatch(baseMs + 1L, captureSessionId = 601L, automaticActionsEnabled = true) { _ -> true }
        orchestrator.observeMarch(listOf(MarchSignal(910f, 600f, 20.0, 0.9f)), baseMs + 100L)
        orchestrator.observeMarch(listOf(MarchSignal(920f, 600f, 20.0, 0.9f)), baseMs + 200L)

        orchestrator.verifyPostAction(currentObservation, popup, baseMs + 300L, captureSessionId = 601L)
        orchestrator.verifyPostAction(currentObservation.copy(timestampMs = baseMs + 350L), popup, baseMs + 400L, captureSessionId = 601L)

        val record = orchestrator.lastPostActionEvidence
        assertTrue(record != null)
        assertEquals(1L, record!!.attemptId)
        assertEquals(601L, record.captureSessionId)
        assertTrue(PostActionEvidence.OWN_MARCH_CONFIRMED in record.evidence)
        assertTrue(PostActionEvidenceSource.MARCH_ASSOCIATION in record.sources)
        assertEquals(selected, record.selected)
        assertEquals(2, record.confirmingFrames)
        assertEquals(2, record.marchTrajectory.size)
        assertEquals(920f, record.marchTrajectory.last().x, 0.01f)
        assertEquals(600f, record.marchTrajectory.last().y, 0.01f)
        assertEquals(selected.point, record.marchTrajectoryEvidence!!.actionPoint)
        assertEquals(2, record.marchTrajectoryEvidence.trajectory.size)
        assertEquals(10f, record.marchTrajectoryEvidence.displacementPx, 0.01f)
        assertEquals(1f, record.marchTrajectoryEvidence.directionX, 0.01f)
        assertEquals(0f, record.marchTrajectoryEvidence.directionY, 0.01f)
        assertEquals(2, record.marchTrajectoryEvidence.confirmingFrames)
        assertTrue(record.marchTrajectoryEvidence.cameraStable)

    }

    @Test
    fun duplicatePostActionFrameCannotSatisfyConfirmation() {
        val orchestrator = ActionOrchestrator()
        val baseMs = System.currentTimeMillis()
        val observation = MapObservation(
            coordinate = selected.coordinate,
            kind = selected.kind,
            level = selected.level,
            screenPoint = selected.point,
            confidence = 1f,
            occupied = true,
            incomingTroops = false,
            label = "WOOD",
            coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 1.0),
            evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED),
            timestampMs = baseMs
        )
        val validation = TargetValidationResult(true, TargetValidationStage.SAFE_TO_INTERACT, validatedAtMs = baseMs)
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = ResourceType.WOOD,
            level = 3,
            quantity = 100L,
            occupied = false,
            incomingTroops = false,
            coordinate = selected.coordinate,
            isPopup = true
        )
        orchestrator.request(true, selected, validation, observation, popup, emptyList(), baseMs)
        orchestrator.revalidate(observation, validation, ActionButton(ActionKind.GATHER, selected.point, 1f), baseMs)
        orchestrator.dispatch(baseMs + 1L, automaticActionsEnabled = true) { true }

        orchestrator.verifyPostAction(observation, popup, baseMs + 100L)
        val repeated = orchestrator.verifyPostAction(observation, popup, baseMs + 200L)

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, repeated.lifecycle.state)
        assertEquals(1, orchestrator.lastPostActionEvidence!!.confirmingFrames)
    }

    @Test
    fun changedSemanticIdentityCannotCountAsSamePostActionTarget() {
        val before = MapObservation(
            coordinate = selected.coordinate,
            kind = selected.kind,
            level = selected.level,
            screenPoint = selected.point,
            confidence = 1f,
            occupied = false,
            incomingTroops = false,
            label = "WOOD",
            coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 1.0),
            evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED),
            timestampMs = 1L
        )
        val afterDifferentResource = before.copy(
            label = "STONE",
            occupied = true,
            timestampMs = 2L
        )
        val evidence = PostActionStateVerifier.collectEvidence(
            selected = selected,
            before = before,
            after = afterDifferentResource,
            popupBefore = null,
            popupAfter = null
        )

        assertTrue(PostActionEvidence.TARGET_OCCUPIED !in evidence)
        assertTrue(PostActionEvidence.TARGET_REMOVED !in evidence)
        assertFalse(PostActionStateVerifier.isSameTarget(afterDifferentResource, selected))
    }
}

