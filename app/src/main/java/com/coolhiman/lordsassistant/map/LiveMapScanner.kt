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
import com.coolhiman.lordsassistant.vision.OpenCvRuntime
import com.coolhiman.lordsassistant.vision.PopupState
import com.coolhiman.lordsassistant.vision.TemplateLibrary
import com.coolhiman.lordsassistant.vision.TemplateTileDetector
import com.coolhiman.lordsassistant.vision.TemporalMarchSignalTracker
import com.coolhiman.lordsassistant.vision.TemporalObservationTracker
import com.coolhiman.lordsassistant.vision.VisionPipeline

data class LiveActionCandidate(
    val target: ActionTargetSnapshot,
    val observation: MapObservation,
    val actionButton: ActionButton,
    val validation: TargetValidationResult,
    val stability: TargetStability,
    /** Zero-based position in the planner's existing ranked order. */
    val plannerRank: Int,
    /** Native planner score when this target is ranked; otherwise -INF. */
    val plannerScore: Double,
    /** Camera continuity must be established in a prior/current frame comparison. */
    val cameraContinuityValid: Boolean
)

data class LiveMapScanResult(
    val observations: List<MapObservation>,
    /** Current-frame observations including screen points for active grid learning. */
    val frameObservations: List<MapObservation> = emptyList(),
    val plan: TargetPlan,
    val detectedTiles: Int,
    val templateCount: Int = 0,
    val semanticTargetDetections: Int = 0,
    val badgeDetections: Int = 0,
    val openCvReady: Boolean = false,
    val openCvDiagnostic: String = "NOT_INITIALIZED",
    val actionButtonDetections: Int = 0,
    val processingMs: Long,
    val origin: WorldCoordinate?,
    /** Provenance of the coordinate evidence exposed by this frame. */
    val coordinateConfidence: CoordinateConfidence = CoordinateConfidence.none(),
    val cameraState: CameraState = CameraState.STABLE,
    val cameraSharedTargets: Int = 0,
    val cameraScaleChangePercent: Float = 0f,
    val validation: TargetValidationResult = TargetValidationResult(false, com.coolhiman.lordsassistant.target.TargetValidationStage.DETECTED),
    val actionButton: ActionButton? = null,
    val selectedActionTarget: ActionTargetSnapshot? = null,
    val actionCandidates: List<LiveActionCandidate> = emptyList(),
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
    private val cameraAnchorTracker = CameraAnchorTracker()
    private val cameraModelStabilityTracker = CameraModelStabilityTracker()
    private val targetStabilityTracker = TargetStabilityTracker()
    private val targetStabilityTrackers = linkedMapOf<String, TargetStabilityTracker>()
    private val templateLibrary = TemplateLibrary(DatasetStore(context))
    private var templates = templateLibrary.loadTileTemplates()
    private val pipeline = VisionPipeline(TemplateTileDetector(), DetectionFusion())

    companion object {
        private const val ACTION_BUTTON_TARGET_MAX_DISTANCE_PX = 420f
    }

    fun scan(
        bitmap: Bitmap,
        defaultKingdom: Int = 0,
        ocrCoordinate: WorldCoordinate? = null,
        textRegions: List<com.coolhiman.lordsassistant.vision.TextRegion> = emptyList(),
        popupState: PopupState? = null
    ): LiveMapScanResult {
        val started = System.currentTimeMillis()
        if (!viewportGuard.accept(bitmap.width, bitmap.height)) {
            // A viewport discontinuity invalidates screen-space continuity.
            // Quarantine all frame-to-frame camera/temporal state so the next
            // accepted frame cannot inherit stale anchor positions.
            cameraAnchorTracker.reset()
            cameraStateTracker.reset()
            targetStabilityTracker.reset()
            targetStabilityTrackers.clear()

            val snapshot = mapMemory.snapshot()
            return LiveMapScanResult(
                observations = snapshot,
                frameObservations = emptyList(),
                plan = TargetPlan(snapshot, emptyList()),
                detectedTiles = 0,
                templateCount = templates.size,
                semanticTargetDetections = 0,
                badgeDetections = 0,
                openCvReady = false,
                openCvDiagnostic = OpenCvRuntime.diagnostic(),
                actionButtonDetections = 0,
                processingMs = System.currentTimeMillis() - started,
                origin = null,
                cameraState = CameraState.UNSTABLE
            )
        }
        val kingdom = ocrCoordinate?.kingdom ?: popupState?.coordinate?.kingdom ?: defaultKingdom
        val resolver = CoordinateResolver(calibrationStore, kingdom)
        val rawMarchSignals = blueMarchDetector.detect(bitmap) + orangeMarchDetector.detect(bitmap)
        val marchSignals = marchTracker.update(rawMarchSignals, started)

        fun analyze(cameraModel: CameraModel? = null) = pipeline.analyze(
            bitmap = bitmap,
            templates = templates,
            textRegions = textRegions,
            marchSignals = marchSignals,
            popupState = popupState,
            coordinateResolver = { x, y ->
                resolver.resolve(com.coolhiman.lordsassistant.model.ScreenPoint(x, y), cameraModel)
            },
            coordinateEvidenceResolver = { x, y ->
                resolver.resolveDetailed(
                    com.coolhiman.lordsassistant.model.ScreenPoint(x, y),
                    cameraModel
                )
            }
        )

        // First pass uses the established affine calibration. Its stable
        // semantic observations provide candidate anchors for the current
        // camera state; no new coordinate is trusted yet.
        var result = analyze()
        var observations = result.fused.map(ObservationMapper::map)

        // The anchor tracker deliberately operates before temporal target
        // stabilization: it is only estimating camera geometry from repeated
        // semantic identities and never authorizes a target.
        val anchors = cameraAnchorTracker.update(observations)
        val fittedCameraModel = calibrationStore.fit(kingdom)?.let { calibration ->
            CameraInvariantWorldModel(calibration).fit(anchors)
        }
        // A camera model can be mathematically usable for one frame while its
        // anchor association is still wrong. Require continuity across two
        // consecutive usable models before allowing it to contribute action
        // authority. Translation-only panning is intentionally allowed.
        val cameraModelContinuityValid = fittedCameraModel == null ||
            cameraModelStabilityTracker.update(fittedCameraModel)

        // Only a validated camera model may trigger a second coordinate pass.
        // Planning/action safety still requires CameraState.STABLE below.
        if (fittedCameraModel?.isUsable() == true) {
            result = analyze(fittedCameraModel)
            observations = result.fused.map(ObservationMapper::map)
        }

        // Persist the final coordinate pass as the baseline for the next
        // frame. The returned anchors are intentionally ignored here because
        // camera fitting already happened above.
        cameraAnchorTracker.update(observations)

        // Temporal stabilization and camera-state assessment happen exactly
        // once for the final coordinate pass, so one bitmap cannot advance
        // stability or fabricate a stable camera state.
        val stable = tracker.update(observations)
        val camera = cameraStateTracker.update(stable)
        val stateAware = if (camera.state == CameraState.STABLE) stable else stable.map {
            it.copy(evidence = it.evidence + com.coolhiman.lordsassistant.model.ObservationEvidence.CAMERA_UNSTABLE)
        }
        mapMemory.upsertAll(stateAware)

        val origin = ocrCoordinate
        val preferences = preferencesStore.load()
        val snapshot = mapMemory.snapshot()
        // MapMemory is intentionally keyed by world coordinate, so an
        // uncalibrated frame-local semantic target cannot live there yet.
        // Feed the raw current-frame semantic observations alongside temporal
        // state and memory. Temporal tracking intentionally drops coordinate-less
        // observations, so using only stateAware here made discovery stay at zero
        // even while the HUD reported dozens of semantic detections. Discovery
        // remains informational; strict action ranking is separately camera-gated
        // and still requires coordinate/level/known-free state.
        val planningObservations = (snapshot + observations + stateAware)
            .distinctBy { current ->
                listOf(
                    current.coordinate,
                    current.screenPoint?.x,
                    current.screenPoint?.y,
                    current.kind,
                    current.level,
                    current.label
                )
            }
        // Discovery ranking is frame-local and does not require the OCR
        // coordinate origin. The previous null-origin branch returned an empty
        // TargetPlan, which made the HUD report "N semantic" while discovery
        // stayed at zero even when badges/monster/resource semantics were
        // successfully detected. Use a neutral origin only for the
        // informational discovery score; never expose that neutral origin to
        // the strict action-ranking path.
        val plannedWithDiscovery = planner.plan(
            origin?.x ?: 0,
            origin?.y ?: 0,
            planningObservations,
            preferences,
            camera.state == CameraState.STABLE
        )
        val plan = if (origin != null) {
            plannedWithDiscovery
        } else {
            // No K/X/Y authority: preserve discovery evidence but fail closed
            // for action ranking. Action candidates already require coordinate,
            // calibration, stability and validation independently.
            plannedWithDiscovery.copy(
                ranked = emptyList(),
                rankedMonsters = emptyList()
            )
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
        val actionCameraStable = camera.state == CameraState.STABLE && cameraModelContinuityValid && camera.continuityForActions
        val coordinateConfidence = when {
            origin != null -> CoordinateConfidence.observed(
                calibrationUsable = calibrationValid,
                cameraStable = camera.state == CameraState.STABLE
            )
            else -> observations.firstOrNull {
                it.coordinateConfidence.authority == CoordinateAuthority.OBSERVED
            }?.coordinateConfidence
                ?: observations.firstOrNull {
                    it.coordinateConfidence.authority == CoordinateAuthority.CALIBRATED
                }?.coordinateConfidence
                ?: CoordinateConfidence.none()
        }
        val detectedActionButtons = ActionButtonDetector.detect(
            textRegions = textRegions,
            popupPresent = popupState?.isPopup == true,
            targetKind = null
        )

        val candidateTargets = detectedActionButtons
            .flatMap { button ->
                val matching = stateAware.filter { observation ->
                    observation.coordinate != null && observation.kind != null && observation.level != null &&
                        when (observation.kind) {
                            com.coolhiman.lordsassistant.model.TargetKind.RESOURCE -> button.kind == ActionKind.GATHER
                            com.coolhiman.lordsassistant.model.TargetKind.MONSTER -> button.kind == ActionKind.HUNT || button.kind == ActionKind.ATTACK
                            null -> false
                        } &&
                        distance(button.point, observation.screenPoint) <= ACTION_BUTTON_TARGET_MAX_DISTANCE_PX
                }
                if (matching.size != 1) emptyList() else matching.map { it to button }
            }
            .mapNotNull { (observation, button) ->

                val key = "${observation.coordinate}:${observation.kind}:${observation.level}"
                val stabilityTracker = targetStabilityTrackers.getOrPut(key) { TargetStabilityTracker() }
                val stability = stabilityTracker.update(observation, camera.state)
                val fused = result.fused.firstOrNull { item ->
                    item.coordinate == observation.coordinate &&
                        item.classification.kind == observation.kind &&
                        item.classification.level == observation.level
                }
                val actionValidation = validationEngine.validate(
                    observation = observation,
                    cameraStable = actionCameraStable,
                    calibrationValid = calibrationValid,
                    targetStable = stability.stable,
                    marchAssociationStatus = fused?.marchAssociation?.status
                        ?: com.coolhiman.lordsassistant.vision.MarchAssociationStatus.NO_MARCH,
                    popupState = popupState,
                    interactionPointValid = true,
                    actionKind = button.kind
                )
                val target = ActionTargetSnapshot(
                    coordinate = observation.coordinate!!,
                    kind = observation.kind!!,
                    level = observation.level!!,
                    actionKind = button.kind,
                    point = button.point
                )
                val plannerRankAndScore = plannerRankAndScore(plan, observation)
                LiveActionCandidate(
                    target = target,
                    observation = observation,
                    actionButton = button,
                    validation = actionValidation,
                    stability = stability,
                    plannerRank = plannerRankAndScore.first,
                    plannerScore = plannerRankAndScore.second,
                    cameraContinuityValid = camera.continuityForActions
                )
            }

        targetStabilityTrackers.keys
            .filter { key -> candidateTargets.none { candidate ->
                "${candidate.target.coordinate}:${candidate.target.kind}:${candidate.target.level}" == key
            } }
            .takeIf { it.size > 32 }
            ?.forEach(targetStabilityTrackers::remove)

        val selectedFusionCandidate = candidateObservation?.let { observation ->
            result.fused.firstOrNull { fused ->
                fused.coordinate == observation.coordinate &&
                    fused.classification.kind == observation.kind &&
                    fused.classification.level == observation.level
            }
        }
        val selectedActionCandidate = candidateTargets.firstOrNull { it.observation == candidateObservation }
        val actionButton = selectedActionCandidate?.actionButton
        val validation = selectedActionCandidate?.validation ?: validationEngine.validate(
            observation = candidateObservation,
            cameraStable = actionCameraStable,
            calibrationValid = calibrationValid,
            targetStable = targetStability.stable,
            marchAssociationStatus = com.coolhiman.lordsassistant.vision.MarchAssociationStatus.NO_MARCH,
            popupState = popupState,
            interactionPointValid = actionButton != null,
            actionKind = actionButton?.kind
        )
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
            frameObservations = observations,
            plan = plan,
            detectedTiles = result.detection.tiles.size,
            templateCount = templates.size,
            // Semantic discovery is frame-local and must not disappear merely
            // because temporal tracking has no calibrated world coordinate yet.
            // Coordinates remain mandatory for ranking/action, but badge/OCR
            // semantics are useful diagnostic evidence before calibration.
            semanticTargetDetections = observations.count { it.kind != null },
            badgeDetections = result.badgeDetections,
            openCvReady = result.openCvReady,
            openCvDiagnostic = OpenCvRuntime.diagnostic(),
            actionButtonDetections = detectedActionButtons.size,
            processingMs = System.currentTimeMillis() - started,
            origin = origin,
            coordinateConfidence = coordinateConfidence,
            cameraState = camera.state,
            cameraSharedTargets = camera.sharedTargets,
            cameraScaleChangePercent = camera.scaleChangePercent,
            validation = validation,
            actionButton = actionButton,
            selectedActionTarget = selectedActionTarget,
            actionCandidates = candidateTargets,
            selectedObservation = candidateObservation,
            marchSignals = marchSignals,
            popupState = popupState,
            selectedMarchAssociation = selectedFusionCandidate?.marchAssociation,
            targetStability = targetStability
        )
    }

    private fun plannerRankAndScore(
        plan: TargetPlan,
        observation: MapObservation
    ): Pair<Int, Double> {
        val coordinate = observation.coordinate ?: return Int.MAX_VALUE to Double.NEGATIVE_INFINITY
        val level = observation.level ?: return Int.MAX_VALUE to Double.NEGATIVE_INFINITY
        when (observation.kind) {
            com.coolhiman.lordsassistant.model.TargetKind.RESOURCE -> {
                val index = plan.ranked.indexOfFirst {
                    it.tile.coordinate == coordinate && it.tile.level == level
                }
                if (index >= 0) return index to plan.ranked[index].score
            }
            com.coolhiman.lordsassistant.model.TargetKind.MONSTER -> {
                val index = plan.rankedMonsters.indexOfFirst {
                    it.target.coordinate == coordinate && it.target.level == level
                }
                if (index >= 0) {
                    return (plan.ranked.size + index) to plan.rankedMonsters[index].score
                }
            }
            null -> Unit
        }
        return Int.MAX_VALUE to Double.NEGATIVE_INFINITY
    }

    private fun distance(a: com.coolhiman.lordsassistant.model.ScreenPoint, b: com.coolhiman.lordsassistant.model.ScreenPoint?): Float {
        if (b == null) return Float.MAX_VALUE
        return kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }

    /**
     * Starts a new capture-session temporal boundary without discarding the
     * durable map memory. Screen-space continuity, camera geometry, march
     * tracking, and target stability from the previous capture session are
     * never valid evidence for a new session.
     */
    fun resetCaptureSession() {
        // Screen-space calibration belongs to the current capture viewport.
        // Preserve durable probe history, but never reuse transform samples
        // from a previous capture session for coordinate resolution/actions.
        calibrationStore.clear()
        reloadTemplates()
        viewportGuard.reset()
        tracker.reset()
        cameraStateTracker.reset()
        cameraAnchorTracker.reset()
        cameraModelStabilityTracker.reset()
        targetStabilityTracker.reset()
        targetStabilityTrackers.clear()
    }

    private fun reloadTemplates() {
        templates.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        templates = templateLibrary.loadTileTemplates()
    }

    fun close() {
        templates.forEach { (_, bitmap) ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }
}
