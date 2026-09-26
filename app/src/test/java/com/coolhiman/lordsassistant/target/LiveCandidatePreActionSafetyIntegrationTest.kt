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
 * V0.10 end-to-end safety boundary:
 * live candidate -> scheduler selection -> latest-frame revalidation.
 *
 * The test deliberately stops before gesture dispatch. A candidate that mutates
 * after selection must fail closed even though it was originally scheduler-safe.
 */
class LiveCandidatePreActionSafetyIntegrationTest {

    @Test
    fun selectedCandidateWithMovedInteractionPointIsRejectedBeforeDispatchBoundary() {
        val selected = snapshot(point = ScreenPoint(500f, 400f))
        val candidate = scheduleCandidate(selected)

        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(candidate))

        val decision = scheduler.claim(10_000L, safeState())
        assertEquals(selected, decision.candidate?.target)

        val latestAction = action(point = ScreenPoint(560f, 400f))
        val result = PreActionRevalidator.revalidate(
            selected = decision.candidate?.target,
            latestObservation = observation(selected),
            latestValidation = safeValidation(),
            latestAction = latestAction
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.INTERACTION_POINT_INVALID in result.reasons)
    }

    @Test
    fun selectedCandidateWithChangedWorldIdentityIsRejectedBeforeDispatchBoundary() {
        val selected = snapshot(point = ScreenPoint(500f, 400f))
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(selected)))

        val decision = scheduler.claim(10_000L, safeState())
        assertEquals(selected, decision.candidate?.target)

        val latestObservation = observation(selected).copy(
            coordinate = WorldCoordinate(355, 168, 511)
        )

        val result = PreActionRevalidator.revalidate(
            selected = decision.candidate?.target,
            latestObservation = latestObservation,
            latestValidation = safeValidation(),
            latestAction = action(selected.point)
        )

        assertFalse(result.safe)
        assertTrue(TargetBlockReason.TARGET_CHANGED in result.reasons)
    }

    @Test
    fun failedRevalidationDoesNotRemoveIndependentQueuedTarget() {
        val first = snapshot(point = ScreenPoint(500f, 400f), x = 355)
        val second = snapshot(point = ScreenPoint(700f, 400f), x = 360)

        val scheduler = ActionScheduler()
        scheduler.refresh(
            listOf(
                scheduleCandidate(first, rank = 0),
                scheduleCandidate(second, rank = 1)
            )
        )

        val decision = scheduler.claim(10_000L, safeState())
        assertEquals(first, decision.candidate?.target)

        val failed = PreActionRevalidator.revalidate(
            selected = decision.candidate?.target,
            latestObservation = observation(first).copy(
                coordinate = WorldCoordinate(355, 169, 511)
            ),
            latestValidation = safeValidation(),
            latestAction = action(first.point)
        )

        assertFalse(failed.safe)
        assertEquals(1, scheduler.queuedCount())
        assertEquals(second, scheduler.peek(10_000L, safeState()).candidate?.target)
    }

    @Test
    fun restartQuarantinePreventsSelectionEvenWhenLiveCandidateIsEligible() {
        val target = snapshot(point = ScreenPoint(500f, 400f))
        val scheduler = ActionScheduler()
        scheduler.refresh(listOf(scheduleCandidate(target)))

        val quarantined = safeState().copy(restartQuarantine = true)
        val decision = scheduler.claim(10_000L, quarantined)

        assertEquals(null, decision.candidate)
        assertEquals(ActionScheduleBlockReason.SAFETY_BLOCKED, decision.reason)
        assertEquals(1, scheduler.queuedCount())
    }

    private fun scheduleCandidate(
        target: ActionTargetSnapshot,
        rank: Int = 0
    ) = ActionScheduleCandidate(
        target = target,
        priority = 0,
        stabilityFrames = 3,
        validationSafe = true,
        queuedAtMs = 9_000L,
        plannerRank = rank,
        plannerScore = 100.0 - rank
    )

    private fun observation(target: ActionTargetSnapshot) = MapObservation(
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

    private fun safeValidation() = TargetValidationResult(
        safe = true,
        stage = TargetValidationStage.SAFE_TO_INTERACT,
        validatedAtMs = System.currentTimeMillis()
    )

    private fun action(point: ScreenPoint) = ActionButton(
        kind = ActionKind.GATHER,
        bounds = RectF(point.x - 20f, point.y - 20f, point.x + 20f, point.y + 20f),
        point = point,
        confidence = 0.95f
    )

    private fun snapshot(
        point: ScreenPoint,
        x: Int = 355
    ) = ActionTargetSnapshot(
        coordinate = WorldCoordinate(x, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = point,
        semanticIdentity = "WOOD"
    )

    private fun safeState() = ActionSchedulerSafetyState(
        lifecycle = ActionLifecycleSnapshot(
            state = ActionLifecycleState.IDLE,
            failure = null
        ),
        automaticActionsEnabled = true,
        restartQuarantine = false,
        recoveryEpochPersistenceHealthy = true
    )
}
