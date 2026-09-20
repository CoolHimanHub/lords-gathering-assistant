package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation

/**
 * Associates stable semantic observations across frames without trusting the
 * transient world coordinate produced by the current camera calibration.
 *
 * Only unique semantic keys are promoted to camera anchors. Ambiguous
 * duplicate resources/monsters remain unused so they cannot distort camera
 * estimation.
 */
class CameraAnchorTracker(
    private val maxAnchors: Int = 16
) {
    private var previous = emptyMap<String, CameraWorldAnchor>()

    fun update(observations: List<MapObservation>): List<CameraWorldAnchor> {
        val grouped = observations
            .filter { it.coordinate != null && it.screenPoint != null }
            .groupBy { semanticKey(it) }

        val current = grouped.mapNotNull { (key, values) ->
            if (values.size != 1) return@mapNotNull null
            val observation = values.single()
            val world = observation.coordinate ?: return@mapNotNull null
            val screen = observation.screenPoint ?: return@mapNotNull null
            key to CameraWorldAnchor(world, screen)
        }.toMap()

        val shared = current.keys.intersect(previous.keys)
            .mapNotNull { key ->
                val old = previous[key] ?: return@mapNotNull null
                val now = current[key] ?: return@mapNotNull null
                CameraWorldAnchor(old.world, now.screen)
            }
            .take(maxAnchors)

        previous = current
        return shared
    }

    fun reset() {
        previous = emptyMap()
    }

    private fun semanticKey(observation: MapObservation): String =
        "${'$'}{observation.kind}|${'$'}{observation.level}|${'$'}{observation.label ?: ""}"
}
