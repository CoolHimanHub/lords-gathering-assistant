package com.coolhiman.lordsassistant.map

import android.content.Context
import com.coolhiman.lordsassistant.accessibility.LmAccessibilityService
import com.coolhiman.lordsassistant.model.MapObservation
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.WorldCoordinate
import com.coolhiman.lordsassistant.overlay.OverlayService
import com.coolhiman.lordsassistant.vision.PopupState
import kotlin.math.abs
import kotlin.math.hypot

/**
 * User-started active learner for the Lords Mobile map.
 * It only probes map cells and waits for the game's popup coordinate.
 */
class GridLearningController(private val context: Context) {
    private val store = GridLearningStore(context)
    private val calibrationStore = CalibrationStore(context)
    private val calibrator = AffineGridCalibrator()

    private data class Probe(val point: ScreenPoint, val expected: WorldCoordinate?, val source: String, val cameraEpoch: Int, val stableAtDispatch: Boolean)

    private var active = false
    private var pending: Probe? = null
    private var pendingSinceMs = 0L
    private var pendingRetries = 0
    private var lastCandidate: ScreenPoint? = null
    private var candidateFrames = 0
    private var lastTapMs = 0L
    private var lastWidth = 0
    private var lastHeight = 0
    private var lastCameraStable = false
    private var cameraEpoch = 0
    private var sessionSamples = 0
    private val learnedCoordinates = linkedSetOf<String>()
    private val queuedWorld = linkedSetOf<String>()
    private val attemptedScreen = linkedMapOf<String, Long>()
    private val frontier = ArrayDeque<WorldCoordinate>()
    private var candidateQueue: Probe? = null
    private var probeAttempts = 0
    private var dispatchSuccesses = 0
    private var dispatchFailures = 0
    private var lastProbeIssue: String? = null
    private var candidateDetections = 0
    private var safeCandidateDetections = 0

    companion object {
        private const val MIN_CANDIDATE_FRAMES = 2
        private const val MIN_TAP_INTERVAL_MS = 850L
        private const val POPUP_TIMEOUT_MS = 3200L
        private const val MAX_RETRIES = 1
        private const val POINT_DEDUP_PX = 26f
        private const val MAX_FRONTIER = 160
        private const val MAX_SESSION_PROBES = 500
        private const val CALIBRATION_RMS_FOR_SYNTHETIC_PX = 24.0
        private const val MIN_SYNTHETIC_SAMPLES = 6
        private const val MIN_WORLD_SPAN = 2
    }

    @Synchronized
    fun start() {
        // A new explicit learning run establishes a fresh screen->world
        // calibration for the current viewport. Durable probe history remains
        // in GridLearningStore; stale screen-space calibration must not leak
        // into this run.
        calibrationStore.clear()
        calibrator.clear()
        active = true
        pending = null
        pendingSinceMs = 0L
        pendingRetries = 0
        lastCandidate = null
        candidateFrames = 0
        lastTapMs = 0L
        sessionSamples = 0
        lastCameraStable = false
        learnedCoordinates.clear()
        queuedWorld.clear()
        frontier.clear()
        attemptedScreen.clear()
        candidateQueue = null
        probeAttempts = 0
        dispatchSuccesses = 0
        dispatchFailures = 0
        lastProbeIssue = null
        candidateDetections = 0
        safeCandidateDetections = 0
    }

    @Synchronized
    fun stop() {
        active = false
        pending = null
        frontier.clear()
        queuedWorld.clear()
        candidateQueue = null
        lastCandidate = null
        candidateFrames = 0
    }

    @Synchronized
    fun isActive(): Boolean = active

