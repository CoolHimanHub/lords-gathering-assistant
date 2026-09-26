package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import android.graphics.RectF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.6.8 cross-component safety regression.
 *
 * Models the durable restart boundary without Android services: a candidate
 * crosses the scheduler/lifecycle/dispatch chain, then a fresh orchestrator is
 * created from the last durable recovery epoch and must quarantine the
 * recovered attempt before any fresh automatic action is possible.
 */
class ActionRestartRecoveryIntegrationTest {

    private val target = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(500f, 400f),
        semanticIdentity = "WOOD"
    )

    private val safe = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT,
        validatedAtMs = 1000L
    )

    @Test
    fun dispatchThenRestartQuarantinesRecoveredAttemptUntilFreshRecoveryBoundary() {
        val scheduler = ActionScheduler(
            cooldownMs = 0L,
            minimumStabilityFrames = 2
        )
        val candidate = ActionScheduleCandidate(
            target = target,
            priority = 0,
            stabilityFrames = 2,
            validationSafe = true,
            queuedAtMs = 1000L,
            plannerRank = 0,
            plannerScore = 100.0
        )
        scheduler.refresh(listOf(candidate))

        val first = ActionOrchestrator(
            initialRecoveryEpoch = 7L,
            attemptIdAllocator = { previous -> previous + 1L }
        )
        val safeState = ActionSchedulerSafetyState(
            lifecycle = first.lifecycleSnapshot,
            automaticActionsEnabled = true,
            restartQuarantine = false,
            recoveryEpochPersistenceHealthy = true
        )

        val selected = scheduler.claim(1000L, safeState).candidate
        assertNotNull(selected)

        val requested = first.request(
            automaticActionsEnabled = true,
            selected = selected!!.target,
            validation = safe,
            beforeObservation = null,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 1000L
        )
        assertEquals(ActionLifecycleState.REQUESTED, requested.lifecycle.state)

        assertEquals(ActionLifecycleState.REVALIDATED, first.revalidate(
            latestObservation = MapObservation(
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
                timestampMs = 1000L
            ),
            latestValidation = safe,
            latestAction = ActionButton(
                kind = ActionKind.GATHER,
                bounds = RectF(490f, 390f, 510f, 410f),
                point = target.point,
                confidence = 0.95f
            )
        ).lifecycle.state)

        assertEquals(
            ActionLifecycleState.WAITING_FOR_RESULT,
            first.dispatch(1100L) { true }.lifecycle.state
        )

        val inFlightAttempt = first.session!!.attemptId
        val durableEpoch = first.currentRecoveryEpoch

        // Simulated process/service death: no live session is carried forward.
        val restarted = ActionOrchestrator(
            initialRecoveryEpoch = durableEpoch,
            attemptIdAllocator = { previous -> previous + 1L }
        )
        val quarantine = restarted.restoreUnknown(inFlightAttempt)

        assertEquals(ActionLifecycleState.UNKNOWN, quarantine.lifecycle.state)
        assertEquals(durableEpoch + 1L, restarted.currentRecoveryEpoch)
        assertTrue(!ActionRecoveryPolicy.mayStartAutomaticAttempt(quarantine.lifecycle))
        assertTrue(
            !ActionSchedulerSafetyGate().maySelect(
                ActionSchedulerSafetyState(
                    lifecycle = quarantine.lifecycle,
                    automaticActionsEnabled = true,
                    restartQuarantine = true,
                    recoveryEpochPersistenceHealthy = true
                )
            )
        )

        // Deliberate/manual recovery establishes another fresh provenance epoch.
        val recovered = restarted.reset()
        assertEquals(ActionLifecycleState.IDLE, recovered.lifecycle.state)
        assertEquals(durableEpoch + 2L, restarted.currentRecoveryEpoch)
        assertTrue(ActionRecoveryPolicy.mayStartAutomaticAttempt(recovered.lifecycle))
        assertTrue(
            ActionSchedulerSafetyGate().maySelect(
                ActionSchedulerSafetyState(
                    lifecycle = recovered.lifecycle,
                    automaticActionsEnabled = true,
                    restartQuarantine = false,
                    recoveryEpochPersistenceHealthy = true
                )
            )
        )

        // The recovered attempt identity must never be reused after the fresh
        // recovery boundary.
        val next = restarted.request(
            automaticActionsEnabled = true,
            selected = target,
            validation = safe,
            beforeObservation = null,
            popupBefore = null,
            baselineMarchSignals = emptyList(),
            nowMs = 2000L
        )
        assertEquals(ActionLifecycleState.REQUESTED, next.lifecycle.state)
        assertTrue(next.session!!.attemptId > inFlightAttempt)
        assertEquals(restarted.currentRecoveryEpoch, next.session!!.recoveryEpoch)
    }
}
