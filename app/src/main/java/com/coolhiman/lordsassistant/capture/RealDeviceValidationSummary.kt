package com.coolhiman.lordsassistant.capture

import com.coolhiman.lordsassistant.target.ActionDiagnosticsSnapshot

/**
 * Read-only correlation view for sustained real-device validation.
 * It never decides candidate eligibility, scheduling, recovery, or gesture execution.
 */
data class RealDeviceValidationSummary(
    val capture: CaptureSessionDiagnosticsSnapshot?,
    val rejectionCounts: Map<String, Int>,
    val action: ActionDiagnosticsSnapshot?,
    val history: List<CaptureSessionDiagnosticsSnapshot> = emptyList()
) {
    val totalRejections: Int
        get() = rejectionCounts.values.sum()

    fun format(): String = buildString {
        appendLine("SUSTAINED DEVICE VALIDATION")
        if (capture == null) {
            appendLine("Capture session: no persisted session yet.")
        } else {
            appendLine("Capture session: #${capture.sessionId} • state=${capture.sessionState.name.lowercase()} • quality=${capture.quality.name}")
            appendLine("Frames: ${capture.frames} total / ${capture.acceptedFrames} accepted / ${capture.droppedFrames} dropped / ${capture.staleFrames} stale")
            appendLine("Input FPS: %.2f • accepted FPS: %.2f • input backpressure: %.1f%%".format(capture.fps, capture.acceptedFramesPerSecond, capture.dropRatePercent))
            appendLine("Latency: %.0f ms total avg • OCR %.0f ms • scan %.0f ms".format(
                capture.averageProcessingMs, capture.averageOcrMs, capture.averageScannerMs
            ))
            appendLine("Runtime: ${capture.restartCount} restarts • ${capture.stallCount} stalls • ${capture.viewportChangeCount} viewport changes")
            capture.runtime.lastStopReason?.let { appendLine("Last stop: ${it.name}") }
            capture.runtime.lastFailureReason?.let { appendLine("Last failure: $it") }
        }
        appendLine()
        appendLine("CROSS-SESSION CORRELATION")
        if (history.isEmpty()) {
            appendLine("Completed sessions: none persisted.")
        } else {
            val qualityCounts = history.groupingBy { it.quality.name }.eachCount()
            val stopCounts = history.mapNotNull { it.runtime.lastStopReason?.name }.groupingBy { it }.eachCount()
            val averageFps = history.map { it.fps }.average()
            appendLine("Completed sessions: ${history.size}")
            appendLine("Average FPS: %.2f".format(averageFps))
            appendLine("Quality: " + qualityCounts.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine("Stop reasons: " + if (stopCounts.isEmpty()) "none" else stopCounts.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" })
            val sessionRejections = history
                .flatMap { snapshot -> snapshot.candidateRejectionCounts.entries }
                .groupingBy { it.key }
                .fold(0) { total, entry -> total + entry.value }
            val totalSessionRejections = history.sumOf { it.candidateRejectionCounts.values.sum() }
            val totalAcceptedFrames = history.sumOf { it.acceptedFrames }
            val rejectionDensity = if (totalAcceptedFrames > 0L) {
                totalSessionRejections * 100.0 / totalAcceptedFrames
            } else {
                0.0
            }
            val averageDropRate = history.map { it.dropRatePercent }.average()
            val totalStalls = history.sumOf { it.stallCount }
            val totalViewportChanges = history.sumOf { it.viewportChangeCount }
            appendLine("Session-attributed rejections: " + if (sessionRejections.isEmpty()) "none" else sessionRejections.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine("Trend: %.2f%% rejections per 100 accepted frames • avg input backpressure %.1f%% • stalls=%d • viewport changes=%d".format(
                rejectionDensity, averageDropRate, totalStalls, totalViewportChanges
            ))
        }
        appendLine()
        appendLine("Latest/all-session candidate rejections: $totalRejections")
        if (rejectionCounts.isEmpty()) {
            appendLine("No persisted CANDIDATE_REJECTED events.")
        } else {
            rejectionCounts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
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
