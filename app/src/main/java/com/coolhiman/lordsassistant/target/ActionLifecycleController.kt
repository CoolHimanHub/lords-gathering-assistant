package com.coolhiman.lordsassistant.target

/**
 * Explicit lifecycle for a future automated action.
 *
 * This class is deliberately UI/Accessibility agnostic. It records the
 * decision path around an action and requires explicit post-action evidence
 * before reporting success.
 */
enum class ActionLifecycleState {
    IDLE,
    REQUESTED,
    REVALIDATED,
    WAITING_FOR_RESULT,
    SUCCEEDED,
    FAILED,
    UNKNOWN
}

enum class ActionLifecycleFailure {
    EXECUTION_BLOCKED,
    ATTEMPT_ID_PERSISTENCE_FAILED,
    REVALIDATION_FAILED,
    DISPATCH_FAILED,
    VERIFICATION_FAILED,
    VERIFICATION_TIMEOUT,
    CAPTURE_SESSION_CHANGED,
    RECOVERY_EPOCH_EXHAUSTED
}

/**
 * Automatic recovery policy.
 *
 * UNKNOWN is intentionally non-retryable because the previous gesture may
 * have succeeded without enough evidence to prove the outcome.
 */
object ActionRecoveryPolicy {
    fun mayStartAutomaticAttempt(state: ActionLifecycleState): Boolean = when (state) {
        ActionLifecycleState.IDLE,
        ActionLifecycleState.SUCCEEDED,
        ActionLifecycleState.FAILED -> true
        ActionLifecycleState.REQUESTED,
        ActionLifecycleState.REVALIDATED,
        ActionLifecycleState.WAITING_FOR_RESULT,
        ActionLifecycleState.UNKNOWN -> false
    }

    fun mayStartAutomaticAttempt(snapshot: ActionLifecycleSnapshot): Boolean =
        snapshot.failure != ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED &&
            mayStartAutomaticAttempt(snapshot.state)
}

data class ActionLifecycleSnapshot(
    val state: ActionLifecycleState,
    val selected: ActionTargetSnapshot? = null,
    val failure: ActionLifecycleFailure? = null
)

enum class PostActionEvidence {
    POPUP_DISAPPEARED,
    TARGET_REMOVED,
    TARGET_OCCUPIED,
    OWN_MARCH_CONFIRMED,
    ACTION_REJECTED
}

/**
 * Conservative verifier: generic march/occupancy evidence is not enough to
 * claim that our own action succeeded. A positive result requires either an
 * explicitly associated own march or a disappeared popup plus a target state
 * change/removal.
 */
object ActionPostVerifier {
    fun verify(evidence: Set<PostActionEvidence>): ActionLifecycleState = when {
        PostActionEvidence.ACTION_REJECTED in evidence -> ActionLifecycleState.FAILED
        PostActionEvidence.OWN_MARCH_CONFIRMED in evidence -> ActionLifecycleState.SUCCEEDED
        PostActionEvidence.POPUP_DISAPPEARED in evidence &&
            (PostActionEvidence.TARGET_REMOVED in evidence ||
             PostActionEvidence.TARGET_OCCUPIED in evidence) ->
            ActionLifecycleState.SUCCEEDED
        else -> ActionLifecycleState.UNKNOWN
    }
}

