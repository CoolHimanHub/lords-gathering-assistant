package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.UserPreferences
import kotlin.math.hypot

data class RankedTarget(val tile: ResourceTile, val score: Double)

object TargetRanker {
    fun rank(
        originX: Int,
        originY: Int,
        candidates: List<ResourceTile>,
        preferences: UserPreferences
    ): List<RankedTarget> {
        return candidates
            .filter { TargetSelector.accepts(it, preferences) }
            .map { tile ->
                val distance = hypot(
                    (tile.coordinate.x - originX).toDouble(),
                    (tile.coordinate.y - originY).toDouble()
                )
                val levelWeight = tile.level * 100.0
                val quantityWeight = (tile.remaining ?: 0L).coerceAtMost(5_000_000L) / 50_000.0
                RankedTarget(tile, levelWeight + quantityWeight - distance * 10.0)
            }
            .sortedByDescending { it.score }
    }
}
