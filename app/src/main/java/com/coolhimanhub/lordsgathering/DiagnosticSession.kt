package com.coolhimanhub.lordsgathering

import android.graphics.Bitmap
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Lightweight opt-in diagnostics for scan sessions. No network activity. */
class DiagnosticSession(private val root: File) {
    private var active = false
    private var sessionDir: File? = null
    private val scanCount = AtomicInteger(0)
    private val detectionCount = AtomicInteger(0)
    private val candidateCount = AtomicInteger(0)
    private val unknownCount = AtomicInteger(0)

    fun start() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        sessionDir = File(root, "diagnostics/$stamp").also { it.mkdirs() }
        active = true
        append("session_start=${SystemClock.elapsedRealtime()}\n")
    }

    fun recordScan(detections: List<ScreenAnalyzer.RssDetection>) {
        if (!active) return
        scanCount.incrementAndGet()
        detectionCount.addAndGet(detections.size)
        candidateCount.addAndGet(detections.count { it.confidence >= 68 && it.type != "unknown" })
        unknownCount.addAndGet(detections.count { it.type == "unknown" })
        val lines = detections.mapIndexed { i, d ->
            "tile=$i,type=${d.type},level=${d.level},x=${d.centerX},y=${d.centerY},confidence=${d.confidence},occupied=${d.occupied},moving=${d.moving},movingScore=${d.movingScore}"
        }
        append("scan=${scanCount.get()}\n${lines.joinToString("\n")}\n")
    }

    fun saveRepresentativeScreenshot(bitmap: Bitmap) {
        if (!active) return
        val dir = File(sessionDir ?: return, "screenshots").also { it.mkdirs() }
        val file = File(dir, "scan_${scanCount.get().toString().padStart(4, '0')}.jpg")
        if (file.exists()) return
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, it) }
    }

    fun summary(): String = "scans=${scanCount.get()} detections=${detectionCount.get()} candidates=${candidateCount.get()} unknown=${unknownCount.get()}"

    fun stop(): File? {
        if (!active) return sessionDir
        append("session_end=${SystemClock.elapsedRealtime()}\n$summary\n")
        active = false
        return sessionDir
    }

    private fun append(text: String) {
        File(sessionDir ?: return, "events.log").appendText(text)
    }
}