class ActionLifecycleController(
    private val executionController: ActionExecutionController = ActionExecutionController()
) {
    var snapshot: ActionLifecycleSnapshot = ActionLifecycleSnapshot(ActionLifecycleState.IDLE)
        private set

    fun request(
        automaticActionsEnabled: Boolean,
        selected: ActionTargetSnapshot?,
        validation: TargetValidationResult,
        nowMs: Long
    ): ActionLifecycleSnapshot {
        val decision = executionController.request(
            automaticActionsEnabled = automaticActionsEnabled,
            selected = selected,
            validation = validation,
            nowMs = nowMs
        )
        return if (decision.allowed) {
            snapshot = ActionLifecycleSnapshot(
                state = ActionLifecycleState.REQUESTED,
                selected = selected
            )
            snapshot
        } else {
            snapshot = ActionLifecycleSnapshot(
                state = ActionLifecycleState.FAILED,
                selected = selected,
                failure = ActionLifecycleFailure.EXECUTION_BLOCKED
            )
            snapshot
        }
    }

    fun attemptIdPersistenceFailed(selected: ActionTargetSnapshot?): ActionLifecycleSnapshot {
        snapshot = ActionLifecycleSnapshot(
            state = ActionLifecycleState.FAILED,
            selected = selected,
            failure = ActionLifecycleFailure.ATTEMPT_ID_PERSISTENCE_FAILED
        )
        return snapshot
    }

    fun revalidated(validation: TargetValidationResult): ActionLifecycleSnapshot {
        if (snapshot.state != ActionLifecycleState.REQUESTED) return snapshot
        snapshot = if (validation.safe && validation.stage == TargetValidationStage.SAFE_TO_INTERACT) {
            snapshot.copy(state = ActionLifecycleState.REVALIDATED)
        } else {
            snapshot.copy(
                state = ActionLifecycleState.FAILED,
                failure = ActionLifecycleFailure.REVALIDATION_FAILED
            )
        }
        return snapshot
    }

    fun captureSessionChanged(): ActionLifecycleSnapshot {
        snapshot = when (snapshot.state) {
            ActionLifecycleState.REQUESTED,
            ActionLifecycleState.REVALIDATED,
            ActionLifecycleState.WAITING_FOR_RESULT -> snapshot.copy(
                state = ActionLifecycleState.UNKNOWN,
                failure = ActionLifecycleFailure.CAPTURE_SESSION_CHANGED
            )
            else -> snapshot
        }
        return snapshot
    }

    fun dispatched(nowMs: Long, dispatchAccepted: Boolean): ActionLifecycleSnapshot {
        if (snapshot.state != ActionLifecycleState.REVALIDATED || snapshot.selected == null) {
            return snapshot.copy(
                state = ActionLifecycleState.FAILED,
                failure = ActionLifecycleFailure.DISPATCH_FAILED
            )
        }
        if (!dispatchAccepted) {
            snapshot = snapshot.copy(
                state = ActionLifecycleState.FAILED,
                failure = ActionLifecycleFailure.DISPATCH_FAILED
            )
            return snapshot
        }
        val selected = snapshot.selected ?: return snapshot.copy(
            state = ActionLifecycleState.FAILED,
            failure = ActionLifecycleFailure.DISPATCH_FAILED
        )
        executionController.markDispatched(selected, nowMs)
        snapshot = snapshot.copy(state = ActionLifecycleState.WAITING_FOR_RESULT)
        return snapshot
    }

    fun verify(evidence: Set<PostActionEvidence>): ActionLifecycleSnapshot {
        if (snapshot.state != ActionLifecycleState.WAITING_FOR_RESULT) return snapshot
        snapshot = when (ActionPostVerifier.verify(evidence)) {
            ActionLifecycleState.SUCCEEDED -> snapshot.copy(state = ActionLifecycleState.SUCCEEDED)
            ActionLifecycleState.FAILED -> snapshot.copy(
                state = ActionLifecycleState.FAILED,
                failure = ActionLifecycleFailure.VERIFICATION_FAILED
            )
            else -> snapshot.copy(state = ActionLifecycleState.UNKNOWN)
        }
        return snapshot
    }

    fun timeout(): ActionLifecycleSnapshot {
        if (snapshot.state == ActionLifecycleState.WAITING_FOR_RESULT) {
            snapshot = snapshot.copy(
                state = ActionLifecycleState.UNKNOWN,
                failure = ActionLifecycleFailure.VERIFICATION_TIMEOUT
            )
        }
        return snapshot
    }

    fun restoreUnknown(): ActionLifecycleSnapshot {
        snapshot = ActionLifecycleSnapshot(
            state = ActionLifecycleState.UNKNOWN,
            failure = ActionLifecycleFailure.VERIFICATION_TIMEOUT
        )
        return snapshot
    }

    fun recoveryEpochExhausted(): ActionLifecycleSnapshot {
        snapshot = ActionLifecycleSnapshot(
            state = ActionLifecycleState.FAILED,
            failure = ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED
        )
        return snapshot
    }

    fun reset(): ActionLifecycleSnapshot {
        executionController.reset()
        snapshot = ActionLifecycleSnapshot(ActionLifecycleState.IDLE)
        return snapshot
    }
}
