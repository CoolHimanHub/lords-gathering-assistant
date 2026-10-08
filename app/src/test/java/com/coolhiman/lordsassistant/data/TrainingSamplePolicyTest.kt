package com.coolhiman.lordsassistant.data

import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.vision.PopupState
import com.coolhiman.lordsassistant.vision.PopupSemantic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingSamplePolicyTest {
    private val coordinate = WorldCoordinate(348, 189, 629)

    @Test
    fun acceptsAuthoritativeResource() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(
                kind = TargetKind.RESOURCE,
                resource = ResourceType.WOOD,
                level = 4,
                coordinate = coordinate,
                isPopup = true
            ),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("RESOURCE", decision.labelType)
        assertEquals("WOOD", decision.label)
        assertEquals(0.98f, decision.confidence)
        assertNull(decision.rejection)
    }

    @Test
    fun rejectsTerrainPopupInsteadOfCallingItEmpty() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(
                terrainName = "forest",
                coordinate = coordinate,
                isPopup = true
            ),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("TERRAIN_POPUP", decision.rejection)
    }

    @Test
    fun acceptsAuthoritativeDarknest() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(semantic = PopupSemantic.DARKNEST, level = 4, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("DARKNEST", decision.labelType)
        assertEquals("DARKNEST", decision.label)
        assertEquals(0.95f, decision.confidence)
        assertNull(decision.rejection)
    }

    @Test
    fun acceptsAuthoritativeCastle() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(semantic = PopupSemantic.CASTLE, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("CASTLE", decision.labelType)
        assertEquals("CASTLE", decision.label)
        assertNull(decision.rejection)
    }

    @Test
    fun acceptsAuthoritativeEmptyTile() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(semantic = PopupSemantic.EMPTY, terrainName = "forest", coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("EMPTY", decision.labelType)
        assertEquals("EMPTY", decision.label)
        assertEquals(0.84f, decision.confidence)
        assertNull(decision.rejection)
    }

    @Test
    fun rejectsUnknownSemantic() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(semantic = PopupSemantic.UNKNOWN, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("SEMANTIC_LABEL_MISSING", decision.rejection)
    }

    @Test
    fun rejectsUnacceptedCoordinate() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(kind = TargetKind.RESOURCE, resource = ResourceType.FOOD, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = false,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("COORDINATE_NOT_ACCEPTED", decision.rejection)
    }

    @Test
    fun rejectsUnstableCamera() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(kind = TargetKind.MONSTER, monsterName = "Noceros", level = 3, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = false,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("CAMERA_UNSTABLE", decision.rejection)
    }

    @Test
    fun rejectsOutOfRangeLevel() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(kind = TargetKind.RESOURCE, resource = ResourceType.ORE, level = 6, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("LEVEL_OUT_OF_RANGE", decision.rejection)
    }

    @Test
    fun rejectsMissingSemanticLabel() {
        val decision = TrainingSamplePolicy.decide(
            PopupState(kind = TargetKind.RESOURCE, coordinate = coordinate, isPopup = true),
            acceptedForCalibration = true,
            cameraStable = true,
            coordinateAuthority = "OBSERVED"
        )
        assertEquals("SEMANTIC_LABEL_MISSING", decision.rejection)
    }
}
