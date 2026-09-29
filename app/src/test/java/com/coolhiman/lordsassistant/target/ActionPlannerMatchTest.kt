package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ObservationEvidence
import com.coolhiman.lordsassistant.model.ResourceTile
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionPlannerMatchTest {
    private fun observation(label: String) = MapObservation(
        coordinate = WorldCoordinate(1, 200, 300),
        screenPoint = ScreenPoint(120f, 120f),
        label = label,
        level = 3,
        quantity = null,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = 0.95f,
        evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
    )

    @Test
    fun resourcePlannerMatchRejectsSemanticDriftAtSameCoordinateAndLevel() {
        val ranked = RankedTarget(
            ResourceTile(
                WorldCoordinate(1, 200, 300),
                ResourceType.WOOD,
                3,
                null,
                false,
                false,
                120f,
                120f
            ),
            123.0
        )

        assertTrue(ActionPlannerMatch.matchesResource(observation("WOOD"), ranked))
        assertFalse(ActionPlannerMatch.matchesResource(observation("STONE"), ranked))
    }

    @Test
    fun currentAndPlannedIdentityMustBothRemainConcreteAndEqual() {
        assertTrue(ActionPlannerMatch.sameSemanticIdentity(observation("WOOD"), observation("WOOD")))
        assertFalse(ActionPlannerMatch.sameSemanticIdentity(observation("WOOD"), observation("STONE")))
        assertFalse(ActionPlannerMatch.sameSemanticIdentity(observation("WOOD"), observation(" ")))
    }
}
