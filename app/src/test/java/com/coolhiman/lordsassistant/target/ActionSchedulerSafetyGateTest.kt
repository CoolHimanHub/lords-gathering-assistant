package com.coolhiman.lordsassistant.target

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionSchedulerSafetyGateTest {

    private val gate = ActionSchedulerSafetyGate()

    private fun state(
        lifecycle: ActionLifecycleState = ActionLifecycleState.IDLE,
        failure: ActionLifecycleFailure? = null,
        automatic: Boolean = true,
        quarantine: Boolean = false,
        persistenceHealthy: Boolean = true
    ) = ActionSchedulerSafetyState(
        lifecycle = ActionLifecycleSnapshot(state = lifecycle, failure = failure),
        automaticActionsEnabled = automatic,
        restartQuarantine = quarantine,
        recoveryEpochPersistenceHealthy = persistenceHealthy
    )

    @Test
    fun idleAutomaticSelectionIsAllowed() {
        assertTrue(gate.maySelect(state()))
        assertEquals(null, gate.blockReason(state()))
    }

    @Test
    fun automaticActionsDisabledBlocksSelection() {
        val reason = gate.blockReason(state(automatic = false))
        assertFalse(gate.maySelect(state(automatic = false)))
        assertEquals(ActionSchedulerSafetyBlockReason.AUTOMATION_DISABLED, reason)
    }

    @Test
    fun restartQuarantineBlocksSelection() {
        assertEquals(
            ActionSchedulerSafetyBlockReason.RESTART_QUARANTINE,
            gate.blockReason(state(quarantine = true))
        )
    }

    @Test
    fun unhealthyRecoveryEpochPersistenceBlocksSelection() {
        assertEquals(
            ActionSchedulerSafetyBlockReason.RECOVERY_EPOCH_PERSISTENCE_UNHEALTHY,
            gate.blockReason(state(persistenceHealthy = false))
        )
    }

    @Test
    fun unknownLifecycleBlocksSelection() {
        assertEquals(
            ActionSchedulerSafetyBlockReason.ACTION_RECOVERY_BLOCKED,
            gate.blockReason(state(lifecycle = ActionLifecycleState.UNKNOWN))
        )
    }

    @Test
    fun recoveryEpochExhaustionBlocksSelection() {
        assertEquals(
            ActionSchedulerSafetyBlockReason.ACTION_RECOVERY_BLOCKED,
            gate.blockReason(
                state(
                    lifecycle = ActionLifecycleState.FAILED,
                    failure = ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED
                )
            )
        )
    }

    @Test
    fun successfulLifecycleCanResumeSelectionWhenDurableSafetyIsHealthy() {
        assertTrue(
            gate.maySelect(
                state(lifecycle = ActionLifecycleState.SUCCEEDED)
            )
        )
    }
}
