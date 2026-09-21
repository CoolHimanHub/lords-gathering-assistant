package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import kotlin.math.hypot

/**
 * Associates semantic observations across frames for camera estimation.
 *
 * Repeated labels are supported through one-to-one nearest-neighbor matching
 * inside each semantic group. A current observation inherits the previous
 * frame's world coordinate, while its current screen point becomes the camera
 * measurement. This avoids using the provisional current-frame coordinate to
 * define its own camera correction.
 */
class CameraAnchorTracker(
    private val maxAnchors: Int = 16,
    private val maxAssociationDistancePx: Float = 180f,
    private val ambiguityMarginPx: Float = 12f
) {
    private data class PreviousAnchor(
        val world: com.coolhiman.lordsassistant.model.WorldCoordinate,
        val screen: com.coolhiman.lordsassistant.model.ScreenPoint
    )

    private var previous = emptyMap<String, List<PreviousAnchor>>()

    fun update(observations: List<MapObservation>): List<CameraWorldAnchor> {
        val grouped = observations
            .filter { it.coordinate != null && it.screenPoint != null }
            .groupBy { semanticKey(it) }

        val shared = mutableListOf<CameraWorldAnchor>()

        for ((key, values) in grouped) {
            val old = previous[key].orEmpty()
            if (old.isEmpty()) continue

            val unmatched = old.toMutableList()
            for (observation in values.sortedBy { it.screenPoint!!.x }) {
                if (unmatched.isEmpty()) break
                val screen = observation.screenPoint ?: continue
                val ranked = unmatched
                    .map { anchor ->
                        anchor to hypot(
                            (screen.x - anchor.screen.x).toDouble(),
                            (screen.y - anchor.screen.y).toDouble()
                        ).toFloat()
                    }
                    .sortedBy { it.second }
                val nearest = ranked.firstOrNull()?.first ?: continue
                val distance = ranked.first().second
                val secondDistance = ranked.getOrNull(1)?.second
                // If two previous anchors are nearly equally plausible, do not
                // guess. A bad identity association can produce a plausible
                // but incorrect camera model and contaminate later frames.
                if (secondDistance != null &&
                    secondDistance - distance < ambiguityMarginPx
                ) {
                    continue
                }
                if (distance <= maxAssociationDistancePx) {
                    shared += CameraWorldAnchor(nearest.world, screen)
                    unmatched.remove(nearest)
                    if (shared.size >= maxAnchors) break
                }
            }
            if (shared.size >= maxAnchors) break
        }

        previous = grouped.mapValues { (_, values) ->
            values.mapNotNull { observation ->
                val world = observation.coordinate ?: return@mapNotNull null
                val screen = observation.screenPoint ?: return@mapNotNull null
                PreviousAnchor(world, screen)
            }
        }
        return shared
    }

    fun reset() {
        previous = emptyMap()
    }

    private fun semanticKey(observation: MapObservation): String =
        "${observation.kind}|${observation.level}|${observation.label ?: ""}"
}
