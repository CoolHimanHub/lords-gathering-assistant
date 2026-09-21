package com.coolhiman.lordsassistant.target

/**
 * Policy gate for a future automated action.
 *
 * This class deliberately does not dispatch gestures. It decides whether a
 * gesture request is eligible after the target has passed revalidation.
 */
data class ActionExecutionDecision(
    val allowed: Boolean,
    val reason: ActionExecutionBlockReason? = null
)

enum class ActionExecutionBlockReason {
    AUTOMATION_DISABLED,
    TARGET_INVALID,
    COOLDOWN,
    DUPLICATE_TARGET
}

class ActionExecutionController(
    private val cooldownMs: Long = 1500L,
    private val duplicateTargetWindowMs: Long = 3000L
) {
    private var lastDispatchMs: Long = Long.MIN_VALUE
    private var lastTarget: ActionTargetSnapshot? = null
    private var lastTargetMs: Long = Long.MIN_VALUE

    fun request(
        automaticActionsEnabled: Boolean,
        selected: ActionTargetSnapshot?,
        validation: TargetValidationResult,
        nowMs: Long
    ): ActionExecutionDecision {
        if (!automaticActionsEnabled) {
            return ActionExecutionDecision(false, ActionExecutionBlockReason.AUTOMATION_DISABLED)
        }
        if (selected == null || !validation.safe || validation.stage != TargetValidationStage.SAFE_TO_INTERACT) {
            return ActionExecutionDecision(false, ActionExecutionBlockReason.TARGET_INVALID)
        }
        if (lastDispatchMs != Long.MIN_VALUE && nowMs - lastDispatchMs < cooldownMs) {
            return ActionExecutionDecision(false, ActionExecutionBlockReason.COOLDOWN)
        }
        if (lastTarget != null && selected == lastTarget && lastTargetMs != Long.MIN_VALUE && nowMs - lastTargetMs < duplicateTargetWindowMs) {
            return ActionExecutionDecision(false, ActionExecutionBlockReason.DUPLICATE_TARGET)
        }

        return ActionExecutionDecision(true)
    }

    fun markDispatched(selected: ActionTargetSnapshot, nowMs: Long) {
        lastDispatchMs = nowMs
        lastTarget = selected
        lastTargetMs = nowMs
    }

    fun reset() {
        lastDispatchMs = Long.MIN_VALUE
        lastTarget = null
        lastTargetMs = Long.MIN_VALUE
    }
}
