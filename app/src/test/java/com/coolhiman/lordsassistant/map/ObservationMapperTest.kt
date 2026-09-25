package com.coolhiman.lordsassistant.map

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.CoordinateAuthority
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.DetectedTile
import com.coolhiman.lordsassistant.vision.FusionCandidate
import com.coolhiman.lordsassistant.vision.TextClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservationMapperTest {
    @Test
    fun preservesMonsterNameForTemporalIdentity() {
        val candidate = FusionCandidate(
            tile = DetectedTile(
                "MONSTER",
                com.coolhiman.lordsassistant.vision.TileClass.MONSTER,
                4,
                RectF(100f, 100f, 140f, 140f),
                0.9
            ),
            classification = TextClassification(
                kind = TargetKind.MONSTER,
                monsterName = "Blackwing",
                level = 4
            ),
            coordinate = WorldCoordinate(1, 200, 300),
            occupied = false,
            incomingTroops = false,
            confidence = 0.95
        )

        val observation = ObservationMapper.map(candidate)

        assertEquals("Blackwing", observation.label)
        assertEquals(TargetKind.MONSTER, observation.kind)
        assertEquals(4, observation.level)
    }

    @Test
    fun preservesCoordinateProvenanceForActionAuthority() {
        val candidate = FusionCandidate(
            tile = DetectedTile(
                "WOOD",
                com.coolhiman.lordsassistant.vision.TileClass.RESOURCE,
                3,
                RectF(100f, 100f, 140f, 140f),
                0.9
            ),
            classification = TextClassification(
                kind = TargetKind.RESOURCE,
                resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
                level = 3
            ),
            coordinate = WorldCoordinate(1, 200, 300),
            occupied = false,
            incomingTroops = false,
            confidence = 0.98,
            coordinateConfidence = CoordinateConfidence.observed(true, true, residualPx = 2.5)
        )

        val observation = ObservationMapper.map(candidate)

        assertEquals(CoordinateAuthority.OBSERVED, observation.coordinateConfidence.authority)
        assertEquals(2.5, observation.coordinateConfidence.residualPx)
        assertTrue(observation.coordinateConfidence.actionAuthoritative)
    }
}
