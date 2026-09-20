package com.coolhiman.lordsassistant.map

import android.content.Context
import android.graphics.Bitmap
import com.coolhiman.lordsassistant.capture.ViewportGuard
import com.coolhiman.lordsassistant.data.DatasetStore
import com.coolhiman.lordsassistant.data.PreferencesStore
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.target.TargetPlan
import com.coolhiman.lordsassistant.target.TargetPlanner
import com.coolhiman.lordsassistant.target.TargetValidationEngine
import com.coolhiman.lordsassistant.target.TargetStability
import com.coolhiman.lordsassistant.target.TargetStabilityTracker
import com.coolhiman.lordsassistant.target.TargetValidationResult
import com.coolhiman.lordsassistant.vision.BlueMarchDetector
import com.coolhiman.lordsassistant.target.ActionButton
import com.coolhiman.lordsassistant.target.ActionButtonDetector
import com.coolhiman.lordsassistant.target.ActionKind
import com.coolhiman.lordsassistant.target.ActionTargetSnapshot
import com.coolhiman.lordsassistant.vision.DetectionFusion
import com.coolhiman.lordsassistant.vision.MarchAssociationDiagnostics
import com.coolhiman.lordsassistant.vision.ObservationMapper
import com.coolhiman.lordsassistant.vision.OrangeMarchDetector
import com.coolhiman.lordsassistant.vision.PopupState
import com.coolhiman.lordsassistant.vision.TemplateLibrary
import com.coolhiman.lordsassistant.vision.TemplateTileDetector
import com.coolhiman.lordsassistant.vision.TemporalMarchSignalTracker
import com.coolhiman.lordsassistant.vision.TemporalObservationTracker
import com.coolhiman.lordsassistant.vision.VisionPipeline

data class LiveMapScanResult(
    val observations: List<MapObservation>,
    val plan: TargetPlan,
    val detectedTiles: Int,
    val processingMs: Long,
    val origin: WorldCoordinate?,
    val cameraState: CameraState = CameraState.STABLE,
    val cameraSharedTargets: Int = 0,
    val cameraScaleChangePercent: Float = 0f,
    val validation: TargetValidationResult = TargetValidationResult(false, com.coolhiman.lordsassistant.target.TargetValidationStage.DETECTED),
    val actionButton: ActionButton? = null,
    val selectedActionTarget: ActionTargetSnapshot? = null,
    val selectedObservation: MapObservation? = null,
    val marchSignals: List<com.coolhiman.lordsassistant.vision.MarchSignal> = emptyList(),
    val popupState: PopupState? = null,
    val selectedMarchAssociation: MarchAssociationDiagnostics? = null,
    val targetStability: TargetStability = TargetStability()
)

class LiveMapScanner(context: Context) {
    private val calibrationStore = CalibrationStore(context)
    private val preferencesStore = PreferencesStore(context)
    private val mapMemory = MapMemory()
    private val tracker = TemporalObservationTracker()
    private val planner = TargetPlanner()
    private val validationEngine = TargetValidationEngine()
    private val blueMarchDetector = BlueMarchDetector()
    private val orangeMarchDetector = OrangeMarchDetector()
    private val marchTracker = TemporalMarchSignalTracker()
    private val viewportGuard = ViewportGuard()
    private val cameraStateTracker = CameraStateTracker()
    private val targetStabilityTracker = TargetStabilityTracker()
    private val templates = TemplateLibrary(DatasetStore(context)).loadTileTemplates()
    private val pipeline = VisionPipeline(TemplateTileDetector(), DetectionFusion())

