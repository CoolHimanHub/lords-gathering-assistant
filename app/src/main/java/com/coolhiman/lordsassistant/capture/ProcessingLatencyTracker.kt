package com.coolhiman.lordsassistant.capture

data class ProcessingLatencySnapshot(
    val frames: Long,
    val lastOcrMs: Long?,
    val lastScannerMs: Long?,
    val lastTotalMs: Long?,
    val maxOcrMs: Long,
    val maxScannerMs: Long,
    val maxTotalMs: Long,
    val averageOcrMs: Double,
    val averageScannerMs: Double,
    val averageTotalMs: Double
)

class ProcessingLatencyTracker {
    private var frames = 0L
    private var lastOcrMs: Long? = null
    private var lastScannerMs: Long? = null
    private var lastTotalMs: Long? = null
    private var maxOcrMs = 0L
    private var maxScannerMs = 0L
    private var maxTotalMs = 0L
    private var totalOcrMs = 0L
    private var totalScannerMs = 0L
    private var totalTotalMs = 0L

    fun record(ocrMs: Long, scannerMs: Long, totalMs: Long) {
        val ocr = ocrMs.coerceAtLeast(0L)
        val scanner = scannerMs.coerceAtLeast(0L)
        val total = totalMs.coerceAtLeast(0L)
        frames++
        lastOcrMs = ocr
        lastScannerMs = scanner
        lastTotalMs = total
        maxOcrMs = maxOf(maxOcrMs, ocr)
        maxScannerMs = maxOf(maxScannerMs, scanner)
        maxTotalMs = maxOf(maxTotalMs, total)
        totalOcrMs += ocr
        totalScannerMs += scanner
        totalTotalMs += total
    }

    fun snapshot(): ProcessingLatencySnapshot = ProcessingLatencySnapshot(
        frames = frames,
        lastOcrMs = lastOcrMs,
        lastScannerMs = lastScannerMs,
        lastTotalMs = lastTotalMs,
        maxOcrMs = maxOcrMs,
        maxScannerMs = maxScannerMs,
        maxTotalMs = maxTotalMs,
        averageOcrMs = if (frames == 0L) 0.0 else totalOcrMs.toDouble() / frames,
        averageScannerMs = if (frames == 0L) 0.0 else totalScannerMs.toDouble() / frames,
        averageTotalMs = if (frames == 0L) 0.0 else totalTotalMs.toDouble() / frames
    )

    fun reset() {
        frames = 0L
        lastOcrMs = null
        lastScannerMs = null
        lastTotalMs = null
        maxOcrMs = 0L
        maxScannerMs = 0L
        maxTotalMs = 0L
        totalOcrMs = 0L
        totalScannerMs = 0L
        totalTotalMs = 0L
    }
}
