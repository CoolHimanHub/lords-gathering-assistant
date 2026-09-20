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
    REVALIDATION_FAILED,
    DISPATCH_FAILED,
    VERIFICATION_FAILED,
    VERIFICATION_TIMEOUT
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
        executionController.markDispatched(snapshot.selected, nowMs)
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

    fun reset(): ActionLifecycleSnapshot {
        executionController.reset()
        snapshot = ActionLifecycleSnapshot(ActionLifecycleState.IDLE)
        return snapshot
    }
}