    fun scan(
        bitmap: Bitmap,
        defaultKingdom: Int = 0,
        ocrCoordinate: WorldCoordinate? = null,
        textRegions: List<com.coolhiman.lordsassistant.vision.TextRegion> = emptyList(),
        popupState: PopupState? = null
    ): LiveMapScanResult {
        val started = System.currentTimeMillis()
        if (!viewportGuard.accept(bitmap.width, bitmap.height)) {
            val snapshot = mapMemory.snapshot()
            return LiveMapScanResult(
                observations = snapshot,
                plan = TargetPlan(snapshot, emptyList()),
                detectedTiles = 0,
                processingMs = System.currentTimeMillis() - started,
                origin = null,
                cameraState = CameraState.UNSTABLE
            )
        }
        val kingdom = ocrCoordinate?.kingdom ?: popupState?.coordinate?.kingdom ?: defaultKingdom
        val resolver = CoordinateResolver(calibrationStore, kingdom)
        val rawMarchSignals = blueMarchDetector.detect(bitmap) + orangeMarchDetector.detect(bitmap)
        val marchSignals = marchTracker.update(rawMarchSignals, started)

        val result = pipeline.analyze(
            bitmap = bitmap,
            templates = templates,
            textRegions = textRegions,
            marchSignals = marchSignals,
            popupState = popupState,
            coordinateResolver = resolver::resolve
        )

        val observations = result.fused.map(ObservationMapper::map)
        val stable = tracker.update(observations)
        val camera = cameraStateTracker.update(stable)
        val stateAware = if (camera.state == CameraState.STABLE) stable else stable.map {
            it.copy(evidence = it.evidence + com.coolhiman.lordsassistant.model.ObservationEvidence.CAMERA_UNSTABLE)
        }
        mapMemory.upsertAll(stateAware)

        val origin = ocrCoordinate
        val preferences = preferencesStore.load()
        val snapshot = mapMemory.snapshot()
        val plan = if (origin != null) {
            planner.plan(origin.x, origin.y, snapshot, preferences, camera.state == CameraState.STABLE)
        } else {
            TargetPlan(snapshot, emptyList())
        }

        val resourceCandidate = plan.ranked.firstOrNull()
        val monsterCandidate = plan.rankedMonsters.firstOrNull()
        val plannedCandidate = resourceCandidate?.let { ranked ->
            snapshot.firstOrNull {
                it.coordinate == ranked.tile.coordinate &&
                    it.kind == com.coolhiman.lordsassistant.model.TargetKind.RESOURCE
            }
        } ?: monsterCandidate?.let { ranked ->
            snapshot.firstOrNull {
                it.coordinate == ranked.target.coordinate &&
                    it.kind == com.coolhiman.lordsassistant.model.TargetKind.MONSTER
            }
        }

        // Safety-critical selection must come from the current frame, not map memory.
        // A stale memory entry may remain rankable after the node disappears from view.
        val candidateObservation = plannedCandidate?.let { planned ->
            stateAware.firstOrNull {
                it.coordinate == planned.coordinate &&
                    it.kind == planned.kind &&
                    it.level == planned.level
            }
        }
        val targetStability = targetStabilityTracker.update(candidateObservation, camera.state)
        val calibrationValid = calibrationStore.fit(kingdom)?.isUsable() == true
        val actionButton = ActionButtonDetector.detect(
            textRegions = textRegions,
            popupPresent = popupState?.isPopup == true,
            targetKind = candidateObservation?.kind
        ).firstOrNull { button ->
            when (candidateObservation?.kind) {
                com.coolhiman.lordsassistant.model.TargetKind.RESOURCE -> button.kind == ActionKind.GATHER
                com.coolhiman.lordsassistant.model.TargetKind.MONSTER -> button.kind == ActionKind.HUNT || button.kind == ActionKind.ATTACK
                null -> false
            }
        }
        val validation = validationEngine.validate(
            observation = candidateObservation,
            cameraStable = camera.state == CameraState.STABLE,
            calibrationValid = calibrationValid,
            targetStable = targetStability.stable,
            popupState = popupState,
            interactionPointValid = actionButton != null,
            actionKind = actionButton?.kind
        )
        val selectedFusionCandidate = candidateObservation?.let { target ->
            result.fused.firstOrNull { fused ->
                fused.coordinate == target.coordinate &&
                    fused.classification.kind == target.kind &&
                    fused.classification.level == target.level
            }
        }
        val selectedActionTarget = candidateObservation?.let { target ->
            val point = actionButton?.point
            val coordinate = target.coordinate
            val level = target.level
            val kind = target.kind
            val actionKind = actionButton?.kind
            if (point != null && coordinate != null && level != null && kind != null && actionKind != null) {
                ActionTargetSnapshot(coordinate, kind, level, actionKind, point)
            } else {
                null
            }
        }

        return LiveMapScanResult(
            observations = snapshot,
            plan = plan,
            detectedTiles = result.detection.tiles.size,
            processingMs = System.currentTimeMillis() - started,
            origin = origin,
            cameraState = camera.state,
            cameraSharedTargets = camera.sharedTargets,
            cameraScaleChangePercent = camera.scaleChangePercent,
            validation = validation,
            actionButton = actionButton,
            selectedActionTarget = selectedActionTarget,
            selectedObservation = candidateObservation,
            marchSignals = marchSignals,
            popupState = popupState,
            selectedMarchAssociation = selectedFusionCandidate?.marchAssociation,
            targetStability = targetStability
        )
    }

    fun close() {
        templates.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
