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

/**
 * V0.5 safety-simulation matrix.
 *
 * These scenarios deliberately exercise the action core without Android,
 * Accessibility, or a real game connection. The invariant is:
 *
 * uncertain/stale evidence must never create a new automatic gesture.
 */
class ActionSafetySimulationTest {
    private val selected = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(900f, 600f),
        semanticIdentity = "WOOD"
    )

    private val observation = MapObservation(
        coordinate = selected.coordinate,
        kind = TargetKind.RESOURCE,
        level = 3,
        screenPoint = selected.point,
        confidence = 0.95f,
        occupied = false,
        incomingTroops = false,
        label = "WOOD",
        coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 4.0),
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
    )

    private val changedObservation = observation.copy(
        coordinate = WorldCoordinate(355, 168, 511)
    )

    private val popup = PopupState(
        kind = TargetKind.RESOURCE,
        resource = ResourceType.WOOD,
        level = 3,
        quantity = 720000L,
        occupied = false,
        incomingTroops = false,
        coordinate = selected.coordinate,
        isPopup = true
    )

    private val safeValidation = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT
    )

    private fun freshObservation() = observation.copy(timestampMs = System.currentTimeMillis())

    private fun freshValidation() = safeValidation.copy(validatedAtMs = System.currentTimeMillis())

    @Test
    fun validFlowDispatchesExactlyOnceAndRequiresVerifiedEvidence() {
        val orchestrator = ActionOrchestrator()
        var dispatches = 0

        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 1_000L)
        orchestrator.revalidate(
            freshObservation(),
            freshValidation(),
            ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        orchestrator.dispatch(1_001L) {
            dispatches++
            true
        }

        orchestrator.observeMarch(listOf(MarchSignal(910f, 600f, 20.0, 0.9f)), 1_100L)
        orchestrator.observeMarch(listOf(MarchSignal(920f, 600f, 20.0, 0.9f)), 1_200L)
        orchestrator.verifyPostAction(observation, popup, 1_300L)
        val result = orchestrator.verifyPostAction(observation, popup, 1_400L)

        assertEquals(1, dispatches)
        assertEquals(ActionLifecycleState.SUCCEEDED, result.lifecycle.state)
    }

    @Test
    fun targetChangeBeforeDispatchBlocksGesture() {
        val orchestrator = ActionOrchestrator()
        var dispatches = 0

        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 2_000L)
        val revalidation = orchestrator.revalidate(
            changedObservation.copy(timestampMs = System.currentTimeMillis()),
            freshValidation(),
            ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        orchestrator.dispatch(2_001L) {
            dispatches++
            true
        }

        assertEquals(ActionLifecycleState.FAILED, revalidation.lifecycle.state)
        assertEquals(ActionLifecycleFailure.REVALIDATION_FAILED, revalidation.lifecycle.failure)
        assertEquals(0, dispatches)
    }

    @Test
    fun popupDisappearanceWithoutMarchCannotBecomeSuccessOrRetry() {
        val orchestrator = ActionOrchestrator()
        var dispatches = 0

        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 3_000L)
        orchestrator.revalidate(
            observation,
            safeValidation,
            ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        orchestrator.dispatch(3_001L) {
            dispatches++
            true
        }

        val waiting = orchestrator.verifyPostAction(observation, null, 3_100L)
        val timeout = orchestrator.verifyPostAction(
            observation,
            null,
            3_001L + ActionOrchestrator.POST_ACTION_TIMEOUT_MS
        )
        val retry = orchestrator.request(
            true, selected, safeValidation, observation, popup, emptyList(), 7_100L
        )

        assertEquals(1, dispatches)
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, waiting.lifecycle.state)
        assertEquals(ActionLifecycleState.UNKNOWN, timeout.lifecycle.state)
        assertEquals(ActionLifecycleFailure.VERIFICATION_TIMEOUT, timeout.lifecycle.failure)
        assertEquals(ActionLifecycleState.UNKNOWN, retry.lifecycle.state)
        assertTrue(retry.session == null)
    }

    @Test
    fun unrelatedMarchEvidenceCannotConfirmOwnership() {
        val orchestrator = ActionOrchestrator()
        var dispatches = 0

        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 4_000L)
        orchestrator.revalidate(
            observation,
            safeValidation,
            ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        orchestrator.dispatch(4_001L) {
            dispatches++
            true
        }

        // The action point is deliberately far from the observed trajectory.
        orchestrator.observeMarch(listOf(MarchSignal(1_300f, 1_100f, 20.0, 0.9f)), 4_100L)
        orchestrator.observeMarch(listOf(MarchSignal(1_320f, 1_100f, 20.0, 0.9f)), 4_200L)
        val result = orchestrator.verifyPostAction(observation, popup, 4_300L)

        assertEquals(1, dispatches)
        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
        assertFalse(result.session?.ownMarchConfirmed == true)
    }

    @Test
    fun cameraInstabilityCannotTurnMarchEvidenceIntoSuccess() {
        val orchestrator = ActionOrchestrator()
        orchestrator.request(true, selected, safeValidation, observation, popup, emptyList(), 5_000L)
        orchestrator.revalidate(
            observation,
            safeValidation,
            ActionButton(ActionKind.GATHER, selected.point, 0.95f)
        )
        orchestrator.dispatch(5_001L) { true }

        orchestrator.observeMarch(listOf(MarchSignal(910f, 600f, 20.0, 0.9f)), 5_100L)
        orchestrator.observeMarch(listOf(MarchSignal(920f, 600f, 20.0, 0.9f)), 5_200L)
        val unstableAfter = freshObservation().copy(
            evidence = setOf(
                ObservationEvidence.TEMPORALLY_CONFIRMED,
                ObservationEvidence.CAMERA_UNSTABLE
            )
        )
        val result = orchestrator.verifyPostAction(unstableAfter, popup, 5_300L)

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
        assertTrue(result.session?.ownMarchConfirmed == true)
        assertEquals(null, orchestrator.lastPostActionEvidence)
    }

    @Test
    fun restartQuarantineCannotBeBypassedByDirectRequest() {
        val orchestrator = ActionOrchestrator()
        orchestrator.restoreUnknown(11L)

        var dispatches = 0
        val result = orchestrator.request(
            true, selected, safeValidation, observation, popup, emptyList(), 6_000L
        )
        orchestrator.dispatch(6_001L) {
            dispatches++
            true
        }

        assertEquals(ActionLifecycleState.UNKNOWN, result.lifecycle.state)
        assertTrue(result.session == null)
        assertEquals(0, dispatches)
    }

    @Test
    fun durableAttemptAllocatorFailureCreatesNoDispatchableSession() {
        val orchestrator = ActionOrchestrator(attemptIdAllocator = { null })
        var dispatches = 0

        val result = orchestrator.request(
            true, selected, safeValidation, observation, popup, emptyList(), 7_000L
        )
        orchestrator.dispatch(7_001L) {
            dispatches++
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(ActionLifecycleFailure.ATTEMPT_ID_PERSISTENCE_FAILED, result.lifecycle.failure)
        assertTrue(result.session == null)
        assertEquals(0, dispatches)
    }

    @Test
    fun recoveryEpochExhaustionCannotReopenAutomaticExecution() {
        val orchestrator = ActionOrchestrator(initialRecoveryEpoch = Long.MAX_VALUE)
        val reset = orchestrator.reset()

        var dispatches = 0
        val retry = orchestrator.request(
            true, selected, safeValidation, observation, popup, emptyList(), 8_000L
        )
        orchestrator.dispatch(8_001L) {
            dispatches++
            true
        }

        assertEquals(ActionLifecycleState.FAILED, reset.lifecycle.state)
        assertEquals(ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED, reset.lifecycle.failure)
        assertEquals(ActionLifecycleState.FAILED, retry.lifecycle.state)
        assertEquals(ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED, retry.lifecycle.failure)
        assertTrue(retry.session == null)
        assertEquals(0, dispatches)
    }
}
