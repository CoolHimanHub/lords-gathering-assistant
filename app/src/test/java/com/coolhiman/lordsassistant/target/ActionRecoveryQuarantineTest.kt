package com.coolhiman.lordsassistant.target

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionRecoveryQuarantineTest {

    @Test
    fun restartRecoveryStartsQuarantinedWithOriginalProvenance() {
        val gate = ActionRecoveryQuarantine()

        gate.restore(
            attemptId = 41L,
            recoveryEpoch = 12L,
            captureSessionId = 701L
        )

        assertTrue(gate.active)
        assertTrue(gate.snapshot.recoveredAttemptId == 41L)
        assertTrue(gate.snapshot.recoveredEpoch == 12L)
        assertTrue(gate.snapshot.recoveredCaptureSessionId == 701L)
    }

    @Test
    fun releaseRequiresBothDurableBarriersAndIdleLifecycle() {
        val gate = ActionRecoveryQuarantine()
        gate.restore(41L, 12L, 701L)

        assertFalse(gate.releaseAfterDeliberateRecovery(
            lifecycleIdle = true,
            recoveryEpochPersisted = false,
            journalCleared = true
        ))
        assertTrue(gate.active)

        assertFalse(gate.releaseAfterDeliberateRecovery(
            lifecycleIdle = true,
            recoveryEpochPersisted = true,
            journalCleared = false
        ))
        assertTrue(gate.active)

        assertFalse(gate.releaseAfterDeliberateRecovery(
            lifecycleIdle = false,
            recoveryEpochPersisted = true,
            journalCleared = true
        ))
        assertTrue(gate.active)

        assertTrue(gate.releaseAfterDeliberateRecovery(
            lifecycleIdle = true,
            recoveryEpochPersisted = true,
            journalCleared = true
        ))
        assertFalse(gate.active)
    }

    @Test
    fun recoveredCaptureSessionIsProvenanceOnlyAndClearedOnRelease() {
        val gate = ActionRecoveryQuarantine()
        gate.restore(99L, 21L, 800L)

        assertTrue(gate.snapshot.recoveredCaptureSessionId == 800L)
        assertTrue(gate.releaseAfterDeliberateRecovery(
            lifecycleIdle = true,
            recoveryEpochPersisted = true,
            journalCleared = true
        ))
        assertTrue(gate.snapshot.recoveredCaptureSessionId == null)
        assertTrue(gate.snapshot.recoveredAttemptId == null)
    }

    @Test
    fun failedReleaseCannotBeBypassedByRepeatedCall() {
        val gate = ActionRecoveryQuarantine()
        gate.restore(77L, 30L, null)

        repeat(3) {
            assertFalse(gate.releaseAfterDeliberateRecovery(
                lifecycleIdle = true,
                recoveryEpochPersisted = false,
                journalCleared = true
            ))
        }

        assertTrue(gate.active)
    }
}
