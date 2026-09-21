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
    val queuedAtMs: Long,
    /** Zero-based order from TargetPlanner; lower is more preferred. */
    val plannerRank: Int = Int.MAX_VALUE,
    /** Planner's native score, retained as a tie-breaker inside the same rank. */
    val plannerScore: Double = Double.NEGATIVE_INFINITY
)

data class ActionScheduleDecision(
    val candidate: ActionScheduleCandidate?,
    val reason: ActionScheduleBlockReason?
)

enum class ActionScheduleBlockReason {
    EMPTY_QUEUE,
    ACTION_IN_FLIGHT,
    COOLDOWN_ACTIVE,
    NO_SAFE_CANDIDATE,
    SAFETY_BLOCKED
}

class ActionScheduler(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val minimumStabilityFrames: Int = DEFAULT_MIN_STABILITY_FRAMES
) {
    private val candidates = linkedMapOf<ActionTargetIdentity, ActionScheduleCandidate>()
    private val completedUntilMs = linkedMapOf<ActionTargetIdentity, Long>()
    private var inFlight = false
    private var lastDispatchAtMs: Long? = null

    fun offer(candidate: ActionScheduleCandidate) {
        if (!candidate.validationSafe) return
        if (candidate.stabilityFrames < minimumStabilityFrames) return
        if (isCompletedAndSuppressed(candidate.target, candidate.queuedAtMs)) return

        val identity = candidate.target.identity()
        val existing = candidates[identity]
        if (existing == null || compare(candidate, existing) < 0) {
            candidates[identity] = candidate
        }
    }

    /**
     * Reconciles the queue with the latest scan.
     *
     * Targets no longer present in the current safe candidate set are removed,
     * preventing an old frame from becoming an actionable target later.
     * Current candidates are then offered using the normal stability/safety
     * admission rules.
     */
    fun refresh(current: Collection<ActionScheduleCandidate>): List<ActionTargetSnapshot> {
        val previousTargets = candidates.values.map { it.target }.toSet()
        val latestSafe = linkedMapOf<ActionTargetIdentity, ActionScheduleCandidate>()

        current.forEach { candidate ->
            if (!candidate.validationSafe || candidate.stabilityFrames < minimumStabilityFrames) return@forEach
            if (isCompletedAndSuppressed(candidate.target, candidate.queuedAtMs)) return@forEach

            val identity = candidate.target.identity()
            val existing = latestSafe[identity]
            if (existing == null || compare(candidate, existing) < 0) {
                latestSafe[identity] = candidate
            }
        }

        // The latest scan is authoritative. Rebuild the queued candidate set
        // from it so planner rank, validation, stability and timestamps cannot
        // remain stale merely because the target identity stayed equal.
        candidates.clear()
        candidates.putAll(latestSafe)

        return previousTargets.filter { it.identity() !in latestSafe.keys }
    }

    fun remove(target: ActionTargetSnapshot) {
        candidates.remove(target.identity())
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

    /**
     * Suppresses a successfully completed target from immediate re-entry.
     * The suppression is scheduler-local and bounded; a later fresh scan may
     * re-offer the target after the cooldown window expires.
     */
    fun markTargetCompleted(target: ActionTargetSnapshot, nowMs: Long) {
        candidates.remove(target)
        completedUntilMs[target.identity()] = nowMs + COMPLETED_TARGET_SUPPRESSION_MS
        completedUntilMs.entries.removeIf { it.value <= nowMs }
    }

    fun peek(
        nowMs: Long,
        safetyState: ActionSchedulerSafetyState
    ): ActionScheduleDecision {
        val blockReason = ActionSchedulerSafetyGate().blockReason(safetyState)
        if (blockReason != null) {
            return ActionScheduleDecision(null, ActionScheduleBlockReason.SAFETY_BLOCKED)
        }
        return peek(nowMs)
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
            .filter {
                it.validationSafe &&
                    it.stabilityFrames >= minimumStabilityFrames &&
                    !isCompletedAndSuppressed(it.target, nowMs)
            }
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
    fun claim(
        nowMs: Long,
        safetyState: ActionSchedulerSafetyState
    ): ActionScheduleDecision {
        val decision = peek(nowMs, safetyState)
        val candidate = decision.candidate ?: return decision
        candidates.remove(candidate.target)
        return decision
    }

    fun claim(nowMs: Long): ActionScheduleDecision {
        val decision = peek(nowMs)
        val candidate = decision.candidate ?: return decision
        candidates.remove(candidate.target)
        return decision
    }

    fun queuedCount(): Int = candidates.size

    fun isInFlight(): Boolean = inFlight

    private fun isCompletedAndSuppressed(target: ActionTargetSnapshot, nowMs: Long): Boolean {
        val identity = target.identity()
        val until = completedUntilMs[identity] ?: return false
        if (until <= nowMs) {
            completedUntilMs.remove(identity)
            return false
        }
        return true
    }

    private fun compare(
        left: ActionScheduleCandidate,
        right: ActionScheduleCandidate
    ): Int {
        // Higher priority first, then stronger stability, then older queue
        // entry. No target is considered "safe" merely because it ranks high.
        return compareBy<ActionScheduleCandidate> { -it.priority }
            .thenBy { it.plannerRank }
            .thenBy { -it.plannerScore }
            .thenBy { -it.stabilityFrames }
            .thenBy { it.queuedAtMs }
            .compare(left, right)
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 1_500L
        const val DEFAULT_MIN_STABILITY_FRAMES = 2
        const val COMPLETED_TARGET_SUPPRESSION_MS = 5_000L
    }
}
