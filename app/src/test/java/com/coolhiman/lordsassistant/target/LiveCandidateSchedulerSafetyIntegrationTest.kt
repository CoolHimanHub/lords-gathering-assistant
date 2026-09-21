package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.10 scheduler handoff coverage.
 *
 * Verifies that the live-to-scheduler boundary preserves cooldown, in-flight,
 * and completed-target suppression before another candidate can be claimed.
 */
class LiveCandidateSchedulerSafetyIntegrationTest {

    @Test
    fun cooldownAndInFlightBlockSelectionWithoutDiscardingQueuedCandidate() {
        val scheduler = ActionScheduler()
        val candidate = schedule(target())

        scheduler.refresh(listOf(candidate))
        scheduler.markDispatchStarted(10_000L)

        val inFlight = scheduler.peek(10_001L, safeState())
        assertNull(inFlight.candidate)
        assertEquals(ActionScheduleBlockReason.ACTION_IN_FLIGHT, inFlight.reason)

        scheduler.markActionFinished()

        val cooldown = scheduler.peek(10_100L)
        assertNull(cooldown.candidate)
        assertEquals(ActionScheduleBlockReason.COOLDOWN_ACTIVE, cooldown.reason)
        assertEquals(1, scheduler.queuedCount())

        val ready = scheduler.peek(11_500L)
        assertEquals(target(), ready.candidate?.target)
    }


    @Test
    fun liveAdapterMirrorsDispatchLifecycleToScheduler() {
        val scheduler = ActionScheduler()
        val adapter = LiveActionSchedulerAdapter(scheduler)
        val candidate = schedule(target())

        adapter.update(listOf(candidate))
        adapter.markDispatchStarted(30_000L)

        val blocked = adapter.select(30_001L, safeState())
        assertNull(blocked.candidate)
        assertEquals(ActionScheduleBlockReason.ACTION_IN_FLIGHT, blocked.reason)

        adapter.markActionFinished()

        val cooldown = adapter.select(30_100L, safeState())
        assertNull(cooldown.candidate)
        assertEquals(ActionScheduleBlockReason.COOLDOWN_ACTIVE, cooldown.reason)

        assertEquals(target(), adapter.select(31_500L, safeState()).candidate?.target)
    }

    @Test
    fun completedTargetSuppressionDoesNotSuppressIndependentTarget() {
        val scheduler = ActionScheduler()
        val completed = target()
        val independent = target(coordinate = WorldCoordinate(355, 168, 511), point = ScreenPoint(620f, 400f))

        scheduler.refresh(listOf(schedule(completed), schedule(independent)))
        scheduler.markTargetCompleted(completed, 20_000L)

        val decision = scheduler.peek(20_001L)
        assertEquals(independent, decision.candidate?.target)
        assertTrue(scheduler.queuedCount() >= 1)

        scheduler.refresh(listOf(schedule(completed), schedule(independent)))
        val afterRefresh = scheduler.peek(20_002L)
        assertEquals(independent, afterRefresh.candidate?.target)
    }

    private fun schedule(target: ActionTargetSnapshot) = ActionScheduleCandidate(
        target = target,
        priority = 0,
        stabilityFrames = 3,
        validationSafe = true,
        queuedAtMs = 9_000L,
        plannerRank = 0,
        plannerScore = 100.0
    )

    private fun target(
        coordinate: WorldCoordinate = WorldCoordinate(355, 167, 511),
        point: ScreenPoint = ScreenPoint(500f, 400f)
    ) =
        ActionTargetSnapshot(
            coordinate = coordinate,
            kind = TargetKind.RESOURCE,
            level = 3,
            actionKind = ActionKind.GATHER,
            point = point
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
