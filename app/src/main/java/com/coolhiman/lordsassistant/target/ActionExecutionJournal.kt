package com.coolhiman.lordsassistant.target

import android.content.Context

/**
 * Durable barrier for an action whose gesture may already have reached the game.
 *
 * The marker is committed synchronously before dispatch so process/service death
 * cannot turn an unknown in-flight gesture into an automatic retry. It is cleared
 * only after a definitive dispatch failure or verified/manual recovery.
 */
class ActionExecutionJournal(context: Context) {
    private val prefs = context.getSharedPreferences("lm_action_journal", Context.MODE_PRIVATE)

    data class Entry(val attemptId: Long, val startedAtMs: Long)

    fun markInFlight(attemptId: Long, startedAtMs: Long): Boolean =
        prefs.edit()
            .putBoolean(KEY_IN_FLIGHT, true)
            .putLong(KEY_ATTEMPT_ID, attemptId)
            .putLong(KEY_STARTED_AT, startedAtMs)
            .commit()

    fun readInFlight(): Entry? =
        if (prefs.getBoolean(KEY_IN_FLIGHT, false)) {
            Entry(
                attemptId = prefs.getLong(KEY_ATTEMPT_ID, 0L),
                startedAtMs = prefs.getLong(KEY_STARTED_AT, 0L)
            )
        } else null

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_IN_FLIGHT = "in_flight"
        private const val KEY_ATTEMPT_ID = "attempt_id"
        private const val KEY_STARTED_AT = "started_at"
    }
}
