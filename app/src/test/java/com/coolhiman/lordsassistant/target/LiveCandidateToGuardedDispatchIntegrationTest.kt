package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.10 guarded pipeline coverage:
 * reconciled live candidate -> scheduler claim -> ActionOrchestrator
 * revalidation -> guarded dispatch boundary.
 *
 * No test in this class performs a real gesture; the dispatch callback is
 * merely an observable side-effect boundary.
 */
class LiveCandidateToGuardedDispatchIntegrationTest {

    @Test
    fun eligibleCandidateCanReachDispatchOnlyAfterFreshRevalidation() {
        val target = target()
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(target)))

        val decision = scheduler.claim(10_000L, safeState())
        assertEquals(target, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = decision.candidate?.target,
            validation = safeValidation(),
            beforeObservation = observation(target),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 10_000L
        )

        orchestrator.revalidate(
            latestObservation = observation(target).copy(timestampMs = 10_000L),
            latestValidation = safeValidation().copy(validatedAtMs = 10_000L),
            latestAction = action(target.point),
            nowMs = 10_000L
        )
        assertEquals(ActionLifecycleState.REVALIDATED, orchestrator.lifecycleSnapshot.state)

        var dispatches = 0
        val result = orchestrator.dispatch(10_001L, automaticActionsEnabled = true) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
        assertEquals(1, dispatches)
    }

    @Test
    fun mutationAfterSchedulerClaimFailsClosedAndNeverInvokesDispatch() {
        val target = target()
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(target)))

        val decision = scheduler.claim(20_000L, safeState())
        assertEquals(target, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = decision.candidate?.target,
            validation = safeValidation(),
            beforeObservation = observation(target),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 20_000L
        )

        val movedPoint = ScreenPoint(560f, 400f)
        val revalidation = orchestrator.revalidate(
            latestObservation = observation(target),
            latestValidation = safeValidation(),
            latestAction = action(movedPoint)
        )
        assertEquals(ActionLifecycleState.FAILED, revalidation.lifecycle.state)
        assertEquals(ActionLifecycleFailure.REVALIDATION_FAILED, revalidation.lifecycle.failure)

        var dispatches = 0
        val result = orchestrator.dispatch(20_001L, automaticActionsEnabled = true) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(0, dispatches)
    }

    @Test
    fun semanticTargetSwapAfterSchedulerClaimFailsClosedAndNeverInvokesDispatch() {
        val target = target()
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(target)))

        val decision = scheduler.claim(25_000L, safeState())
        assertEquals(target, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = decision.candidate?.target,
            validation = safeValidation(),
            beforeObservation = observation(target),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 25_000L
        )

        // Same world coordinate/kind/level/action, but the semantic target
        // changed. The scheduler identity includes semantics and the final
        // revalidator must reject this as a different target.
        val swapped = target.copy(semanticIdentity = "STONE")
        val revalidation = orchestrator.revalidate(
            latestObservation = observation(swapped).copy(timestampMs = 25_000L),
            latestValidation = safeValidation().copy(validatedAtMs = 25_000L),
            latestAction = action(swapped.point),
            nowMs = 25_000L
        )

        assertEquals(ActionLifecycleState.FAILED, revalidation.lifecycle.state)
        assertEquals(ActionLifecycleFailure.REVALIDATION_FAILED, revalidation.lifecycle.failure)

        var dispatches = 0
        val result = orchestrator.dispatch(25_001L, automaticActionsEnabled = true) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(0, dispatches)
    }

    @Test
    fun captureSessionChangeAfterSchedulerClaimInvalidatesDispatch() {
        val target = target()
        val scheduler = ActionScheduler()
        scheduler.resetForCaptureSession(41L)
        scheduler.refresh(listOf(scheduleCandidate(target).copy(captureSessionId = 41L)))

        val decision = scheduler.claim(
            40_000L,
            safeState().copy(captureSessionId = 41L)
        )
        assertEquals(target, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = decision.candidate?.target,
            validation = safeValidationAt(40_000L),
            beforeObservation = observationAt(target, 40_000L),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 40_000L,
            captureSessionId = 41L
        )

        val revalidation = orchestrator.revalidate(
            latestObservation = observationAt(target, 40_000L),
            latestValidation = safeValidationAt(40_000L),
            latestAction = action(target.point),
            nowMs = 40_000L,
            captureSessionId = 42L
        )

        assertEquals(ActionLifecycleState.UNKNOWN, revalidation.lifecycle.state)
        assertEquals(ActionLifecycleFailure.CAPTURE_SESSION_CHANGED, revalidation.lifecycle.failure)

        var dispatches = 0
        val result = orchestrator.dispatch(
            nowMs = 40_001L,
            captureSessionId = 42L,
            automaticActionsEnabled = true
        ) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.UNKNOWN, result.lifecycle.state)
        assertEquals(0, dispatches)
    }

    @Test
    fun activeCaptureSessionRejectsUnboundActionRequest() {
        val target = target()
        val orchestrator = ActionOrchestrator()
        val started = orchestrator.beginCaptureSession(51L)
        assertEquals(ActionLifecycleState.IDLE, started.lifecycle.state)

        val result = orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(51_000L),
            beforeObservation = observationAt(target, 51_000L),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 51_000L,
            captureSessionId = null
        )

        assertEquals(ActionLifecycleState.UNKNOWN, result.lifecycle.state)
        assertEquals(ActionLifecycleFailure.CAPTURE_SESSION_CHANGED, result.lifecycle.failure)
        assertEquals(null, result.session)
    }

    @Test
    fun activeCaptureSessionRejectsActionRequestFromDifferentSession() {
        val target = target()
        val orchestrator = ActionOrchestrator()
        orchestrator.beginCaptureSession(52L)

        val result = orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(52_000L),
            beforeObservation = observationAt(target, 52_000L),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 52_000L,
            captureSessionId = 53L
        )

        assertEquals(ActionLifecycleState.UNKNOWN, result.lifecycle.state)
        assertEquals(ActionLifecycleFailure.CAPTURE_SESSION_CHANGED, result.lifecycle.failure)
        assertEquals(null, result.session)
    }

    @Test
    fun failedClaimedTargetDoesNotDiscardIndependentQueuedCandidate() {
        val first = target()
        val second = target(ScreenPoint(620f, 400f)).copy(
            coordinate = WorldCoordinate(356, 167, 511),
            semanticIdentity = "STONE"
        )
        val scheduler = ActionScheduler()
        scheduler.refresh(
            listOf(
                scheduleCandidate(first).copy(priority = 10, plannerRank = 0),
                scheduleCandidate(second).copy(priority = 5, plannerRank = 1)
            )
        )

        val decision = scheduler.claim(50_000L, safeState())
        assertEquals(first, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = decision.candidate?.target,
            validation = safeValidationAt(50_000L),
            beforeObservation = observationAt(first, 50_000L),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 50_000L
        )

        val failed = orchestrator.revalidate(
            latestObservation = observationAt(first, 50_000L),
            latestValidation = safeValidationAt(50_000L),
            latestAction = action(ScreenPoint(700f, 400f)),
            nowMs = 50_000L
        )
        assertEquals(ActionLifecycleState.FAILED, failed.lifecycle.state)
        assertEquals(ActionLifecycleFailure.REVALIDATION_FAILED, failed.lifecycle.failure)

        // The claimed target is consumed, but an independently queued target
        // must survive the failed revalidation of the first target.
        val remaining = scheduler.peek(50_001L, safeState()).candidate?.target
        assertEquals(second, remaining)
    }

    @Test
    fun disabledAutomationStopsAtOrchestratorBeforeDispatch() {
        val target = target()
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(target)))

        val decision = scheduler.claim(30_000L, safeState())
        assertEquals(target, decision.candidate?.target)

        val orchestrator = ActionOrchestrator()
        val request = orchestrator.request(
            automaticActionsEnabled = false,
            selected = decision.candidate?.target,
            validation = safeValidation(),
            beforeObservation = observation(target),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 30_000L
        )

        assertEquals(ActionLifecycleState.FAILED, request.lifecycle.state)

        var dispatches = 0
        orchestrator.dispatch(30_001L, automaticActionsEnabled = true) { _ ->
            dispatches += 1
            true
        }

        assertFalse(orchestrator.lifecycleSnapshot.state == ActionLifecycleState.WAITING_FOR_RESULT)
        assertEquals(0, dispatches)
    }

    @Test
    fun finalGestureReceivesExactContextThatPassedFinalGate() {
        val target = target()
        val now = System.currentTimeMillis()
        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(now),
            beforeObservation = observationAt(target, now),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = now
        )
        val latestObservation = observationAt(target, now)
        val latestValidation = safeValidationAt(now)
        val latestAction = action(target.point)
        orchestrator.revalidate(
            latestObservation = latestObservation,
            latestValidation = latestValidation,
            latestAction = latestAction,
            nowMs = now
        )

        var received: FinalDispatchContext? = null
        val result = orchestrator.dispatch(
            nowMs = now + 1L,
            automaticActionsEnabled = true
        ) { context ->
            received = context
            true
        }

        assertEquals(ActionLifecycleState.WAITING_FOR_RESULT, result.lifecycle.state)
        assertEquals(target, received?.selected)
        assertEquals(latestObservation, received?.latestObservation)
        val finalValidation = PreActionRevalidator.revalidate(
            selected = target,
            latestObservation = latestObservation,
            latestValidation = latestValidation,
            latestAction = latestAction,
            nowMs = now
        )
        assertEquals(finalValidation, received?.latestValidation)
        assertEquals(latestAction, received?.latestAction)
        assertEquals(now, received?.revalidatedAtMs)
        assertEquals(orchestrator.session?.attemptId, received?.attemptId)
        assertEquals(orchestrator.currentRecoveryEpoch, received?.recoveryEpoch)
    }

    @Test
    fun finalDispatchContextCannotBeForgedIntoAccessibilityBoundary() {
        val target = target()
        val now = System.currentTimeMillis()
        val orchestrator = ActionOrchestrator()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(now),
            beforeObservation = observationAt(target, now),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = now
        )
        orchestrator.revalidate(
            latestObservation = observationAt(target, now),
            latestValidation = safeValidationAt(now),
            latestAction = action(target.point),
            nowMs = now
        )

        var received: FinalDispatchContext? = null
        orchestrator.dispatch(now + 1L, automaticActionsEnabled = true) {
            received = it
            true
        }

        val context = received ?: error("dispatch context missing")
        assertTrue(context.isGuardedDispatch())
        assertFalse(context.copy(capability = Any()).isGuardedDispatch())
    }

    @Test
    fun automationDisabledAfterRevalidationBlocksFinalGesture() {
        val target = target()
        val orchestrator = ActionOrchestrator()
        val now = System.currentTimeMillis()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(now),
            beforeObservation = observationAt(target, now),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = now
        )
        orchestrator.revalidate(
            latestObservation = observationAt(target, now),
            latestValidation = safeValidationAt(now),
            latestAction = action(target.point),
            nowMs = now
        )

        var dispatches = 0
        val result = orchestrator.dispatch(
            nowMs = now + 1L,
            automaticActionsEnabled = false
        ) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(0, dispatches)
    }

    @Test
    fun evidenceThatExpiresAfterRevalidationIsRejectedAtGestureBoundary() {
        val target = target()
        val orchestrator = ActionOrchestrator()
        val now = System.currentTimeMillis()
        orchestrator.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safeValidationAt(now),
            beforeObservation = observationAt(target, now),
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = now
        )
        orchestrator.revalidate(
            latestObservation = observationAt(target, now),
            latestValidation = safeValidationAt(now),
            latestAction = action(target.point),
            nowMs = now
        )

        var dispatches = 0
        val result = orchestrator.dispatch(
            nowMs = now + 2_001L,
            automaticActionsEnabled = true
        ) { _ ->
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(ActionLifecycleFailure.DISPATCH_FAILED, result.lifecycle.failure)
        assertEquals(0, dispatches)
    }

    private fun scheduleCandidate(target: ActionTargetSnapshot) =
        ActionScheduleCandidate(
            target = target,
            priority = 0,
            stabilityFrames = 3,
            validationSafe = true,
            queuedAtMs = 9_000L,
            plannerRank = 0,
            plannerScore = 100.0
        )

    private fun target(point: ScreenPoint = ScreenPoint(500f, 400f)) =
        ActionTargetSnapshot(
            coordinate = WorldCoordinate(355, 167, 511),
            kind = TargetKind.RESOURCE,
            level = 3,
            actionKind = ActionKind.GATHER,
            point = point,
            semanticIdentity = "WOOD"
        )

    private fun observationAt(target: ActionTargetSnapshot, timestampMs: Long) =
        observation(target).copy(timestampMs = timestampMs)

    private fun observation(target: ActionTargetSnapshot) =
        MapObservation(
            coordinate = target.coordinate,
            kind = target.kind,
            level = target.level,
            screenPoint = target.point,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false,
            label = target.semanticIdentity,
            coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 4.0),
            evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED),
            timestampMs = System.currentTimeMillis()
        )

    private fun action(point: ScreenPoint) =
        ActionButton(
            kind = ActionKind.GATHER,
            bounds = RectF(point.x - 20f, point.y - 20f, point.x + 20f, point.y + 20f),
            point = point,
            confidence = 0.95f
        )

    private fun safeValidationAt(timestampMs: Long) =
        safeValidation().copy(validatedAtMs = timestampMs)

    private fun safeValidation() =
        TargetValidationResult(
            safe = true,
            stage = TargetValidationStage.SAFE_TO_INTERACT,
            validatedAtMs = System.currentTimeMillis()
        )

    private fun safeState() =
        ActionSchedulerSafetyState(
            lifecycle = ActionLifecycleSnapshot(
                state = ActionLifecycleState.IDLE,
                failure = null
            ),
            automaticActionsEnabled = true,
            restartQuarantine = false,
            recoveryEpochPersistenceHealthy = true
        )
}
