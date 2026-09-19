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
        val usedTrackIndexes = mutableSetOf<Int>()

        signals.forEach { signal ->
            var bestIndex = -1
            var bestDistance = Float.MAX_VALUE

            tracks.forEachIndexed { index, track ->
                if (index in usedTrackIndexes) return@forEachIndexed
                val distance = hypot(
                    (track.signal.x - signal.x).toDouble(),
                    (track.signal.y - signal.y).toDouble()
                ).toFloat()
                if (distance < bestDistance) {
                    bestDistance = distance
                    bestIndex = index
                }
            }

            val updated = if (bestIndex >= 0 && bestDistance <= maxDistancePx) {
                usedTrackIndexes += bestIndex
                tracks[bestIndex].signal = signal
                tracks[bestIndex].hits += 1
                tracks[bestIndex].lastSeenMs = nowMs
                tracks[bestIndex]
            } else {
                val newTrack = Track(signal, 1, nowMs)
                tracks += newTrack
                usedTrackIndexes += tracks.lastIndex
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
