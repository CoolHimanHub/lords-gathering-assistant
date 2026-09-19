package com.coolhiman.lordsassistant.vision

import kotlin.math.hypot

/**
 * Stabilizes march-path evidence across consecutive frames.
 * A single contour is not enough to mark a resource occupied.
 */
class TemporalMarchSignalTracker(
    private val confirmHits: Int = 2,
    private val maxGapMs: Long = 1_500L,
    private val maxDistancePx: Float = 45f
) {
    private data class Track(
        var signal: MarchSignal,
        var hits: Int,
        var lastSeenMs: Long
    )

    private val tracks = mutableListOf<Track>()

    fun update(signals: List<MarchSignal>, nowMs: Long): List<MarchSignal> {
        tracks.removeAll { nowMs - it.lastSeenMs > maxGapMs }
        val stable = mutableListOf<MarchSignal>()

        signals.forEach { signal ->
            val track = tracks.minByOrNull {
                hypot(
                    (it.signal.x - signal.x).toDouble(),
                    (it.signal.y - signal.y).toDouble()
                )
            }
            val distance = track?.let {
                hypot(
                    (it.signal.x - signal.x).toDouble(),
                    (it.signal.y - signal.y).toDouble()
                ).toFloat()
            }

            val updated = if (track != null && distance != null && distance <= maxDistancePx) {
                track.signal = signal
                track.hits += 1
                track.lastSeenMs = nowMs
                track
            } else {
                val newTrack = Track(signal, 1, nowMs)
                tracks += newTrack
                newTrack
            }

            if (updated.hits >= confirmHits) stable += updated.signal
        }

        return stable.distinctBy { Pair((it.x / 8f).toInt(), (it.y / 8f).toInt()) }
    }

    fun clear() {
        tracks.clear()
    }
}
