package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind

object ObservationMapper {
    fun map(candidate: FusionCandidate): MapObservation {
        val kind = when {
            candidate.ignored -> null
            candidate.classification.kind != null -> candidate.classification.kind
            else -> when (candidate.tile.tileClass) {
                TileClass.RESOURCE -> TargetKind.RESOURCE
                TileClass.MONSTER -> TargetKind.MONSTER
            }
        }
        val label = candidate.classification.resource?.name ?: candidate.classification.monsterName ?: candidate.tile.label
        val evidence = buildSet {
            if (candidate.classification.kind != null || candidate.classification.resource != null || candidate.classification.level != null) add(ObservationEvidence.OCR_CONFIRMED)
            if (candidate.incomingTroops == true) add(ObservationEvidence.MARCH_CONFIRMED)
            if (candidate.occupied == null || candidate.incomingTroops == null) add(ObservationEvidence.STATE_UNKNOWN)
        }
        return MapObservation(
            coordinate = candidate.coordinate,
            screenPoint = ScreenPoint(candidate.tile.centerX, candidate.tile.centerY),
            label = label,
            level = candidate.classification.level ?: candidate.tile.level,
            quantity = candidate.classification.quantity,
            occupied = candidate.occupied,
            incomingTroops = candidate.incomingTroops,
            kind = kind,
            confidence = candidate.confidence.toFloat().coerceIn(0f, 1f),
            evidence = evidence,
            coordinateConfidence = candidate.coordinateConfidence
        )
    }
}
