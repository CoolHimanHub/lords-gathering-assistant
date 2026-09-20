package com.coolhiman.lordsassistant.target

/**
 * Process-local command bridge from diagnostics UI to the active capture service.
 *
 * The UI can request recovery, but the capture service remains responsible for
 * checking the lifecycle state and automation preference before resetting.
 */
object ActionManualRecoveryStore {
    @Volatile
    private var resetRequested = false

    fun requestReset() {
        resetRequested = true
    }

    fun consumeResetRequest(): Boolean {
        if (!resetRequested) return false
        resetRequested = false
        return true
    }

    fun clear() {
        resetRequested = false
    }
}
