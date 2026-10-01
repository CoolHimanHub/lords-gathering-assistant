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
import com.coolhiman.lordsassistant.vision.AiSceneDetector
import com.coolhiman.lordsassistant.target.ActionButton
import com.coolhiman.lordsassistant.target.ActionButtonDetector
import com.coolhiman.lordsassistant.target.ActionSemanticIdentity
import com.coolhiman.lordsassistant.target.ActionKind
import com.coolhiman.lordsassistant.target.ActionTargetSnapshot
import com.coolhiman.lordsassistant.vision.DetectionFusion
import com.coolhiman.lordsassistant.vision.MarchAssociationDiagnostics
import com.coolhiman.lordsassistant.vision.mapObservation
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
    val aiSceneObjects: Int = 0,
    val aiSceneDiagnostic: String = "AI IDLE",
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
    val cameraMedianShiftPx: Float = 0f,
    val cameraSpreadPx: Float = 0f,
    val cameraScaleChangePercent: Float = 0f,
    val cameraContinuityForActions: Boolean = false,
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
    private var previousCameraState: CameraState? = null
    // The map HUD can OCR only X/Y and supply kingdom=0. Popup K/X/Y is
    // authoritative, so retain the last non-zero kingdom for calibration.
    private var activeKingdom: Int? = null
    private val targetStabilityRegistry = com.coolhiman.lordsassistant.target.TargetStabilityRegistry()
    private val templateLibrary = TemplateLibrary(DatasetStore(context))
    private var templates = templateLibrary.loadTileTemplates()
    private val pipeline = VisionPipeline(TemplateTileDetector(), DetectionFusion(), AiSceneDetector(context))

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
            // A viewport discontinuity is a hard screen-space evidence boundary.
            // Do not carry temporal target/march evidence or camera geometry across
            // it. Re-baseline the guard to the new dimensions and process this
            // frame as the first frame of the new viewport; action continuity will
            // remain false until subsequent frames establish it again.
            viewportGuard.reset()
            check(viewportGuard.accept(bitmap.width, bitmap.height)) {
                "ViewportGuard rejected its own fresh dimensions"
            }
            tracker.reset()
            marchTracker.clear()
            cameraAnchorTracker.reset()
            cameraModelStabilityTracker.reset()
            cameraStateTracker.reset()
            targetStabilityTracker.reset()
            targetStabilityRegistry.resetForCameraBoundary()
        }
        popupState?.coordinate?.kingdom?.takeIf { it > 0 }?.let { activeKingdom = it }
        ocrCoordinate?.kingdom?.takeIf { it > 0 }?.let { activeKingdom = it }
        val kingdom = activeKingdom ?: ocrCoordinate?.kingdom ?: popupState?.coordinate?.kingdom ?: defaultKingdom
        val resolver = CoordinateResolver(calibrationStore, kingdom)
        // March contours are screen-space evidence. Once the camera has moved,
        // tracks from the previous view must not survive into the next stable frame.
        // The first unstable frame is still harmless because action authority is
        // camera-gated; the next frame starts from a fresh march track set.
        if (previousCameraState != null && previousCameraState != CameraState.STABLE) {
            marchTracker.clear()
        }
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
        var observations = result.fused.map(::mapObservation)

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

        // Only a camera model that has also established frame-to-frame
        // continuity may drive the second coordinate pass. A mathematically
        // usable model can still be a first-frame baseline or an incorrect
        // anchor fit; consuming it early could poison world coordinates and
        // durable map memory even though action authority remains gated below.
        // The first pass remains the authoritative fallback until continuity
        // is established.
        if (fittedCameraModel?.isUsable() == true && cameraModelContinuityValid) {
            result = analyze(fittedCameraModel)
            observations = result.fused.map(::mapObservation)
        }

        // Persist the final coordinate pass as the baseline for the next
        // frame. The returned anchors are intentionally ignored here because
        // camera fitting already happened above.
        cameraAnchorTracker.update(observations)

        // Temporal stabilization is intentionally strict about OCR identity
        // because its output can become action-authoritative. Camera continuity
        // is different: it must observe raw current-frame screen geometry so a
        // transient OCR label dropout does not turn a stationary camera into
        // "shared=0". This assessment is diagnostic/grid-learning evidence only;
        // action authorization remains gated by camera.continuityForActions below.
        val stable = tracker.update(observations)
        val camera = cameraStateTracker.update(observations)
        previousCameraState = camera.state
        val stateAware = if (camera.state == CameraState.STABLE) stable else stable.map {
            it.copy(evidence = it.evidence + com.coolhiman.lordsassistant.model.ObservationEvidence.CAMERA_UNSTABLE)
        }
        mapMemory.upsertAll(stateAware)

        // Prefer popup K/X/Y when present. It is direct game evidence and avoids
        // treating an OCR-only K=0 placeholder as authoritative.
        val origin = popupState?.coordinate ?: ocrCoordinate?.let { coordinate ->
            if (coordinate.kingdom == 0 && activeKingdom != null) {
                coordinate.copy(kingdom = activeKingdom!!)
            } else {
                coordinate
            }
        }
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
            planningObservations.firstOrNull {
                it.kind == com.coolhiman.lordsassistant.model.TargetKind.RESOURCE &&
                    com.coolhiman.lordsassistant.target.ActionPlannerMatch.matchesResource(it, ranked)
            }
        } ?: monsterCandidate?.let { ranked ->
            planningObservations.firstOrNull {
                it.kind == com.coolhiman.lordsassistant.model.TargetKind.MONSTER &&
                    com.coolhiman.lordsassistant.target.ActionPlannerMatch.matchesMonster(it, ranked)
            }
        }

        // Safety-critical selection must come from the current frame, not map memory.
        // A stale memory entry may remain rankable after the node disappears from view.
        val candidateObservation = plannedCandidate?.let { planned ->
            stateAware.firstOrNull {
                it.coordinate == planned.coordinate &&
                    it.kind == planned.kind &&
                    it.level == planned.level &&
                    com.coolhiman.lordsassistant.target.ActionPlannerMatch.sameSemanticIdentity(it, planned)
            }
        }
        // A camera discontinuity invalidates every per-target temporal tracker, including targets that were absent while the camera was unstable.
        // Without this collection-level reset, an old two-frame history could resume on the first post-pan frame and satisfy action stability too early.
        if (camera.state != CameraState.STABLE) {
            targetStabilityRegistry.resetForCameraBoundary()
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

        // OCR/template button detection can return duplicate representations
        // of the same visible action. Never let duplicates advance temporal
        // target stability twice within one bitmap; doing so would make a
        // first-frame target appear stable after a single captured frame.
        val uniqueActionButtons = detectedActionButtons.distinctBy {
            "${it.kind}:${it.point.x}:${it.point.y}"
        }

        // Multiple detected buttons can still refer to the same world target
        // (for example overlapping OCR/template detections at different screen
        // points). Resolve each target to one deterministic button before touching
        // temporal stability. Otherwise one bitmap can advance the same target's
        // tracker more than once and manufacture a two-frame confirmation.
        val candidateMatches = uniqueActionButtons
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
                // A single button that is close to multiple world targets is
                // ambiguous and must not become an action candidate.
                if (matching.size == 1) matching.map { it to button } else emptyList()
            }
            .groupBy { (observation, _) ->
                "${observation.coordinate}:${observation.kind}:${observation.level}"
            }
            .values
            .mapNotNull { matches ->
                // Same coordinate/kind/level is not sufficient identity for
                // action dispatch. If the same world target is represented by
                // different semantic identities in one frame, selecting the
                // nearest button would silently choose between conflicting
                // observations. Reject the whole group and require a fresh,
                // unambiguous frame instead.
                val semanticIdentities = matches
                    .map { (observation, _) ->
                        ActionSemanticIdentity.fromObservation(observation)
                    }
                    .toSet()
                if (semanticIdentities.size > 1 || semanticIdentities.contains(null)) {
                    null
                } else {
                    matches.minByOrNull { (observation, button) ->
                        distance(button.point, observation.screenPoint)
                    }
                }
            }

        val candidateTargets = candidateMatches
            .mapNotNull { (observation, button) ->

                val semanticIdentity = ActionSemanticIdentity.fromObservation(observation)
                val key = "${observation.coordinate}:${observation.kind}:${observation.level}:${semanticIdentity ?: "<missing>"}"
                val stability = targetStabilityRegistry.update(key, observation, camera.state)
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
                    point = button.point,
                    semanticIdentity = ActionSemanticIdentity.fromObservation(observation)
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

        targetStabilityRegistry.removeExcept(
            candidateTargets.mapTo(linkedSetOf()) {
                "${it.target.coordinate}:${it.target.kind}:${it.target.level}:${it.target.semanticIdentity ?: "<missing>"}"
            }
        )

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
                ActionTargetSnapshot(
                    coordinate = coordinate,
                    kind = kind,
                    level = level,
                    actionKind = actionKind,
                    point = point,
                    semanticIdentity = ActionSemanticIdentity.fromObservation(target)
                )
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
            aiSceneObjects = result.aiScene.objects.size,
            aiSceneDiagnostic = result.aiScene.diagnostic,
            badgeDetections = result.badgeDetections,
            openCvReady = result.openCvReady,
            openCvDiagnostic = OpenCvRuntime.diagnostic(),
            actionButtonDetections = detectedActionButtons.size,
            processingMs = System.currentTimeMillis() - started,
            origin = origin,
            coordinateConfidence = coordinateConfidence,
            cameraState = camera.state,
            cameraSharedTargets = camera.sharedTargets,
            cameraMedianShiftPx = camera.medianShiftPx,
            cameraSpreadPx = camera.spreadPx,
            cameraScaleChangePercent = camera.scaleChangePercent,
            cameraContinuityForActions = camera.continuityForActions,
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
                    it.tile.coordinate == coordinate &&
                        it.tile.level == level &&
                        com.coolhiman.lordsassistant.target.ActionPlannerMatch.matchesResource(observation, it)
                }
                if (index >= 0) return index to plan.ranked[index].score
            }
            com.coolhiman.lordsassistant.model.TargetKind.MONSTER -> {
                val index = plan.rankedMonsters.indexOfFirst {
                    it.target.coordinate == coordinate &&
                        it.target.level == level &&
                        com.coolhiman.lordsassistant.target.ActionPlannerMatch.matchesMonster(observation, it)
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
        targetStabilityRegistry.resetForCameraBoundary()
        previousCameraState = null
        activeKingdom = null
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
        pipeline.close()
    }
}
