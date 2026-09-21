package com.coolhiman.lordsassistant.target

/**
 * Explicit recovery gate for a process/service restart that recovered a
 * durable in-flight action.
 *
 * The gate is reconstructed from ActionExecutionJournal on every service
 * start. It can only be released by a deliberate recovery after both durable
 * barriers are established: the new recovery epoch is persisted and the
 * in-flight journal is synchronously cleared.
 */
data class ActionRecoveryQuarantineSnapshot(
    val active: Boolean = false,
    val recoveredAttemptId: Long? = null,
    val recoveredEpoch: Long? = null,
    val recoveredCaptureSessionId: Long? = null
)

class ActionRecoveryQuarantine {
    private var state = ActionRecoveryQuarantineSnapshot()

    val snapshot: ActionRecoveryQuarantineSnapshot
        get() = state

    val active: Boolean
        get() = state.active

    fun restore(
        attemptId: Long,
        recoveryEpoch: Long,
        captureSessionId: Long?
    ) {
        state = ActionRecoveryQuarantineSnapshot(
            active = true,
            recoveredAttemptId = attemptId,
            recoveredEpoch = recoveryEpoch,
            recoveredCaptureSessionId = captureSessionId
        )
    }

    /**
     * Releases restart quarantine only after deliberate recovery has reached
     * IDLE and both durable barriers report success.
     */
    fun releaseAfterDeliberateRecovery(
        lifecycleIdle: Boolean,
        recoveryEpochPersisted: Boolean,
        journalCleared: Boolean,
        currentCaptureSessionId: Long? = null
    ): Boolean {
        if (!state.active) return false
        if (!lifecycleIdle || !recoveryEpochPersisted || !journalCleared) return false
        if (state.recoveredCaptureSessionId != null &&
            (currentCaptureSessionId == null || currentCaptureSessionId == state.recoveredCaptureSessionId)
        ) return false

        state = ActionRecoveryQuarantineSnapshot()
        return true
    }
}
