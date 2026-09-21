package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.map.LiveActionCandidate
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * V0.10 integration boundary test:
 * Live scanner candidate -> reconciliation -> scheduler queue.
 *
 * This deliberately stops before ActionOrchestrator/gesture dispatch.
 */
class LiveCandidateToSchedulerIntegrationTest {

    @Test
    fun eligibleCandidateReachesSchedulerAndRejectedCandidateDoesNot() {
        val eligible = candidate(id = 1)
        val rejected = candidate(id = 2, cameraContinuityValid = false)
        val reconciliation = LiveActionCandidateReconciler.reconcile(
            listOf(eligible, rejected)
        )

        val scheduler = ActionScheduler()
        val adapter = LiveActionSchedulerAdapter(scheduler)
        adapter.update(
            reconciliation.eligible.map { it.toScheduleCandidate(queuedAtMs = 1_000L) }
        )

        val decision = adapter.select(
            nowMs = 10_000L,
            safetyState = safeState()
        )

        assertEquals(1, adapter.queuedCount())
        assertEquals(eligible.target, decision.candidate?.target)
        assertEquals(
            LiveActionCandidateRejectionReason.CAMERA_CONTINUITY_INVALID,
            reconciliation.rejected[rejected]
        )
    }

    @Test
    fun latestFrameRemovalCannotLeaveOldCandidateActionable() {
        val firstFrame = candidate(id = 1)
        val secondFrame = candidate(id = 2)

        val scheduler = ActionScheduler()
        val adapter = LiveActionSchedulerAdapter(scheduler)

        adapter.update(
            LiveActionCandidateReconciler.reconcile(listOf(firstFrame))
                .eligible
                .map { it.toScheduleCandidate(1_000L) }
        )
        adapter.update(
            LiveActionCandidateReconciler.reconcile(listOf(secondFrame))
                .eligible
                .map { it.toScheduleCandidate(2_000L) }
        )

        val decision = adapter.select(
            nowMs = 10_000L,
            safetyState = safeState()
        )

        assertEquals(1, adapter.queuedCount())
        assertEquals(secondFrame.target, decision.candidate?.target)
        assertNull(
            scheduler.peek(10_000L, safeState()).candidate
                ?.takeIf { it.target == firstFrame.target }
        )
    }

    private fun LiveActionCandidate.toScheduleCandidate(queuedAtMs: Long) =
        ActionScheduleCandidate(
            target = target,
            priority = 0,
            stabilityFrames = stability.consecutiveFrames,
            validationSafe = validation.safe,
            queuedAtMs = queuedAtMs,
            plannerRank = plannerRank,
            plannerScore = plannerScore
        )

    private fun candidate(
        id: Int,
        cameraContinuityValid: Boolean = true
    ): LiveActionCandidate {
        val coordinate = WorldCoordinate(355, 160 + id, 500 + id)
        val point = ScreenPoint(900f + id, 600f)
        val observation = MapObservation(
            coordinate = coordinate,
            kind = TargetKind.RESOURCE,
            level = 3,
            screenPoint = point,
            confidence = 0.95f,
            occupied = false,
            incomingTroops = false
        )
        return LiveActionCandidate(
            target = ActionTargetSnapshot(
                coordinate = coordinate,
                kind = TargetKind.RESOURCE,
                level = 3,
                actionKind = ActionKind.GATHER,
                point = point
            ),
            observation = observation,
            actionButton = ActionButton(
                kind = ActionKind.GATHER,
                bounds = RectF(890f, 588f, 910f, 612f),
                point = point,
                confidence = 0.95f
            ),
            validation = TargetValidationResult(
                safe = true,
                stage = TargetValidationStage.SAFE_TO_INTERACT
            ),
            stability = TargetStability(stable = true, consecutiveFrames = 3),
            plannerRank = id - 1,
            plannerScore = 100.0 - id,
            cameraContinuityValid = cameraContinuityValid
        )
    }

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
