package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveActionSchedulerAdapterTest {

    private fun candidate(
        id: Int,
        priority: Int = 10,
        stabilityFrames: Int = 3,
        safe: Boolean = true
    ) = ActionScheduleCandidate(
        target = target(id),
        priority = priority,
        stabilityFrames = stabilityFrames,
        validationSafe = safe,
        queuedAtMs = id.toLong()
    )

    private fun target(id: Int) =
        ActionTargetSnapshot(
            coordinate = WorldCoordinate(id, 167, 511),
            kind = TargetKind.RESOURCE,
            level = 3,
            actionKind = ActionKind.GATHER,
            point = ScreenPoint(900f + id, 600f)
        )

    @Test
    fun updateReconcilesAndSelectsCurrentSafeTarget() {
        val adapter = LiveActionSchedulerAdapter(ActionScheduler())
        adapter.update(listOf(candidate(1), candidate(2, priority = 20)))

        val decision = adapter.select(
            nowMs = 10_000L,
            safetyState = safeState()
        )

        assertEquals(target(2), decision.candidate?.target)
        assertEquals(2, adapter.queuedCount())
    }

    @Test
    fun secondUpdateRemovesTargetThatDisappeared() {
        val adapter = LiveActionSchedulerAdapter(ActionScheduler())
        adapter.update(listOf(candidate(1), candidate(2)))
        adapter.update(listOf(candidate(2)))

        val decision = adapter.select(
            nowMs = 10_000L,
            safetyState = safeState()
        )

        assertEquals(target(2), decision.candidate?.target)
        assertEquals(1, adapter.queuedCount())
    }

    @Test
    fun unsafeLatestFrameCannotRemainActionable() {
        val adapter = LiveActionSchedulerAdapter(ActionScheduler())
        adapter.update(listOf(candidate(1)))
        adapter.update(listOf(candidate(1, safe = false)))

        assertEquals(0, adapter.queuedCount())
    }

    @Test
    fun safetyBlockDoesNotConsumeCandidate() {
        val adapter = LiveActionSchedulerAdapter(ActionScheduler())
        adapter.update(listOf(candidate(1)))

        val decision = adapter.select(
            nowMs = 10_000L,
            safetyState = safeState(lifecycle = ActionLifecycleState.UNKNOWN)
        )

        assertEquals(ActionScheduleBlockReason.SAFETY_BLOCKED, decision.reason)
        assertEquals(1, adapter.queuedCount())
    }

    @Test
    fun captureSessionResetClearsAdapterSchedulerState() {
        val adapter = LiveActionSchedulerAdapter(ActionScheduler())
        adapter.update(listOf(candidate(1)))
        adapter.markDispatchStarted(1_000L)

        adapter.resetForCaptureSession()

        assertEquals(0, adapter.queuedCount())
        assertEquals(
            ActionScheduleBlockReason.EMPTY_QUEUE,
            adapter.select(1_000L, safeState()).reason
        )

        adapter.update(listOf(candidate(2)))
        assertEquals(target(2), adapter.select(1_000L, safeState()).candidate?.target)
    }

    private fun safeState(
        lifecycle: ActionLifecycleState = ActionLifecycleState.IDLE
    ) = ActionSchedulerSafetyState(
        lifecycle = ActionLifecycleSnapshot(state = lifecycle, failure = null),
        automaticActionsEnabled = true,
        restartQuarantine = false,
        recoveryEpochPersistenceHealthy = true
    )
}
