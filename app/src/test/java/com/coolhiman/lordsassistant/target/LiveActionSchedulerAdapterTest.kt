package com.coolhiman.lordsassistant.target

import kotlin.test.Test
import kotlin.test.assertEquals

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
            coordinate = WorldCoordinate(1, id, id),
            kind = ResourceKind.FOOD,
            level = 1,
            actionKind = ActionKind.GATHER,
            interactionPoint = ScreenPoint(100f + id, 200f + id)
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

    private fun safeState(
        lifecycle: ActionLifecycleState = ActionLifecycleState.IDLE
    ) = ActionSchedulerSafetyState(
        lifecycle = ActionLifecycleSnapshot(state = lifecycle, failure = null),
        automaticActionsEnabled = true,
        restartQuarantine = false,
        recoveryEpochPersistenceHealthy = true
    )
}
