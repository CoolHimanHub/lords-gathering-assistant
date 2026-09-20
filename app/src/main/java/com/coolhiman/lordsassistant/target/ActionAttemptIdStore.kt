package com.coolhiman.lordsassistant.target

import android.content.Context

/**
 * Persists action-attempt identity independently of the in-flight journal.
 *
 * Allocation is synchronous so an attempt ID is durable before a guarded
 * gesture can be dispatched. The minimum value prevents legacy/recovered
 * journal IDs from being reused after an upgrade or restart.
 */
class ActionAttemptIdStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_action_attempt_id", Context.MODE_PRIVATE)

    fun read(): Long = prefs.getLong(KEY_ATTEMPT_ID, 0L)

    fun allocateNext(minimumPreviousId: Long = 0L): Long? {
        val next = maxOf(read(), minimumPreviousId) + 1L
        if (!prefs.edit().putLong(KEY_ATTEMPT_ID, next).commit()) return null
        return next
    }

    companion object {
        private const val KEY_ATTEMPT_ID = "attempt_id"
    }
}
