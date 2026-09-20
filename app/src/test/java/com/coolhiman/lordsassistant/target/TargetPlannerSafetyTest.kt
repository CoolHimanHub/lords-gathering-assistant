package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.UserPreferences
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetPlannerSafetyTest {
    private val planner = TargetPlanner(minimumConfidence = 0.5f)
    private val prefs = UserPreferences(
        resourceTypes = setOf(ResourceType.WOOD),
        resourceLevels = 1..5
    )

    @Test
    fun unknownOccupancyIsNotEligible() {
        val observation = MapObservation(
            coordinate = WorldCoordinate(1, 100, 100),
            screenPoint = null,
            label = ResourceType.WOOD.name,
            level = 3,
            quantity = 100000L,
            occupied = null,
            incomingTroops = null,
            kind = TargetKind.RESOURCE,
            confidence = 0.95f
        )

        val plan = planner.plan(90, 90, listOf(observation), prefs)
        assertEquals(0, plan.ranked.size)
    }

    @Test
    fun explicitlyFreeObservationIsEligible() {
        val observation = MapObservation(
            coordinate = WorldCoordinate(1, 100, 100),
            screenPoint = null,
            label = ResourceType.WOOD.name,
            level = 3,
            quantity = 100000L,
            occupied = false,
            incomingTroops = false,
            kind = TargetKind.RESOURCE,
            confidence = 0.95f
        )

        val plan = planner.plan(90, 90, listOf(observation), prefs)
        assertEquals(1, plan.ranked.size)
    }
}


    @Test
    fun safeMonsterIsRankedWhenLevelConfigured() {
        val planner = TargetPlanner()
        val observation = MapObservation(
            coordinate = WorldCoordinate(1, 100, 100),
            screenPoint = ScreenPoint(500f, 500f),
            label = "Frostwing",
            level = 3,
            quantity = null,
            occupied = false,
            incomingTroops = false,
            kind = TargetKind.MONSTER,
            confidence = 0.90f,
            evidence = setOf(ObservationEvidence.TEMPORALLY_CONFIRMED)
        )
        val plan = planner.plan(90, 90, listOf(observation), UserPreferences(monsterLevels = setOf(3)))
        assertEquals(1, plan.rankedMonsters.size)
        assertEquals(3, plan.rankedMonsters.first().target.level)
    }

    @Test
    fun unknownMonsterStateIsNotRanked() {
        val planner = TargetPlanner()
        val observation = MapObservation(
            coordinate = WorldCoordinate(1, 100, 100),
            screenPoint = ScreenPoint(500f, 500f),
            label = "Frostwing",
            level = 3,
            quantity = null,
            occupied = null,
            incomingTroops = null,
            kind = TargetKind.MONSTER,
            confidence = 0.90f
        )
        val plan = planner.plan(90, 90, listOf(observation), UserPreferences(monsterLevels = setOf(3)))
        assertTrue(plan.rankedMonsters.isEmpty())
    }
