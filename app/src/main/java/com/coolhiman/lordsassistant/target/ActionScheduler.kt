package com.coolhiman.lordsassistant.target

/**
 * V0.5.1 multi-target scheduling boundary.
 *
 * The scheduler decides which already-detected candidate should be offered to
 * the action pipeline next. It never performs a gesture and never bypasses
 * ActionOrchestrator validation/recovery.
 */
data class ActionScheduleCandidate(
    val target: ActionTargetSnapshot,
    val priority: Int,
    val stabilityFrames: Int,
    val validationSafe: Boolean,
    val queuedAtMs: Long
)

data class ActionScheduleDecision(
    val candidate: ActionScheduleCandidate?,
    val reason: ActionScheduleBlockReason?
)

enum class ActionScheduleBlockReason {
    EMPTY_QUEUE,
    ACTION_IN_FLIGHT,
    COOLDOWN_ACTIVE,
    NO_SAFE_CANDIDATE
}

class ActionScheduler(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val minimumStabilityFrames: Int = DEFAULT_MIN_STABILITY_FRAMES
) {
    private val candidates = linkedMapOf<ActionTargetSnapshot, ActionScheduleCandidate>()
    private var inFlight = false
    private var lastDispatchAtMs: Long? = null

    fun offer(candidate: ActionScheduleCandidate) {
        if (!candidate.validationSafe) return
        if (candidate.stabilityFrames < minimumStabilityFrames) return

        val existing = candidates[candidate.target]
        if (existing == null || compare(candidate, existing) < 0) {
            candidates[candidate.target] = candidate
        }
    }

    fun remove(target: ActionTargetSnapshot) {
        candidates.remove(target)
    }

    fun clear() {
        candidates.clear()
    }

    fun markDispatchStarted(nowMs: Long) {
        inFlight = true
        lastDispatchAtMs = nowMs
    }

    fun markActionFinished() {
        inFlight = false
    }

    fun peek(nowMs: Long): ActionScheduleDecision {
        if (candidates.isEmpty()) {
            return ActionScheduleDecision(null, ActionScheduleBlockReason.EMPTY_QUEUE)
        }
        if (inFlight) {
            return ActionScheduleDecision(null, ActionScheduleBlockReason.ACTION_IN_FLIGHT)
        }

        val last = lastDispatchAtMs
        if (last != null && nowMs - last < cooldownMs) {
            return ActionScheduleDecision(null, ActionScheduleBlockReason.COOLDOWN_ACTIVE)
        }

        val next = candidates.values
            .asSequence()
            .filter { it.validationSafe && it.stabilityFrames >= minimumStabilityFrames }
            .minWithOrNull(::compare)

        return if (next != null) {
            ActionScheduleDecision(next, null)
        } else {
            ActionScheduleDecision(null, ActionScheduleBlockReason.NO_SAFE_CANDIDATE)
        }
    }

    /**
     * Claims the currently selected candidate. Claiming only removes it from
     * the scheduler; the caller must still pass it through revalidation,
     * provenance, journaling, and guarded dispatch.
     */
    fun claim(nowMs: Long): ActionScheduleDecision {
        val decision = peek(nowMs)
        val candidate = decision.candidate ?: return decision
        candidates.remove(candidate.target)
        return decision
    }

    fun queuedCount(): Int = candidates.size

    fun isInFlight(): Boolean = inFlight

    private fun compare(
        left: ActionScheduleCandidate,
        right: ActionScheduleCandidate
    ): Int {
        // Higher priority first, then stronger stability, then older queue
        // entry. No target is considered "safe" merely because it ranks high.
        return compareValuesBy(
            left,
            right,
            { -it.priority },
            { -it.stabilityFrames },
            { it.queuedAtMs }
        )
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1_500L
        const val DEFAULT_MIN_STABILITY_FRAMES = 2
    }
}
