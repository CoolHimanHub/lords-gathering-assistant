package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.MonsterTarget
import com.coolhiman.lordsassistant.model.ResourceTile

/**
 * Matches planner provenance to the live observation that may authorize an
 * action. Coordinate/level equality alone is insufficient because semantic
 * identity can change while a world tile remains the same.
 */
object ActionPlannerMatch {
    fun sameSemanticIdentity(first: MapObservation, second: MapObservation): Boolean =
        ActionSemanticIdentity.fromObservation(first) == ActionSemanticIdentity.fromObservation(second)

    fun matchesResource(observation: MapObservation, ranked: RankedTarget): Boolean =
        observation.coordinate == ranked.tile.coordinate &&
            observation.level == ranked.tile.level &&
            ActionSemanticIdentity.fromObservation(observation)
                ?.equals(ranked.tile.type.name, ignoreCase = true) == true

    fun matchesMonster(observation: MapObservation, ranked: RankedMonsterTarget): Boolean =
        observation.coordinate == ranked.target.coordinate &&
            observation.level == ranked.target.level &&
            ActionSemanticIdentity.fromObservation(observation)
                ?.equals(ranked.target.name, ignoreCase = true) == true

    fun matchesMonsterTarget(observation: MapObservation, target: MonsterTarget): Boolean =
        observation.coordinate == target.coordinate &&
            observation.level == target.level &&
            ActionSemanticIdentity.fromObservation(observation)
                ?.equals(target.name, ignoreCase = true) == true
}
