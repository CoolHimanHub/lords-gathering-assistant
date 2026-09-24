package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.UserPreferences
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class TargetPlannerTest {
    @Test
    fun onlyValidatedFreeResourcesBecomeTargets() {
        val good = MapObservation(WorldCoordinate(355,100,100), null, ResourceType.FOOD.name, 5, 500_000, false, false, TargetKind.RESOURCE, 0.95f)
        val occupied = good.copy(coordinate = WorldCoordinate(355,101,100), occupied = true)
        val weak = good.copy(coordinate = WorldCoordinate(355,102,100), confidence = 0.50f)
        val plan = TargetPlanner().plan(100,100,listOf(good,occupied,weak),UserPreferences())
        assertEquals(1, plan.ranked.size)
        assertEquals(100, plan.ranked.first().tile.coordinate.x)
    }
    @Test
    fun discoveryRankingRemainsVisibleWhenCameraIsNotStable() {
        val target = MapObservation(
            WorldCoordinate(355, 101, 100),
            com.coolhiman.lordsassistant.model.ScreenPoint(120f, 120f),
            "RESOURCE_BADGE",
            3,
            null,
            false,
            false,
            TargetKind.RESOURCE,
            0.80f
        )
        val plan = TargetPlanner().plan(
            100,
            100,
            listOf(target),
            UserPreferences(),
            cameraStable = false
        )
        assertEquals(1, plan.rankedDiscoveries.size)
        assertEquals(0, plan.ranked.size)
    }

    

    @Test
    fun discoverySurfacesSemanticTargetBeforeCoordinateCalibration() {
        val target = MapObservation(
            coordinate = null,
            screenPoint = com.coolhiman.lordsassistant.model.ScreenPoint(480f, 320f),
            label = "RESOURCE_BADGE",
            level = 5,
            quantity = null,
            occupied = null,
            incomingTroops = null,
            kind = TargetKind.RESOURCE,
            confidence = 0.90f
        )
        val plan = TargetPlanner().plan(100, 100, listOf(target), UserPreferences())
        assertEquals(1, plan.rankedDiscoveries.size)
        assertEquals(null, plan.rankedDiscoveries.first().observation.coordinate)
        assertEquals(0, plan.ranked.size)
    }

    @Test
    fun discoverySurfacesSemanticBadgeWhenLevelIsUnknown() {
        val target = MapObservation(
            coordinate = null,
            screenPoint = com.coolhiman.lordsassistant.model.ScreenPoint(640f, 360f),
            label = "RESOURCE_BADGE",
            level = null,
            quantity = null,
            occupied = null,
            incomingTroops = null,
            kind = TargetKind.RESOURCE,
            confidence = 0.80f
        )
        val plan = TargetPlanner().plan(100, 100, listOf(target), UserPreferences())
        assertEquals(1, plan.rankedDiscoveries.size)
        assertEquals(null, plan.rankedDiscoveries.first().observation.level)
        assertEquals(0, plan.ranked.size)
    }

    @Test
    fun discoverySurfacesBadgeTargetWhenStateIsUnknown() {
        val target = MapObservation(
            WorldCoordinate(355, 101, 100),
            com.coolhiman.lordsassistant.model.ScreenPoint(120f, 120f),
            "RESOURCE_BADGE",
            4,
            null,
            null,
            null,
            TargetKind.RESOURCE,
            0.80f
        )
        val plan = TargetPlanner().plan(100, 100, listOf(target), UserPreferences())
        assertEquals(1, plan.rankedDiscoveries.size)
        assertEquals(0, plan.ranked.size)
    }

}
