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
}
