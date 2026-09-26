package com.coolhiman.lordsassistant.target

/**
 * V0.5.1 safety boundary between multi-target scheduling and the action core.
 *
 * The scheduler may queue candidates independently, but selection for an
 * automatic action is allowed only when the action lifecycle and durable
 * recovery state permit a fresh attempt.
 */
data class ActionSchedulerSafetyState(
    val lifecycle: ActionLifecycleSnapshot,
    val automaticActionsEnabled: Boolean,
    val restartQuarantine: Boolean,
    val recoveryEpochPersistenceHealthy: Boolean,
    val captureSessionId: Long? = null
)

enum class ActionSchedulerSafetyBlockReason {
    AUTOMATION_DISABLED,
    RESTART_QUARANTINE,
    RECOVERY_EPOCH_PERSISTENCE_UNHEALTHY,
    ACTION_RECOVERY_BLOCKED
}

class ActionSchedulerSafetyGate {

    fun blockReason(state: ActionSchedulerSafetyState): ActionSchedulerSafetyBlockReason? {
        if (!state.automaticActionsEnabled) {
            return ActionSchedulerSafetyBlockReason.AUTOMATION_DISABLED
        }
        if (state.restartQuarantine) {
            return ActionSchedulerSafetyBlockReason.RESTART_QUARANTINE
        }
        if (!state.recoveryEpochPersistenceHealthy) {
            return ActionSchedulerSafetyBlockReason.RECOVERY_EPOCH_PERSISTENCE_UNHEALTHY
        }
        if (!ActionRecoveryPolicy.mayStartAutomaticAttempt(state.lifecycle)) {
            return ActionSchedulerSafetyBlockReason.ACTION_RECOVERY_BLOCKED
        }
        return null
    }

    fun maySelect(state: ActionSchedulerSafetyState): Boolean =
        blockReason(state) == null
}
