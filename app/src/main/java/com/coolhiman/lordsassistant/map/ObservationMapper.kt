package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.vision.FusionCandidate

object ObservationMapper {
    fun map(candidate: FusionCandidate): MapObservation = MapObservation(
        coordinate = candidate.coordinate,
        screenPoint = com.coolhiman.lordsassistant.model.ScreenPoint(candidate.tile.centerX, candidate.tile.centerY),
        label = candidate.classification.resource?.name ?: candidate.classification.monsterName ?: candidate.classification.kind?.name,
        level = candidate.classification.level ?: candidate.tile.level,
        quantity = candidate.classification.quantity,
        occupied = candidate.occupied,
        incomingTroops = candidate.incomingTroops,
        kind = candidate.classification.kind,
        confidence = candidate.confidence.toFloat().coerceIn(0f, 1f),
        coordinateConfidence = candidate.coordinateConfidence
    )
}
