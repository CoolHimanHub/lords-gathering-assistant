package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.UserPreferences
import com.coolhiman.lordsassistant.model.ResourceType

data class TargetPlan(
    val observations: List<MapObservation>,
    val ranked: List<RankedTarget>
)

class TargetPlanner(
    private val minimumConfidence: Float = 0.72f
) {
    fun plan(
        originX: Int,
        originY: Int,
        observations: List<MapObservation>,
        preferences: UserPreferences
    ): TargetPlan {
        val eligible = observations.filter { o ->
            o.coordinate != null &&
                o.kind == com.coolhiman.lordsassistant.model.TargetKind.RESOURCE &&
                (o.level ?: 0) in preferences.resourceLevels &&
                o.occupied != true &&
                o.incomingTroops != true &&
                o.confidence >= minimumConfidence
        }
        val tiles = eligible.mapNotNull { o ->
            val c = o.coordinate ?: return@mapNotNull null
            val type = o.label?.let { runCatching { ResourceType.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            val level = o.level ?: return@mapNotNull null
            ResourceTile(c, type, level, o.quantity, false, false, o.screenPoint?.x ?: 0f, o.screenPoint?.y ?: 0f)
        }
        return TargetPlan(eligible, TargetRanker.rank(originX, originY, tiles, preferences))
    }
}
