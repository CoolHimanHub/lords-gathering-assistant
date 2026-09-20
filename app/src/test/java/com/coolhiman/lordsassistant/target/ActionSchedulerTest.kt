package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSchedulerTest {
    private fun target(x: Int) = ActionTargetSnapshot(
        coordinate = WorldCoordinate(x, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(900f + x, 600f)
    )

    @Test
    fun higherPrioritySafeStableCandidateIsSelected() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 20, 2, true, 1_100L))

        val decision = scheduler.peek(5_000L)

        assertEquals(target(2), decision.candidate?.target)
        assertEquals(null, decision.reason)
    }

    @Test
    fun unstableOrUnsafeCandidatesNeverEnterQueue() {
        val scheduler = ActionScheduler()

        scheduler.offer(ActionScheduleCandidate(target(1), 100, 1, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 100, 2, false, 1_100L))

        assertEquals(0, scheduler.queuedCount())
        assertEquals(ActionScheduleBlockReason.EMPTY_QUEUE, scheduler.peek(5_000L).reason)
    }

    @Test
    fun inFlightBlocksSecondSelection() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 9, 2, true, 1_100L))

        scheduler.markDispatchStarted(5_000L)

        val decision = scheduler.peek(5_100L)

        assertEquals(ActionScheduleBlockReason.ACTION_IN_FLIGHT, decision.reason)
        assertTrue(decision.candidate == null)
    }

    @Test
    fun cooldownBlocksUntilWindowExpires() {
        val scheduler = ActionScheduler(cooldownMs = 1_500L)
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.markDispatchStarted(5_000L)
        scheduler.markActionFinished()

        assertEquals(
            ActionScheduleBlockReason.COOLDOWN_ACTIVE,
            scheduler.peek(6_000L).reason
        )
        assertEquals(target(1), scheduler.peek(6_500L).candidate?.target)
    }

    @Test
    fun claimRemovesOnlySelectedCandidate() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 9, 2, true, 1_100L))

        val claimed = scheduler.claim(5_000L)

        assertEquals(target(1), claimed.candidate?.target)
        assertEquals(1, scheduler.queuedCount())
        assertEquals(target(2), scheduler.peek(5_000L).candidate?.target)
    }

    @Test
    fun duplicateTargetKeepsStrongerCandidate() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(1), 20, 3, true, 1_100L))

        val decision = scheduler.peek(5_000L)

        assertEquals(20, decision.candidate?.priority)
        assertEquals(3, decision.candidate?.stabilityFrames)
        assertEquals(1, scheduler.queuedCount())
    }

    @Test
    fun failedValidationAfterQueueingIsNotRepresentedAsSafe() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 50, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 40, 3, false, 1_100L))

        val first = scheduler.claim(5_000L)
        assertEquals(target(1), first.candidate?.target)

        scheduler.markDispatchStarted(5_000L)
        scheduler.markActionFinished()
        assertFalse(scheduler.peek(5_000L).candidate?.target == target(2))
    }
    @Test
    fun safetyStateBlocksSelectionEvenWithSafeCandidate() {
        val scheduler = ActionScheduler()
        scheduler.offer(candidate(priority = 10, stabilityFrames = 3, safe = true))

        val safety = ActionSchedulerSafetyState(
            lifecycle = ActionLifecycleSnapshot(
                state = ActionLifecycleState.UNKNOWN,
                failure = null
            ),
            automaticActionsEnabled = true,
            restartQuarantine = false,
            recoveryEpochPersistenceHealthy = true
        )

        val decision = scheduler.peek(10_000L, safety)

        assertEquals(ActionScheduleBlockReason.SAFETY_BLOCKED, decision.reason)
        assertEquals(null, decision.candidate)
        assertEquals(1, scheduler.queuedCount())
    }

    @Test
    fun safetyStateAllowsSelectionOnlyWhenCoreRecoveryIsReady() {
        val scheduler = ActionScheduler()
        scheduler.offer(candidate(priority = 10, stabilityFrames = 3, safe = true))

        val safety = ActionSchedulerSafetyState(
            lifecycle = ActionLifecycleSnapshot(
                state = ActionLifecycleState.IDLE,
                failure = null
            ),
            automaticActionsEnabled = true,
            restartQuarantine = false,
            recoveryEpochPersistenceHealthy = true
        )

        assertTrue(scheduler.peek(10_000L, safety).candidate != null)
    }

    @Test
    fun refreshRemovesTargetsThatDisappearedFromLatestScan() {
        val scheduler = ActionScheduler()
        val first = candidate(priority = 10, stabilityFrames = 3, safe = true)
        val second = candidate(priority = 5, stabilityFrames = 3, safe = true)

        scheduler.refresh(listOf(first, second))
        assertEquals(2, scheduler.queuedCount())

        scheduler.refresh(listOf(second))

        val decision = scheduler.peek(10_000L)
        assertEquals(second.target, decision.candidate?.target)
        assertEquals(1, scheduler.queuedCount())
    }

    @Test
    fun refreshDoesNotKeepPreviouslySafeTargetWhenCurrentFrameBecomesUnsafe() {
        val scheduler = ActionScheduler()
        val first = candidate(priority = 10, stabilityFrames = 3, safe = true)

        scheduler.refresh(listOf(first))
        scheduler.refresh(
            listOf(
                first.copy(validationSafe = false)
            )
        )

        assertEquals(0, scheduler.queuedCount())
        assertEquals(ActionScheduleBlockReason.EMPTY_QUEUE, scheduler.peek(10_000L).reason)
    }

    @Test
    fun refreshDoesNotAdmitCurrentFrameUnstableTarget() {
        val scheduler = ActionScheduler()
        val first = candidate(priority = 10, stabilityFrames = 3, safe = true)

        scheduler.refresh(listOf(first))
        scheduler.refresh(
            listOf(
                first.copy(stabilityFrames = 1)
            )
        )

        assertEquals(0, scheduler.queuedCount())
    }

}
