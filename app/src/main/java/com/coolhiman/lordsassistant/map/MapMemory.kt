package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast in-memory world map cache.
 * Persistence can be added later once observation quality is stable.
 */
class MapMemory {
    private val observations = ConcurrentHashMap<WorldCoordinate, MapObservation>()

    fun upsert(observation: MapObservation) {
        val key = observation.coordinate ?: return
        val previous = observations[key]
        if (previous == null || observation.confidence >= previous.confidence ||
            observation.timestampMs - previous.timestampMs > 5_000
        ) {
            observations[key] = observation
        }
    }

    fun get(coordinate: WorldCoordinate): MapObservation? = observations[coordinate]

    fun snapshot(): List<MapObservation> = observations.values.toList()

    fun nearby(center: WorldCoordinate, radius: Int): List<MapObservation> =
        observations.values.filter {
            val c = it.coordinate ?: return@filter false
            kotlin.math.abs(c.x - center.x) <= radius &&
                kotlin.math.abs(c.y - center.y) <= radius &&
                c.kingdom == center.kingdom
        }
}
