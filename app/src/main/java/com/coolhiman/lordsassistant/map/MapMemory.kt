package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import java.util.concurrent.ConcurrentHashMap

/**
 * Fast world-map cache. Low-confidence observations are retained briefly and
 * stale entries can be removed before target selection.
 */
class MapMemory(
    private val staleAfterMs: Long = 15_000L
) {
    private val observations = ConcurrentHashMap<WorldCoordinate, MapObservation>()

    fun upsert(observation: MapObservation) {
        val key = observation.coordinate ?: return
        val previous = observations[key]
        if (previous == null ||
            observation.confidence >= previous.confidence ||
            observation.timestampMs - previous.timestampMs > 5_000
        ) {
            observations[key] = observation
        }
    }

    fun upsertAll(items: Iterable<MapObservation>) {
        items.forEach(::upsert)
    }

    fun get(coordinate: WorldCoordinate): MapObservation? = observations[coordinate]

    fun purgeStale(nowMs: Long = System.currentTimeMillis()): Int {
        var removed = 0
        observations.entries.removeIf {
            val stale = nowMs - it.value.timestampMs > staleAfterMs
            if (stale) removed++
            stale
        }
        return removed
    }

    fun snapshot(nowMs: Long = System.currentTimeMillis(), purgeStale: Boolean = true): List<MapObservation> {
        if (purgeStale) purgeStale(nowMs)
        return observations.values.toList()
    }

    fun nearby(center: WorldCoordinate, radius: Int): List<MapObservation> =
        snapshot().filter {
            val c = it.coordinate ?: return@filter false
            kotlin.math.abs(c.x - center.x) <= radius &&
                kotlin.math.abs(c.y - center.y) <= radius &&
                c.kingdom == center.kingdom
        }

    fun size(): Int = observations.size
}