    @Synchronized
    fun onFrame(
        width: Int,
        height: Int,
        frameObservations: List<MapObservation>,
        popupState: PopupState?,
        actionButtonDetections: Int,
        cameraStable: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ) {
        if (!active || width <= 0 || height <= 0) return
        lastWidth = width
        lastHeight = height
        // A calibration transform is valid only for one camera viewport. If
        // the camera leaves the stable state, invalidate the active transform
        // and any screen-space work queued against it. Durable probe history
        // remains untouched, so the learner can rebuild from the new viewport.
        if (lastCameraStable && !cameraStable) {
            cameraEpoch++
            calibrator.clear()
            calibrationStore.clear()
            frontier.clear()
            queuedWorld.clear()
            candidateQueue = null
            pending = null
            pendingSinceMs = 0L
            pendingRetries = 0
            lastCandidate = null
            candidateFrames = 0
        }
        lastCameraStable = cameraStable

        val currentPopup = popupState?.takeIf { it.isPopup && it.coordinate != null }
        val currentPending = pending

        if (currentPending != null) {
            if (currentPopup != null) {
                recordPopup(currentPending, currentPopup, nowMs)
                pending = null
                pendingSinceMs = 0L
                pendingRetries = 0
                lastCandidate = null
                candidateFrames = 0
                lastTapMs = nowMs
            } else if (nowMs - pendingSinceMs >= POPUP_TIMEOUT_MS) {
                if (pendingRetries < MAX_RETRIES) {
                    pendingRetries++
                    pendingSinceMs = nowMs
                    dispatchProbe(currentPending)
                    return
                }
                pending = null
                pendingSinceMs = 0L
                pendingRetries = 0
                lastCandidate = null
                candidateFrames = 0
            }
            return
        }

        if (sessionSamples >= MAX_SESSION_PROBES) return
        // A popup may remain open after a successful probe. Lords Mobile can
        // replace that popup when another map cell is tapped, so learning should
        // continue without requiring a manual close. While a popup is visible,
        // probes are restricted to the lower map-safe band so the learner never
        // taps the popup's Transfer/Occupy controls.
        // Do not use the global action-button count as a learning stop condition.
        // The game's fixed HUD can legitimately produce action-button detections
        // even when the selected point is a safe map cell. A count-only gate
        // therefore deadlocked learning before the first probe. Probe geometry
        // below is the authoritative safety boundary; popup controls are handled
        // separately by the lower-band rule.
        if (nowMs - lastTapMs < MIN_TAP_INTERVAL_MS) return

        // Seed learning from detected map-cell geometry, not only from semantic
        // classifications. Empty/unknown cells are part of the grid and often
        // have no TargetKind at all. Requiring kind != null here made a fresh
        // learning run depend on the very classification that calibration is
        // supposed to help establish, producing probes=0 while tile detection
        // itself was healthy.
        candidateDetections = frameObservations.count {
            it.screenPoint != null && it.confidence >= 0.45f
        }
        safeCandidateDetections = frameObservations.count {
            val point = it.screenPoint
            point != null && it.confidence >= 0.45f && safeMapPoint(point, width, height)
        }
        frameObservations
            .asSequence()
            .filter { it.screenPoint != null && it.confidence >= 0.45f }
            .filter { safeMapPoint(it.screenPoint!!, width, height) }
            .forEach { observation ->
                val point = observation.screenPoint!!
                val key = pointKey(point)
                if (!attemptedScreen.containsKey(key) && candidateQueue == null) {
                    val source = if (observation.kind != null) "semantic" else "visual-grid"
                    candidateQueue = Probe(point, observation.coordinate, source, cameraEpoch, cameraStable)
                }
            }

        val selected = nextSyntheticProbe(width, height, cameraStable) ?: nextQueuedPoint(width, height)
        if (selected == null) return

        val point = selected.point
        // Keep taps away from the visible popup/action controls. The lower map
        // band is deliberately used while a popup is open; once the popup closes
        // the normal safe-map geometry is restored.
        if (currentPopup != null && point.y < height * 0.62f) return
        if (lastCandidate != null && distance(lastCandidate, point) <= POINT_DEDUP_PX) {
            candidateFrames++
        } else {
            lastCandidate = point
            candidateFrames = 1
        }
        if (candidateFrames < MIN_CANDIDATE_FRAMES) return

        pending = selected
        pendingSinceMs = nowMs
        pendingRetries = 0
        lastTapMs = nowMs
        probeAttempts++
        lastProbeIssue = null
        while (attemptedScreen.size > 512) {
            val oldest = attemptedScreen.entries.minByOrNull { it.value }?.key ?: break
            attemptedScreen.remove(oldest)
        }
        dispatchProbe(selected)
    }

    private fun recordPopup(probe: Probe, popup: PopupState, nowMs: Long) {
        val actual = popup.coordinate ?: return
        val expected = probe.expected
        val delta = if (expected != null && expected.kingdom == actual.kingdom) {
            maxOf(abs(expected.x - actual.x), abs(expected.y - actual.y))
        } else null

        val accepted = GridProbeAcceptancePolicy.accepts(
            expected = expected,
            actual = actual,
            source = probe.source
        )

        store.append(
            GridLearningRecord(
                timestampMs = nowMs,
                screenX = probe.point.x,
                screenY = probe.point.y,
                expected = expected,
                actual = actual,
                kind = popup.kind?.name,
                resource = popup.resource?.name,
                monsterName = popup.monsterName,
                level = popup.level,
                quantity = popup.quantity,
                occupied = popup.occupied,
                incomingTroops = popup.incomingTroops,
                popup = popup.isPopup,
                coordinateDelta = delta,
                acceptedForCalibration = accepted
            )
        )

        // Only feed the affine model with an expectation that agrees with the
        // popup (or with an intentionally expectation-free semantic probe).
        // A bad predicted tap must never poison the transform used for the
        // next generation of probes.
        // Never combine samples across a camera-motion boundary. The popup
        // coordinate is authoritative for the tapped cell, but the screen
        // position is only useful for calibration in the current viewport.
        if (accepted && probe.cameraEpoch == cameraEpoch && probe.stableAtDispatch && lastCameraStable) {
            calibrationStore.addSample(actual, probe.point)
            calibrator.addSample(actual, probe.point)
        }
        sessionSamples++
        learnedCoordinates.add(worldKey(actual))
        enqueueNeighbors(actual)
    }

