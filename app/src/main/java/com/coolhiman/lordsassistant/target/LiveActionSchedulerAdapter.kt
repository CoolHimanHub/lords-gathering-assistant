package com.coolhiman.lordsassistant.target

/**
 * V0.5.1 live-flow adapter.
 *
 * Converts the latest scanner-derived candidates into scheduler input and
 * reconciles the queue in one operation. It deliberately knows nothing about
 * gestures, accessibility, journaling, or dispatch.
 */
class LiveActionSchedulerAdapter(
    private val scheduler: ActionScheduler
) {

    fun update(candidates: Collection<ActionScheduleCandidate>) {
        scheduler.refresh(candidates)
    }

    fun select(
        nowMs: Long,
        safetyState: ActionSchedulerSafetyState
    ): ActionScheduleDecision =
        scheduler.peek(nowMs, safetyState)

    fun claim(
        nowMs: Long,
        safetyState: ActionSchedulerSafetyState
    ): ActionScheduleDecision =
        scheduler.claim(nowMs, safetyState)

    fun clear() {
        scheduler.clear()
    }

    fun queuedCount(): Int = scheduler.queuedCount()
}
