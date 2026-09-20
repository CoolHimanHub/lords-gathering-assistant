package com.coolhiman.lordsassistant.target

/**
 * Immutable provenance captured immediately before a guarded gesture.
 *
 * A dispatch is valid only when its attempt ID and recovery epoch still match
 * the live action session. This prevents a stale pre-dispatch record from
 * crossing a recovery/restart boundary.
 */
data class ActionDispatchProvenance(
    val attemptId: Long,
    val recoveryEpoch: Long,
    val startedAtMs: Long
) {
    fun matches(session: ActionOrchestrator.Session?): Boolean =
        session != null &&
            session.attemptId == attemptId &&
            session.recoveryEpoch == recoveryEpoch
}
