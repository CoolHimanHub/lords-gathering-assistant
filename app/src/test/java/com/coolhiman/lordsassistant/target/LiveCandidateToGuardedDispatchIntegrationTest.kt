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
            latestObservation = observation(target),
            latestValidation = safeValidation(),
            latestAction = action(target.point)
        )
        assertEquals(ActionLifecycleState.REVALIDATED, orchestrator.lifecycleSnapshot.state)

        var dispatches = 0
        val result = orchestrator.dispatch(10_001L) {
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
        val result = orchestrator.dispatch(20_001L) {
            dispatches += 1
            true
        }

        assertEquals(ActionLifecycleState.FAILED, result.lifecycle.state)
        assertEquals(0, dispatches)
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
        orchestrator.dispatch(30_001L) {
            dispatches += 1
            true
        }

        assertFalse(orchestrator.lifecycleSnapshot.state == ActionLifecycleState.WAITING_FOR_RESULT)
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

    private fun observation(target: ActionTargetSnapshot) =
        MapObservation(
            coordinate = target.coordinate,
            kind = target.kind,
            level = target.level,
            screenPoint = target.point,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false,
            label = "WOOD",
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
