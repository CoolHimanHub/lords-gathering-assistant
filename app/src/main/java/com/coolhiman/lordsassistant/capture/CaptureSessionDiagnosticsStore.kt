package com.coolhiman.lordsassistant.capture

import android.content.Context
import org.json.JSONObject

/**
 * Persists the latest read-only capture-session diagnostics so the evidence
 * screen can inspect a completed/restarted device session. This store has no
 * action-authority semantics and never changes scheduler eligibility.
 */
class CaptureSessionDiagnosticsStore(context: Context) {
    private val prefs = context.getSharedPreferences("lm_capture_diagnostics", Context.MODE_PRIVATE)

    fun save(snapshot: CaptureSessionDiagnosticsSnapshot): Boolean =
        prefs.edit().putString(KEY_SNAPSHOT, toJson(snapshot).toString()).commit()

    fun read(): CaptureSessionDiagnosticsSnapshot? {
        val raw = prefs.getString(KEY_SNAPSHOT, null) ?: return null
        return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
    }

    fun clear(): Boolean = prefs.edit().remove(KEY_SNAPSHOT).commit()

    fun formatLatest(): String {
        val snapshot = read() ?: return "No persisted capture-session diagnostics yet."
        return buildString {
            appendLine("REAL-DEVICE CAPTURE SESSION")
            appendLine("Session: #${snapshot.sessionId}  • state=${snapshot.sessionState.name.lowercase()}")
            appendLine("Frames: ${snapshot.frames} total / ${snapshot.acceptedFrames} accepted / ${snapshot.droppedFrames} dropped / ${snapshot.staleFrames} stale")
            appendLine("FPS: %.2f  • drop rate: %.1f%%".format(snapshot.fps, snapshot.dropRatePercent))
            appendLine("Processing: %.0f ms avg / ${snapshot.maxProcessingMs} ms max".format(snapshot.averageProcessingMs))
            appendLine("OCR: %.0f ms avg  • Scan: %.0f ms avg".format(snapshot.averageOcrMs, snapshot.averageScannerMs))
            appendLine("Restarts: ${snapshot.restartCount}  • stalls: ${snapshot.stallCount}  • viewport changes: ${snapshot.viewportChangeCount}")
            appendLine("Quality: ${snapshot.quality.name}")
            snapshot.runtime.lastStopReason?.let { appendLine("Last stop: ${it.name}") }
            snapshot.runtime.lastFailureReason?.let { appendLine("Last failure: $it") }
            appendLine("Diagnostic memory safety: ${snapshot.memorySafeForDiagnostics}")
        }
    }

    private fun toJson(snapshot: CaptureSessionDiagnosticsSnapshot) = JSONObject().apply {
        put("sessionId", snapshot.sessionId)
        put("active", snapshot.runtime.active)
        put("sessionStarted", snapshot.capture.sessionStarted)
        put("startCount", snapshot.runtime.startCount)
        put("restartCount", snapshot.runtime.restartCount)
        put("stallCount", snapshot.runtime.stallCount)
        put("viewportChangeCount", snapshot.runtime.viewportChangeCount)
        put("lastStopReason", snapshot.runtime.lastStopReason?.name)
        put("lastFailureReason", snapshot.runtime.lastFailureReason)
        put("totalFrames", snapshot.capture.totalFrames)
        put("acceptedFrames", snapshot.capture.acceptedFrames)
        put("droppedFrames", snapshot.capture.droppedFrames)
        put("staleFrames", snapshot.capture.staleFrames)
        put("processedFrames", snapshot.capture.processedFrames)
        put("viewportResets", snapshot.capture.viewportResets)
        put("lastFrameGapMs", snapshot.capture.lastFrameGapMs)
        put("maxFrameGapMs", snapshot.capture.maxFrameGapMs)
        put("lastProcessingMs", snapshot.capture.lastProcessingMs)
        put("maxProcessingMs", snapshot.capture.maxProcessingMs)
        put("averageProcessingMs", snapshot.capture.averageProcessingMs)
        put("lastFrameAtMs", snapshot.capture.lastFrameAtMs)
        put("sessionDurationMs", snapshot.capture.sessionDurationMs)
        put("latencyFrames", snapshot.latency.frames)
        put("lastOcrMs", snapshot.latency.lastOcrMs)
        put("lastScannerMs", snapshot.latency.lastScannerMs)
        put("lastTotalMs", snapshot.latency.lastTotalMs)
        put("maxOcrMs", snapshot.latency.maxOcrMs)
        put("maxScannerMs", snapshot.latency.maxScannerMs)
        put("maxTotalMs", snapshot.latency.maxTotalMs)
        put("averageOcrMs", snapshot.latency.averageOcrMs)
        put("averageScannerMs", snapshot.latency.averageScannerMs)
        put("averageTotalMs", snapshot.latency.averageTotalMs)
        put("quality", snapshot.quality.name)
    }

    private fun fromJson(json: JSONObject): CaptureSessionDiagnosticsSnapshot {
        val runtime = CaptureRuntimeSnapshot(
            sessionId = json.optLong("sessionId"),
            active = json.optBoolean("active"),
            startCount = json.optLong("startCount"),
            restartCount = json.optLong("restartCount"),
            stallCount = json.optLong("stallCount"),
            viewportChangeCount = json.optLong("viewportChangeCount"),
            lastStopReason = json.optString("lastStopReason").takeIf { it.isNotBlank() }?.let(CaptureStopReason::valueOf),
            lastFailureReason = json.optString("lastFailureReason").takeIf { it.isNotBlank() }
        )
        val capture = CaptureHealthSnapshot(
            sessionStarted = json.optBoolean("sessionStarted", json.optBoolean("active")),
            totalFrames = json.optLong("totalFrames"),
            acceptedFrames = json.optLong("acceptedFrames"),
            droppedFrames = json.optLong("droppedFrames"),
            staleFrames = json.optLong("staleFrames"),
            processedFrames = json.optLong("processedFrames"),
            viewportResets = json.optLong("viewportResets"),
            lastFrameGapMs = json.optLongOrNull("lastFrameGapMs"),
            maxFrameGapMs = json.optLong("maxFrameGapMs"),
            lastProcessingMs = json.optLongOrNull("lastProcessingMs"),
            maxProcessingMs = json.optLong("maxProcessingMs"),
            averageProcessingMs = json.optDouble("averageProcessingMs"),
            lastFrameAtMs = json.optLongOrNull("lastFrameAtMs"),
            sessionDurationMs = json.optLong("sessionDurationMs")
        )
        val latency = ProcessingLatencySnapshot(
            frames = json.optLong("latencyFrames"),
            lastOcrMs = json.optLongOrNull("lastOcrMs"),
            lastScannerMs = json.optLongOrNull("lastScannerMs"),
            lastTotalMs = json.optLongOrNull("lastTotalMs"),
            maxOcrMs = json.optLong("maxOcrMs"),
            maxScannerMs = json.optLong("maxScannerMs"),
            maxTotalMs = json.optLong("maxTotalMs"),
            averageOcrMs = json.optDouble("averageOcrMs"),
            averageScannerMs = json.optDouble("averageScannerMs"),
            averageTotalMs = json.optDouble("averageTotalMs")
        )
        return CaptureSessionDiagnosticsSnapshot(
            capture = capture,
            runtime = runtime,
            latency = latency,
            quality = CaptureQuality.valueOf(json.optString("quality"))
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (has(key) && !isNull(key)) optLong(key) else null

    companion object {
        private const val KEY_SNAPSHOT = "snapshot"
    }
}
