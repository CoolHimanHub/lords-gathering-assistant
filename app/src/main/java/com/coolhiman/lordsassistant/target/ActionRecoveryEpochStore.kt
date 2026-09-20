package com.coolhiman.lordsassistant.target

import android.content.Context

/**
 * Persists the action recovery epoch independently of any single in-flight attempt.
 *
 * This preserves provenance across clean process/service restarts even when no
 * action is currently quarantined in the execution journal.
 */
class ActionRecoveryEpochStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_action_recovery_epoch", Context.MODE_PRIVATE)

    fun read(): Long = prefs.getLong(KEY_EPOCH, 0L)

    fun write(epoch: Long): Boolean =
        prefs.edit().putLong(KEY_EPOCH, epoch).commit()

    companion object {
        private const val KEY_EPOCH = "epoch"
    }
}
