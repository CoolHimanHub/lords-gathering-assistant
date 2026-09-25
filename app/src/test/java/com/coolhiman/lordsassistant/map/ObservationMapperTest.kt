package com.coolhiman.lordsassistant.map

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.DetectedTile
import com.coolhiman.lordsassistant.vision.FusionCandidate
import com.coolhiman.lordsassistant.vision.TextClassification
import org.junit.Assert.assertEquals
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
}
