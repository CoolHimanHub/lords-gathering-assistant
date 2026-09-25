package com.coolhiman.lordsassistant.vision

import android.graphics.Bitmap
import android.graphics.RectF
import com.coolhiman.lordsassistant.map.CoordinateResolution
import com.coolhiman.lordsassistant.model.CoordinateAuthority
import com.coolhiman.lordsassistant.model.CoordinateConfidence
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.model.TargetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DetectionFusionTest {
    @Test
    fun marchNearTileMarksItOccupiedAndIncoming() {
        val tile = DetectedTile(
            "FOOD", TileClass.RESOURCE, 5, RectF(100f,100f,140f,140f), 0.9
        )
        val fusion = DetectionFusion(maxMarchDistancePx = 80f)
        val result = fusion.fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(155f, 120f, 50.0, 0.8f))
        ) { _, _ -> WorldCoordinate(1, 200, 300) }

        assertEquals(1, result.size)
        assertTrue(result.single().occupied == true)
        assertTrue(result.single().incomingTroops == true)
        assertEquals(200, result.single().coordinate?.x)
        assertEquals(MarchAssociationStatus.CLEAR_MARCH, result.single().marchAssociation.status)
        assertEquals(35f, result.single().marchAssociation.nearestDistancePx!!, 0.01f)
    }

    @Test
    fun popupUnoccupiedOverridesMarchForSelectedCoordinate() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            quantity = 720000L,
            occupied = false,
            incomingTroops = false,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )
        val result = DetectionFusion(maxMarchDistancePx = 80f).fuse(
            DetectionFrame(listOf(tile), 1),
            listOf(
                TextRegion(RectF(100f, 100f, 140f, 140f), GameTextClassifier.classify("WOOD LV 3"), "WOOD LV 3")
            ),
            listOf(MarchSignal(155f, 120f, 50.0, 0.8f)),
            popupState = popup,
            coordinateResolver = { _, _ -> WorldCoordinate(1, 200, 300) }
        )
        assertEquals(false, result.single().occupied)
        assertEquals(false, result.single().incomingTroops)
        assertEquals(720000L, result.single().classification.quantity)
    }

    @Test
    fun popupUpgradesOnlyMatchingCoordinateToObservedProvenance() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            quantity = 720000L,
            occupied = false,
            incomingTroops = false,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )
        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(tile), 1),
            listOf(
                TextRegion(RectF(100f, 100f, 140f, 140f), GameTextClassifier.classify("WOOD LV 3"), "WOOD LV 3")
            ),
            emptyList(),
            popupState = popup,
            coordinateResolver = { _, _ -> WorldCoordinate(1, 200, 300) },
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        ).single()

        assertEquals(CoordinateAuthority.OBSERVED, result.coordinateConfidence.authority)
        assertTrue(result.coordinateConfidence.actionAuthoritative)
    }

    @Test
    fun ambiguousPopupCoordinateDoesNotUpgradeEitherCollidingTile() {
        val first = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f, 100f, 140f, 140f), 0.9
        )
        val second = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(400f, 100f, 440f, 140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )

        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(first, second), 1),
            emptyList(),
            emptyList(),
            popupState = popup,
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.coordinateConfidence.authority == CoordinateAuthority.CALIBRATED })
        assertFalse(result.any { it.coordinateConfidence.actionAuthoritative })
    }

    @Test
    fun popupResourceIdentityDoesNotUpgradeDifferentResourceAtSameCoordinate() {
        val first = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f, 100f, 140f, 140f), 0.9
        )
        val second = DetectedTile(
            "STONE", TileClass.RESOURCE, 3, RectF(150f, 100f, 190f, 140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )
        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(first, second), 1),
            listOf(
                TextRegion(RectF(100f, 100f, 140f, 140f), GameTextClassifier.classify("WOOD LV 3"), "WOOD LV 3"),
                TextRegion(RectF(150f, 100f, 190f, 140f), GameTextClassifier.classify("STONE LV 3"), "STONE LV 3")
            ),
            emptyList(),
            popupState = popup,
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        )

        assertEquals(CoordinateAuthority.OBSERVED, result[0].coordinateConfidence.authority)
        assertEquals(CoordinateAuthority.CALIBRATED, result[1].coordinateConfidence.authority)
    }

    @Test
    fun popupSpecificResourceDoesNotMatchUnknownCompetingTile() {
        val first = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f, 100f, 140f, 140f), 0.9
        )
        val second = DetectedTile(
            "RESOURCE", TileClass.RESOURCE, 3, RectF(150f, 100f, 190f, 140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )

        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(first, second), 1),
            emptyList(),
            emptyList(),
            popupState = popup,
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.coordinateConfidence.authority == CoordinateAuthority.CALIBRATED })
    }

    @Test
    fun popupLevelDoesNotMatchUnknownCompetingTile() {
        val first = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f, 100f, 140f, 140f), 0.9
        )
        val second = DetectedTile(
            "RESOURCE", TileClass.RESOURCE, null, RectF(150f, 100f, 190f, 140f), 0.9
        )
        val popup = PopupState(
            kind = TargetKind.RESOURCE,
            resource = com.coolhiman.lordsassistant.model.ResourceType.WOOD,
            level = 3,
            coordinate = WorldCoordinate(1, 200, 300),
            isPopup = true
        )

        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(first, second), 1),
            listOf(
                TextRegion(RectF(100f, 100f, 140f, 140f), GameTextClassifier.classify("WOOD LV 3"), "WOOD LV 3")
            ),
            emptyList(),
            popupState = popup,
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        )

        assertEquals(2, result.size)
        assertTrue(result.all { it.coordinateConfidence.authority == CoordinateAuthority.CALIBRATED })
        assertTrue(result.none { it.coordinateConfidence.actionAuthoritative })
    }

    @Test
    fun calibratedCoordinateRemainsCalibratedWithoutPopupEvidence() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            emptyList(),
            coordinateEvidenceResolver = { _, _ ->
                CoordinateResolution(
                    WorldCoordinate(1, 200, 300),
                    CoordinateConfidence.calibrated(true, true, 4.0)
                )
            }
        ).single()

        assertEquals(CoordinateAuthority.CALIBRATED, result.coordinateConfidence.authority)
        assertTrue(!result.coordinateConfidence.actionAuthoritative)
    }

    @Test
    fun distantMarchLeavesOccupancyUnknownWhenNoOtherEvidenceExists() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion(maxMarchDistancePx = 30f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(300f, 300f, 50.0, 0.8f))
        ) { _, _ -> null }

        assertNull(result.single().occupied)
        assertNull(result.single().incomingTroops)
    }
    @Test
    fun ambiguousNearbyMarchesDoNotForceIncomingState() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion(maxMarchDistancePx = 80f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(
                MarchSignal(155f, 120f, 50.0, 0.95f),
                MarchSignal(120f, 155f, 50.0, 0.90f)
            )
        ) { _, _ -> WorldCoordinate(1, 200, 300) }

        assertNull(result.single().occupied)
        assertNull(result.single().incomingTroops)
        assertEquals(MarchAssociationStatus.AMBIGUOUS_MARCH, result.single().marchAssociation.status)
        assertEquals(35f, result.single().marchAssociation.nearestDistancePx!!, 0.01f)
        assertEquals(35f, result.single().marchAssociation.secondNearestDistancePx!!, 0.01f)
    }

    @Test
    fun noNearbyMarchReportsNoMarchAssociation() {
        val tile = DetectedTile(
            "WOOD", TileClass.RESOURCE, 3, RectF(100f,100f,140f,140f), 0.9
        )
        val result = DetectionFusion(maxMarchDistancePx = 30f).fuse(
            DetectionFrame(listOf(tile), 1),
            emptyList(),
            listOf(MarchSignal(300f, 300f, 50.0, 0.8f))
        ) { _, _ -> null }

        assertEquals(MarchAssociationStatus.NO_MARCH, result.single().marchAssociation.status)
        assertNull(result.single().marchAssociation.nearestDistancePx)
        assertNull(result.single().marchAssociation.secondNearestDistancePx)
    }

    @Test
    fun ocrPreprocessorBoundsLargeFrameWithoutMutatingSource() {
        val source = Bitmap.createBitmap(1440, 2560, Bitmap.Config.ARGB_8888)
        val prepared = OcrBitmapPreprocessor.prepare(source)
        assertEquals(576, prepared.width)
        assertEquals(1024, prepared.height)
        assertTrue(prepared !== source)
        prepared.recycle()
        source.recycle()
    }

    @Test
    fun ocrPreprocessorBoundsMediumFrameWithoutMutatingSource() {
        val source = Bitmap.createBitmap(800, 1200, Bitmap.Config.ARGB_8888)
        val prepared = OcrBitmapPreprocessor.prepare(source)
        assertEquals(682, prepared.width)
        assertEquals(1024, prepared.height)
        assertTrue(prepared !== source)
        prepared.recycle()
        source.recycle()
    }

    @Test
    fun ocrPreprocessorKeepsSmallFrameForZeroCopyPath() {
        val source = Bitmap.createBitmap(800, 1000, Bitmap.Config.ARGB_8888)
        val prepared = OcrBitmapPreprocessor.prepare(source)
        assertTrue(prepared === source)
        source.recycle()
    }


    @Test
    fun nearbyStructureLabelDoesNotOverrideResourceBadgeWhenTooFar() {
        val tile = DetectedTile(
            "RESOURCE_BADGE", TileClass.RESOURCE, 4, RectF(100f, 100f, 130f, 130f), 0.9
        )
        val relay = TextRegion(
            RectF(160f, 100f, 250f, 125f),
            GameTextClassifier.classify("Relay Tower 420"),
            "Relay Tower 420"
        )
        val result = DetectionFusion().fuse(
            DetectionFrame(listOf(tile), 1),
            listOf(relay),
            emptyList()
        ) { _, _ -> WorldCoordinate(1, 200, 300) }

        assertEquals(TargetKind.RESOURCE, result.single().classification.kind)
        assertEquals(false, result.single().ignored)
    }

}