    private fun enqueueNeighbors(center: WorldCoordinate) {
        val deltas = arrayOf(
            -1 to -1, 0 to -1, 1 to -1,
            -1 to 0,             1 to 0,
            -1 to 1, 0 to 1, 1 to 1
        )
        for ((dx, dy) in deltas) {
            if (frontier.size >= MAX_FRONTIER) break
            val c = WorldCoordinate(center.kingdom, center.x + dx, center.y + dy)
            val key = worldKey(c)
            if (learnedCoordinates.contains(key) || !queuedWorld.add(key)) continue
            frontier.addLast(c)
        }
    }

    private fun nextSyntheticProbe(width: Int, height: Int, cameraStable: Boolean): Probe? {
        if (!cameraStable) return null
        val calibration = calibrator.fit() ?: return null
        if (calibration.rmsErrorPx > CALIBRATION_RMS_FOR_SYNTHETIC_PX) return null
        if (calibrator.sampleCount() < MIN_SYNTHETIC_SAMPLES) return null
        if (calibration.worldSpanX < MIN_WORLD_SPAN || calibration.worldSpanY < MIN_WORLD_SPAN) return null
        if (frontier.isEmpty()) return null

        repeat(frontier.size) {
            val coordinate = frontier.removeFirst()
            queuedWorld.remove(worldKey(coordinate))
            if (learnedCoordinates.contains(worldKey(coordinate))) return@repeat
            val point = calibration.predict(coordinate)
            if (!safeMapPoint(point, width, height)) {
                // Keep the world cell for a later viewport/camera position.
                if (queuedWorld.add(worldKey(coordinate))) frontier.addLast(coordinate)
                return@repeat
            }
            return Probe(point, coordinate, "predicted-grid", cameraEpoch, cameraStable)
        }
        return null
    }

    private fun nextQueuedPoint(width: Int, height: Int): Probe? {
        val queued = candidateQueue ?: return null
        candidateQueue = null
        return queued.takeIf { safeMapPoint(it.point, width, height) }
    }

    private fun dispatchProbe(probe: Probe) {
        val service = LmAccessibilityService.instance
        if (service == null) {
            dispatchFailures++
            lastProbeIssue = "ACCESSIBILITY OFF"
            pending = null
            pendingSinceMs = 0L
            attemptedScreen.remove(pointKey(probe.point))
            return
        }
        service.tapGridProbe(
            point = probe.point,
            screenWidth = lastWidth,
            screenHeight = lastHeight
        ) { dispatched ->
            synchronized(this) {
                if (dispatched) {
                    dispatchSuccesses++
                    attemptedScreen[pointKey(probe.point)] = System.currentTimeMillis()
                    lastProbeIssue = null
                } else {
                    dispatchFailures++
                    lastProbeIssue = "PROBE DISPATCH FAILED"
                    attemptedScreen.remove(pointKey(probe.point))
                }
                if (!dispatched && pending?.point == probe.point) {
                    pending = null
                    pendingSinceMs = 0L
                    candidateFrames = 0
                }
            }
        }
    }

    @Synchronized
    fun statusLine(): String {
        if (!active) return "Grid learning: OFF"
        val fit = calibrator.fit()
        val rms = fit?.rmsErrorPx?.let { " • RMS %.1fpx".format(it) } ?: ""
        val dispatch = "dispatch=" + dispatchSuccesses + "/" + probeAttempts
        val issue = lastProbeIssue?.let { " • " + it } ?: ""
        val candidate = " • candidates=" + candidateDetections + "/" + safeCandidateDetections
        return "GRID LEARN • probes=" + sessionSamples +
            " • saved=" + store.sampleCount() +
            " • frontier=" + frontier.size +
            candidate +
            " • " + dispatch +
            " • camera=" + if (lastCameraStable) "STABLE" else "UNSTABLE" +
            " • " + if (pending != null) "WAITING FOR POPUP" + rms else "READY" + rms + issue
    }

    private fun safeMapPoint(point: ScreenPoint, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val x = point.x
        val y = point.y
        if (x < width * 0.10f || x > width * 0.90f) return false
        if (y < height * 0.16f || y > height * 0.82f) return false
        // Never probe through the touchable diagnostics overlay. The learner
        // must not be able to activate timer/buttons while learning the grid.
        if (OverlayService.isPointInsideInteractiveOverlay(x, y)) return false
        if (y < height * 0.30f && x in (width * 0.34f)..(width * 0.76f)) return false
        return true
    }

    private fun worldKey(c: WorldCoordinate): String =
        c.kingdom.toString() + ":" + c.x + ":" + c.y

    private fun pointKey(p: ScreenPoint): String =
        (p.x.toInt() / 12).toString() + ":" + (p.y.toInt() / 12)

    private fun distance(a: ScreenPoint?, b: ScreenPoint?): Float {
        if (a == null || b == null) return Float.MAX_VALUE
        return hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble()).toFloat()
    }
}
