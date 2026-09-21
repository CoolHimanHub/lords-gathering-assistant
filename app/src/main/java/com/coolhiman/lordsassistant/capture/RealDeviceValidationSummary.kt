package com.coolhiman.lordsassistant.capture

import com.coolhiman.lordsassistant.target.ActionDiagnosticsSnapshot

/**
 * Read-only correlation view for sustained real-device validation.
 * It never decides candidate eligibility, scheduling, recovery, or gesture execution.
 */
data class RealDeviceValidationSummary(
    val capture: CaptureSessionDiagnosticsSnapshot?,
    val rejectionCounts: Map<String, Int>,
    val action: ActionDiagnosticsSnapshot?
) {
    val totalRejections: Int
        get() = rejectionCounts.values.sum()

    fun format(): String = buildString {
        appendLine("SUSTAINED DEVICE VALIDATION")
        if (capture == null) {
            appendLine("Capture session: no persisted session yet.")
        } else {
            appendLine("Capture session: #${capture.sessionId} • quality=${capture.quality.name}")
            appendLine("Frames: ${capture.frames} total / ${capture.acceptedFrames} accepted / ${capture.droppedFrames} dropped / ${capture.staleFrames} stale")
            appendLine("FPS: %.2f • drop rate: %.1f%%".format(capture.fps, capture.dropRatePercent))
            appendLine("Latency: %.0f ms total avg • OCR %.0f ms • scan %.0f ms".format(
                capture.averageProcessingMs, capture.averageOcrMs, capture.averageScannerMs
            ))
            appendLine("Runtime: ${capture.restartCount} restarts • ${capture.stallCount} stalls • ${capture.viewportChangeCount} viewport changes")
            capture.runtime.lastStopReason?.let { appendLine("Last stop: ${it.name}") }
            capture.runtime.lastFailureReason?.let { appendLine("Last failure: $it") }
        }
        appendLine()
        appendLine("Candidate rejections: $totalRejections")
        if (rejectionCounts.isEmpty()) {
            appendLine("No persisted CANDIDATE_REJECTED events.")
        } else {
            rejectionCounts.entries.sortedByDescending { it.value }.thenBy { it.key }
                .forEach { (reason, count) -> appendLine("$reason = $count") }
        }
        appendLine()
        if (action == null) {
            appendLine("Action/evidence: no live diagnostic snapshot yet.")
        } else {
            appendLine("Action lifecycle: ${action.lifecycle.state.name}")
            appendLine("Validation: ${action.validationStage.name} • safe=${action.validationSafe}")
            appendLine("Attempt: ${action.actionAttemptId ?: "none"} • recovery epoch=${action.recoveryEpoch}")
            appendLine("Restart quarantine: ${if (action.restartQuarantine) "ACTIVE" else "clear"}")
            action.evidence?.let {
                appendLine("Post-action evidence: ${it.evidence.joinToString(", ") { evidence -> evidence.name }}")
                appendLine("Evidence frames: ${it.confirmingFrames} • camera stable=${it.cameraStable}")
            } ?: appendLine("Post-action evidence: none")
        }
        appendLine()
        appendLine("Diagnostic correlation only — no action authority is granted by this summary.")
    }
}
