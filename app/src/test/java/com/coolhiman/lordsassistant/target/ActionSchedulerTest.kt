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

    private fun candidate(
        priority: Int,
        stabilityFrames: Int,
        safe: Boolean,
        targetX: Int = 99
    ) = ActionScheduleCandidate(
        target = target(targetX),
        priority = priority,
        stabilityFrames = stabilityFrames,
        validationSafe = safe,
        queuedAtMs = 1_000L
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
    fun plannerRankIsPreferredBeforePlannerScoreAndStability() {
        val scheduler = ActionScheduler()
        scheduler.offer(
            ActionScheduleCandidate(
                target = target(1),
                priority = 0,
                stabilityFrames = 2,
                validationSafe = true,
                queuedAtMs = 1_000L,
                plannerRank = 1,
                plannerScore = 999.0
            )
        )
        scheduler.offer(
            ActionScheduleCandidate(
                target = target(2),
                priority = 0,
                stabilityFrames = 2,
                validationSafe = true,
                queuedAtMs = 1_100L,
                plannerRank = 0,
                plannerScore = -100.0
            )
        )

        val decision = scheduler.peek(5_000L)

        assertEquals(target(2), decision.candidate?.target)
        assertEquals(0, decision.candidate?.plannerRank)
    }

    @Test
    fun plannerScoreBreaksTieInsideSamePlannerRank() {
        val scheduler = ActionScheduler()
        scheduler.offer(
            ActionScheduleCandidate(
                target = target(1), priority = 0, stabilityFrames = 2,
                validationSafe = true, queuedAtMs = 1_000L,
                plannerRank = 3, plannerScore = 10.0
            )
        )
        scheduler.offer(
            ActionScheduleCandidate(
                target = target(2), priority = 0, stabilityFrames = 2,
                validationSafe = true, queuedAtMs = 1_100L,
                plannerRank = 3, plannerScore = 20.0
            )
        )

        assertEquals(target(2), scheduler.peek(5_000L).candidate?.target)
    }

    @Test
    fun refreshReplacesStaleMetadataForSameTargetIdentity() {
        val scheduler = ActionScheduler()
        val old = ActionScheduleCandidate(
            target = target(1), priority = 0, stabilityFrames = 4,
            validationSafe = true, queuedAtMs = 1_000L,
            plannerRank = 0, plannerScore = 100.0
        )
        val latest = old.copy(
            stabilityFrames = 2,
            queuedAtMs = 2_000L,
            plannerRank = 4,
            plannerScore = 10.0
        )

        scheduler.refresh(listOf(old))
        scheduler.refresh(listOf(latest))

        val selected = scheduler.peek(5_000L).candidate
        assertEquals(latest.queuedAtMs, selected?.queuedAtMs)
        assertEquals(latest.plannerRank, selected?.plannerRank)
        assertEquals(latest.plannerScore, selected?.plannerScore, 0.0)
        assertEquals(latest.stabilityFrames, selected?.stabilityFrames)
    }

    @Test
    fun refreshDropsTargetWhenLatestFrameChangesItsIdentity() {
        val scheduler = ActionScheduler()
        val old = candidate(priority = 10, stabilityFrames = 3, safe = true)
        val changed = old.copy(target = target(2))

        scheduler.refresh(listOf(old))
        val dropped = scheduler.refresh(listOf(changed))

        assertEquals(listOf(old.target), dropped)
        assertEquals(changed.target, scheduler.peek(10_000L).candidate?.target)
    }

    @Test
    fun completedTargetIsNotRequeuedImmediately() {
        val scheduler = ActionScheduler()
        val completed = ActionScheduleCandidate(
            target = target(1), priority = 0, stabilityFrames = 3,
            validationSafe = true, queuedAtMs = 1_000L,
            plannerRank = 0, plannerScore = 50.0
        )

        scheduler.refresh(listOf(completed))
        assertEquals(1, scheduler.queuedCount())

        val claimed = scheduler.claim(2_000L)
        assertEquals(completed.target, claimed.candidate?.target)
        scheduler.markTargetCompleted(completed.target, 2_000L)

        scheduler.refresh(listOf(completed.copy(queuedAtMs = 2_100L)))

        assertEquals(0, scheduler.queuedCount())
        assertEquals(ActionScheduleBlockReason.EMPTY_QUEUE, scheduler.peek(2_100L).reason)
    }

    @Test
    fun completedTargetCanReenterAfterSuppressionWindow() {
        val scheduler = ActionScheduler()
        val completed = ActionScheduleCandidate(
            target = target(1), priority = 0, stabilityFrames = 3,
            validationSafe = true, queuedAtMs = 1_000L,
            plannerRank = 0, plannerScore = 50.0
        )

        scheduler.markTargetCompleted(completed.target, 2_000L)
        scheduler.refresh(listOf(completed.copy(queuedAtMs = 2_000L)))
        assertEquals(0, scheduler.queuedCount())

        val later = 2_000L + ActionScheduler.COMPLETED_TARGET_SUPPRESSION_MS
        scheduler.refresh(listOf(completed.copy(queuedAtMs = later)))

        assertEquals(1, scheduler.queuedCount())
        assertEquals(completed.target, scheduler.peek(later).candidate?.target)
    }

    @Test
    fun claimedTargetFailureDoesNotDiscardOtherQueuedTarget() {
        val scheduler = ActionScheduler()
        val failed = candidate(priority = 10, stabilityFrames = 3, safe = true, targetX = 1)
        val remaining = candidate(priority = 5, stabilityFrames = 3, safe = true, targetX = 2)

        scheduler.refresh(listOf(failed, remaining))
        val claimed = scheduler.claim(10_000L)
        assertEquals(failed.target, claimed.candidate?.target)

        // The caller's revalidation/dispatch failure belongs only to the
        // claimed attempt; the independently validated target remains queued.
        assertEquals(1, scheduler.queuedCount())
        assertEquals(remaining.target, scheduler.peek(10_000L).candidate?.target)
    }

    @Test
    fun safetyStateBlocksSelectionEvenWithSafeCandidate() {
        val scheduler = ActionScheduler()
        scheduler.offer(candidate(priority = 10, stabilityFrames = 3, safe = true, targetX = 1))

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
        val first = candidate(priority = 10, stabilityFrames = 3, safe = true, targetX = 1)
        val second = candidate(priority = 5, stabilityFrames = 3, safe = true, targetX = 2)

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
        val first = candidate(priority = 10, stabilityFrames = 3, safe = true, targetX = 1)

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

    @Test
    fun refreshReturnsTargetsDroppedByLatestSafeSet() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))
        scheduler.offer(ActionScheduleCandidate(target(2), 9, 2, true, 1_100L))

        val dropped = scheduler.refresh(
            listOf(ActionScheduleCandidate(target(2), 9, 2, true, 2_000L))
        )

        assertEquals(listOf(target(1)), dropped)
        assertEquals(1, scheduler.queuedCount())
        assertEquals(target(2), scheduler.peek(5_000L).candidate?.target)
    }

    @Test
    fun refreshNeverAdmitsUnsafeOrUnstableCandidates() {
        val scheduler = ActionScheduler()
        scheduler.offer(ActionScheduleCandidate(target(1), 10, 2, true, 1_000L))

        val dropped = scheduler.refresh(
            listOf(ActionScheduleCandidate(target(2), 20, 1, true, 2_000L))
        )

        assertEquals(listOf(target(1)), dropped)
        assertEquals(0, scheduler.queuedCount())
    }

}
