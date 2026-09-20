package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Stabilizes world-map observations across frames.
 *
 * Occupancy is treated as stateful evidence: once a node is observed occupied,
 * a single "free" frame cannot immediately clear it. This prevents transient
 * OCR/color misses from causing a target to become eligible while troops are
 * still moving toward or gathering at the node.
 */
class TemporalObservationTracker(
    private val confirmHits: Int = 2,
    private val maxGapMs: Long = 2500L
) {
    private data class Track(
        var observation: MapObservation,
        var hits: Int,
        var freeHits: Int,
        var occupiedHits: Int,
        var lastSeen: Long
    )

    private val tracks = ConcurrentHashMap<WorldCoordinate, Track>()

    fun update(
        observations: List<MapObservation>,
        nowMs: Long = System.currentTimeMillis()
    ): List<MapObservation> {
        val output = ArrayList<MapObservation>()

        for (observation in observations) {
            val key = observation.coordinate ?: continue
            val old = tracks[key]
            val strongOccupied = observation.occupied == true ||
                observation.incomingTroops == true

            val track = if (old == null || nowMs - old.lastSeen > maxGapMs) {
                Track(
                    observation = observation,
                    hits = 1,
                    freeHits = if (observation.occupied == false && observation.incomingTroops != true) 1 else 0,
                    occupiedHits = if (strongOccupied) 1 else 0,
                    lastSeen = nowMs
                ).also { tracks[key] = it }
            } else {
                val consistent = old.observation.kind == observation.kind &&
                    old.observation.level == observation.level

                if (consistent) {
                    old.hits = old.hits + 1
                } else {
                    old.hits = max(1, old.hits - 1)
                }

                if (observation.occupied == false && observation.incomingTroops != true) {
                    old.freeHits = old.freeHits + 1
                } else {
                    old.freeHits = 0
                }

                if (strongOccupied) {
                    old.occupiedHits = old.occupiedHits + 1
                } else {
                    old.occupiedHits = 0
                }

                val wasOccupied = old.observation.occupied == true ||
                    old.observation.incomingTroops == true

                // A free transition needs repeated free evidence if the node
                // was previously occupied/incoming. Occupied evidence may
                // become authoritative immediately.
                val acceptFreeTransition = !wasOccupied ||
                    old.freeHits >= confirmHits

                val candidate = if (observation.occupied == false && observation.incomingTroops != true &&
                    !acceptFreeTransition) {
                    old.observation.copy(
                        confidence = max(old.observation.confidence, observation.confidence)
                    )
                } else if (observation.confidence >= old.observation.confidence) {
                    observation
                } else {
                    old.observation
                }

                old.observation = if (strongOccupied) {
                    observation
                } else {
                    candidate
                }
                old.lastSeen = nowMs
                old
            }

            val occupiedState = track.observation.occupied == true ||
                track.observation.incomingTroops == true

            if (track.hits >= confirmHits || occupiedState || track.freeHits >= confirmHits) {
                output += track.observation
            }
        }

        tracks.entries.removeIf { nowMs - it.value.lastSeen > maxGapMs * 4 }
        return output
    }
}
