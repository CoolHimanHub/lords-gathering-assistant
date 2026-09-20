package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.MonsterTarget
import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.UserPreferences
import kotlin.math.hypot

data class TargetPlan(
    val observations: List<MapObservation>,
    val ranked: List<RankedTarget>,
    val rankedMonsters: List<RankedMonsterTarget> = emptyList()
)

data class RankedMonsterTarget(val target: MonsterTarget, val score: Double)

class TargetPlanner(
    private val minimumConfidence: Float = 0.72f
) {
    fun plan(
        originX: Int,
        originY: Int,
        observations: List<MapObservation>,
        preferences: UserPreferences,
        cameraStable: Boolean = true
    ): TargetPlan {
        if (!cameraStable) return TargetPlan(observations, emptyList(), emptyList())

        val eligible = observations.filter { o ->
            o.coordinate != null &&
                o.level != null &&
                o.occupied == false &&
                o.incomingTroops == false &&
                o.confidence >= minimumConfidence
        }

        val resources = eligible.filter { it.kind == TargetKind.RESOURCE }
        val resourceTiles = resources.mapNotNull { o ->
            val c = o.coordinate ?: return@mapNotNull null
            val type = o.label?.let { runCatching { ResourceType.valueOf(it) }.getOrNull() } ?: return@mapNotNull null
            ResourceTile(
                c, type, o.level ?: return@mapNotNull null, o.quantity,
                false, false, o.screenPoint?.x ?: 0f, o.screenPoint?.y ?: 0f
            )
        }
        val rankedResources = TargetRanker.rank(originX, originY, resourceTiles, preferences)

        val monsters = eligible.filter { it.kind == TargetKind.MONSTER && (it.level ?: 0) in preferences.monsterLevels }
        val rankedMonsters = monsters.mapNotNull { o ->
            val c = o.coordinate ?: return@mapNotNull null
            val level = o.level ?: return@mapNotNull null
            val name = o.label?.takeIf { it.isNotBlank() } ?: "MONSTER"
            val distance = hypot((c.x - originX).toDouble(), (c.y - originY).toDouble())
            val levelWeight = level * 100.0
            RankedMonsterTarget(
                MonsterTarget(c, name, level, o.screenPoint?.x ?: 0f, o.screenPoint?.y ?: 0f),
                levelWeight - distance * 10.0
            )
        }.sortedByDescending { it.score }

        return TargetPlan(eligible, rankedResources, rankedMonsters)
    }
}
