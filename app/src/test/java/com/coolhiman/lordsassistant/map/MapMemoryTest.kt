package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.CoordinateAuthority
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class MapMemoryTest {
    private val coordinate = WorldCoordinate(355, 10, 20)

    private fun observation(
        coordinateConfidence: CoordinateConfidence,
        timestamp: Long,
        confidence: Float = 0.9f
    ) = MapObservation(
        coordinate = coordinate,
        screenPoint = ScreenPoint(100f, 100f),
        label = "WOOD",
        level = 3,
        quantity = 1000L,
        occupied = false,
        incomingTroops = false,
        kind = TargetKind.RESOURCE,
        confidence = confidence,
        timestampMs = timestamp,
        coordinateConfidence = coordinateConfidence
    )

    @Test
    fun freshCalibratedObservationReplacesOlderObservedAuthority() {
        val memory = MapMemory()
        memory.upsert(observation(CoordinateConfidence.observed(true, true), 1000L, 1.0f))
        memory.upsert(observation(CoordinateConfidence.calibrated(true, true, 4.0), 1100L, 0.9f))

        assertEquals(
            CoordinateAuthority.CALIBRATED,
            memory.get(coordinate)?.coordinateConfidence?.authority
        )
    }

    @Test
    fun olderObservedObservationCannotOverwriteNewerCalibratedFrame() {
        val memory = MapMemory()
        memory.upsert(observation(CoordinateConfidence.calibrated(true, true, 4.0), 1100L))
        memory.upsert(observation(CoordinateConfidence.observed(true, true), 1000L, 1.0f))

        assertEquals(
            CoordinateAuthority.CALIBRATED,
            memory.get(coordinate)?.coordinateConfidence?.authority
        )
    }
}
