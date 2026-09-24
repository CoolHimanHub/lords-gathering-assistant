package com.coolhiman.lordsassistant.map

import android.content.Context
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Durable active-learning log for map probes.
 *
 * A probe is a user-started, map-only tap followed by OCR of the game's tile
 * popup. We keep both the requested/expected coordinate and the coordinate
 * actually reported by the popup so grid errors are measurable instead of
 * silently corrected.
 */
data class GridLearningRecord(
    val timestampMs: Long,
    val screenX: Float,
    val screenY: Float,
    val expected: WorldCoordinate?,
    val actual: WorldCoordinate?,
    val kind: String?,
    val resource: String?,
    val monsterName: String?,
    val level: Int?,
    val quantity: Long?,
    val occupied: Boolean?,
    val incomingTroops: Boolean?,
    val popup: Boolean,
    val coordinateDelta: Int?,
    val acceptedForCalibration: Boolean
)

class GridLearningStore(context: Context) {
    private val root = File(context.filesDir, "grid_learning").apply { mkdirs() }
    private val logFile = File(root, "tile_probes.jsonl")
    private val count = AtomicLong(if (logFile.exists()) {
        runCatching { logFile.useLines { lines -> lines.count { it.isNotBlank() }.toLong() } }.getOrDefault(0L)
    } else 0L)

    @Synchronized
    fun append(record: GridLearningRecord) {
        val o = JSONObject().apply {
            put("timestampMs", record.timestampMs)
            put("screenX", record.screenX)
            put("screenY", record.screenY)
            record.expected?.let {
                put("expectedK", it.kingdom); put("expectedX", it.x); put("expectedY", it.y)
            }
            record.actual?.let {
                put("actualK", it.kingdom); put("actualX", it.x); put("actualY", it.y)
            }
            record.kind?.let { put("kind", it) }
            record.resource?.let { put("resource", it) }
            record.monsterName?.let { put("monsterName", it) }
            record.level?.let { put("level", it) }
            record.quantity?.let { put("quantity", it) }
            record.occupied?.let { put("occupied", it) }
            record.incomingTroops?.let { put("incomingTroops", it) }
            put("popup", record.popup)
            record.coordinateDelta?.let { put("coordinateDelta", it) }
            put("acceptedForCalibration", record.acceptedForCalibration)
        }
        logFile.appendText(o.toString() + "\n")
        count.incrementAndGet()
    }

    fun sampleCount(): Long = count.get()

    fun file(): File = logFile

    fun clear() {
        synchronized(this) {
            if (logFile.exists()) logFile.writeText("")
            count.set(0L)
        }
    }
}
