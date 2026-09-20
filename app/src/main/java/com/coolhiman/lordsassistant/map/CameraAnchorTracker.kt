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
    private val maxAssociationDistancePx: Float = 180f
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
                val nearest = unmatched.minByOrNull {
                    hypot(
                        (screen.x - it.screen.x).toDouble(),
                        (screen.y - it.screen.y).toDouble()
                    )
                } ?: continue
                val distance = hypot(
                    (screen.x - nearest.screen.x).toDouble(),
                    (screen.y - nearest.screen.y).toDouble()
                ).toFloat()
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
