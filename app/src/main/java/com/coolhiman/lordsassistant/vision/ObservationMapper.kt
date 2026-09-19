package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind

object ObservationMapper {
    fun map(candidate: FusionCandidate): MapObservation {
        val kind = candidate.classification.kind ?: when (candidate.tile.tileClass) {
            TileClass.RESOURCE -> TargetKind.RESOURCE
            TileClass.MONSTER -> TargetKind.MONSTER
        }
        val label = candidate.classification.resource?.name ?: candidate.tile.label
        return MapObservation(
            coordinate = candidate.coordinate,
            screenPoint = ScreenPoint(candidate.tile.centerX, candidate.tile.centerY),
            label = label,
            level = candidate.classification.level ?: candidate.tile.level,
            quantity = candidate.classification.quantity,
            occupied = candidate.occupied,
            incomingTroops = candidate.incomingTroops,
            kind = kind,
            confidence = candidate.confidence.toFloat().coerceIn(0f, 1f)
        )
    }
}
