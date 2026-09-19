package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Stabilizes world-map observations across frames. A new coordinate normally
 * needs two consistent sightings before it is surfaced to planning; a strong
 * popup observation is allowed through immediately.
 */
class TemporalObservationTracker(
    private val confirmHits: Int = 2,
    private val maxGapMs: Long = 2500L
) {
    private data class Track(
        var observation: MapObservation,
        var hits: Int,
        var lastSeen: Long
    )

    private val tracks = ConcurrentHashMap<WorldCoordinate, Track>()

    fun update(observations: List<MapObservation>, nowMs: Long = System.currentTimeMillis()): List<MapObservation> {
        val output = ArrayList<MapObservation>()
        for (observation in observations) {
            val key = observation.coordinate ?: continue
            val old = tracks[key]
            val strong = observation.confidence >= 0.92f ||
                observation.occupied == true ||
                observation.incomingTroops == true

            val track = if (old == null || nowMs - old.lastSeen > maxGapMs) {
                Track(observation, 1, nowMs).also { tracks[key] = it }
            } else {
                val consistent = old.observation.kind == observation.kind &&
                    old.observation.level == observation.level
                old.observation = if (observation.confidence >= old.observation.confidence) observation else old.observation
                old.hits = if (consistent) old.hits + 1 else max(1, old.hits - 1)
                old.lastSeen = nowMs
                old
            }

            if (track.hits >= confirmHits || strong) {
                output += track.observation
            }
        }
        tracks.entries.removeIf { nowMs - it.value.lastSeen > maxGapMs * 4 }
        return output
    }
}
