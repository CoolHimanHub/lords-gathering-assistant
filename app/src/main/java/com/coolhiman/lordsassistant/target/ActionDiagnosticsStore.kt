package com.coolhiman.lordsassistant.target

/**
 * Process-local diagnostics bridge between the screen scanner and the debug UI.
 * It carries no authority to execute an action.
 */
object ActionDiagnosticsStore {
    @Volatile var latest: ActionDiagnosticsSnapshot? = null
}
