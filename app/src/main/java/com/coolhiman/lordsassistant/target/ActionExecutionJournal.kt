package com.coolhiman.lordsassistant.target

import android.content.Context

/**
 * Durable barrier for an action whose gesture may already have reached the game.
 *
 * The marker is committed synchronously before dispatch so process/service death
 * cannot turn an unknown in-flight gesture into an automatic retry. It is cleared
 * only after a definitive dispatch failure or verified/manual recovery.
 *
 * The stored attempt ID and recovery epoch form the durable provenance boundary
 * for the guarded gesture.
 */
class ActionExecutionJournal(context: Context) {
    private val prefs = context.getSharedPreferences("lm_action_journal", Context.MODE_PRIVATE)

    data class Entry(
        val attemptId: Long,
        val recoveryEpoch: Long,
        val startedAtMs: Long
    )

    fun markInFlight(provenance: ActionDispatchProvenance): Boolean =
        prefs.edit()
            .putBoolean(KEY_IN_FLIGHT, true)
            .putLong(KEY_ATTEMPT_ID, provenance.attemptId)
            .putLong(KEY_RECOVERY_EPOCH, provenance.recoveryEpoch)
            .putLong(KEY_STARTED_AT, provenance.startedAtMs)
            .commit()

    /**
     * Legacy journal entries written before V0.4.46 have no epoch key.
     * They remain useful only for restart quarantine; they can never satisfy
     * the current-session provenance match because the recovered epoch advances.
     */
    fun readInFlight(): Entry? =
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            Entry(
                attemptId = prefs.getLong(KEY_ATTEMPT_ID, 0L),
                recoveryEpoch = prefs.getLong(KEY_RECOVERY_EPOCH, 0L),
                startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L)
            )
        } else null

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IN_FLIGHT = "in_flight"
        private const val KEY_ATTEMPT_ID = "attempt_id"
        private const val KEY_RECOVERY_EPOCH = "recovery_epoch"
        private const val KEY_STARTED_AT = "started_at"
    }
}
