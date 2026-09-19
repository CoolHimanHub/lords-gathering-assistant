package com.coolhiman.lordsassistant.map

import android.content.Context
import android.graphics.Bitmap
import com.coolhiman.lordsassistant.data.DatasetStore
import com.coolhiman.lordsassistant.data.PreferencesStore
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.target.TargetPlan
import com.coolhiman.lordsassistant.target.TargetPlanner
import com.coolhiman.lordsassistant.vision.BlueMarchDetector
import com.coolhiman.lordsassistant.vision.DetectionFusion
import com.coolhiman.lordsassistant.vision.ObservationMapper
import com.coolhiman.lordsassistant.vision.TemplateLibrary
import com.coolhiman.lordsassistant.vision.TemplateTileDetector
import com.coolhiman.lordsassistant.vision.VisionPipeline

data class LiveMapScanResult(
    val observations: List<MapObservation>,
    val plan: TargetPlan,
    val detectedTiles: Int,
    val processingMs: Long,
    val origin: WorldCoordinate?
)

/**
 * V0.4.5 live bridge: screenshot -> visual candidates -> calibrated coordinates
 * -> map memory -> validated target plan. It never performs a game tap.
 */
class LiveMapScanner(context: Context) {
    private val calibrationStore = CalibrationStore(context)
    private val preferencesStore = PreferencesStore(context)
    private val mapMemory = MapMemory()
    private val planner = TargetPlanner()
    private val marchDetector = BlueMarchDetector()
    private val templates = TemplateLibrary(DatasetStore(context)).loadTileTemplates()
    private val pipeline = VisionPipeline(
        TemplateTileDetector(),
        DetectionFusion()
    )

    fun scan(
        bitmap: Bitmap,
        defaultKingdom: Int = 0,
        ocrCoordinate: WorldCoordinate? = null,
        textRegions: List<com.coolhiman.lordsassistant.vision.TextRegion> = emptyList()
    ): LiveMapScanResult {
        val started = System.currentTimeMillis()
        val kingdom = ocrCoordinate?.kingdom ?: defaultKingdom
        val resolver = CoordinateResolver(calibrationStore, kingdom)
        val marchSignals = marchDetector.detect(bitmap)

        val result = pipeline.analyze(
            bitmap = bitmap,
            templates = templates,
            textRegions = textRegions,
            marchSignals = marchSignals,
            coordinateResolver = resolver::resolve
        )

        val observations = result.fused.map(ObservationMapper::map)
        mapMemory.upsertAll(observations)

        val origin = ocrCoordinate
        val preferences = preferencesStore.load()
        val snapshot = mapMemory.snapshot()
        val plan = if (origin != null) {
            planner.plan(origin.x, origin.y, snapshot, preferences)
        } else {
            TargetPlan(snapshot, emptyList())
        }

        return LiveMapScanResult(
            observations = snapshot,
            plan = plan,
            detectedTiles = result.detection.tiles.size,
            processingMs = System.currentTimeMillis() - started,
            origin = origin
        )
    }

    fun close() {
        templates.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
