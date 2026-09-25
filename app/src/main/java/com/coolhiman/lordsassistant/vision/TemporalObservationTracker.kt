package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.WorldCoordinate
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.hypot

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
    private val maxGapMs: Long = 2500L,
    private val maxScreenMatchDistancePx: Float = 90f
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
            var old = tracks[key]

            // Coordinate calibration can drift while the same visible node
            // remains stationary. Re-associate by semantic identity + screen
            // proximity before creating a new temporal track.
            if (old == null && observation.screenPoint != null) {
                val match = tracks.entries
                    .asSequence()
                    .filter { (_, track) ->
                        nowMs - track.lastSeen <= maxGapMs &&
                            track.observation.kind == observation.kind &&
                            track.observation.level == observation.level &&
                            track.observation.screenPoint != null
                    }
                    .map { entry ->
                        val point = entry.value.observation.screenPoint!!
                        val distance = hypot(
                            (observation.screenPoint.x - point.x).toDouble(),
                            (observation.screenPoint.y - point.y).toDouble()
                        ).toFloat()
                        entry to distance
                    }
                    .filter { it.second <= maxScreenMatchDistancePx }
                    .minByOrNull { it.second }
                if (match != null) {
                    tracks.remove(match.first.key)
                    old = match.first.value
                    tracks[key] = old
                }
            }
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
                        confidence = max(old.observation.confidence, observation.confidence),
                        screenPoint = observation.screenPoint,
                        timestampMs = observation.timestampMs,
                        coordinateConfidence = observation.coordinateConfidence
                    )
                } else if (observation.confidence >= old.observation.confidence) {
                    observation
                } else {
                    // State continuity may keep the older semantic observation,
                    // but coordinate provenance is always frame-local. Never
                    // carry OBSERVED authority into a later calibrated/unknown
                    // frame merely because the world identity stayed equal.
                    old.observation.copy(
                        screenPoint = observation.screenPoint,
                        timestampMs = observation.timestampMs,
                        coordinateConfidence = observation.coordinateConfidence
                    )
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
                val evidence = track.observation.evidence + ObservationEvidence.TEMPORALLY_CONFIRMED
                output += track.observation.copy(evidence = evidence)
            }
        }

        tracks.entries.removeIf { nowMs - it.value.lastSeen > maxGapMs * 4 }
        return output
    }
    fun reset() {
        tracks.clear()
    }
}
