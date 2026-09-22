package com.coolhiman.lordsassistant.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import com.coolhiman.lordsassistant.map.LiveMapScanner
import com.coolhiman.lordsassistant.overlay.OverlayService
import com.coolhiman.lordsassistant.accessibility.LmAccessibilityService
import com.coolhiman.lordsassistant.target.ActionDiagnosticsSnapshot
import com.coolhiman.lordsassistant.target.ActionDiagnosticsStore
import com.coolhiman.lordsassistant.target.ActionLifecycleState
import com.coolhiman.lordsassistant.target.ActionLifecycleFailure
import com.coolhiman.lordsassistant.target.ActionOrchestrator
import com.coolhiman.lordsassistant.target.ActionRecoveryPolicy
import com.coolhiman.lordsassistant.target.ActionManualRecoveryStore
import com.coolhiman.lordsassistant.target.ActionExecutionJournal
import com.coolhiman.lordsassistant.target.ActionRecoveryEpochStore
import com.coolhiman.lordsassistant.target.ActionAttemptIdStore
import com.coolhiman.lordsassistant.target.ActionDispatchProvenance
import com.coolhiman.lordsassistant.target.ActionRecoveryQuarantine
import com.coolhiman.lordsassistant.target.ActionScheduler
import com.coolhiman.lordsassistant.target.LiveActionSchedulerAdapter
import com.coolhiman.lordsassistant.target.ActionScheduleCandidate
import com.coolhiman.lordsassistant.target.ActionSchedulerSafetyState
import com.coolhiman.lordsassistant.target.ActionAuditEvent
import com.coolhiman.lordsassistant.target.ActionAuditEventType
import com.coolhiman.lordsassistant.target.ActionAuditLogStore
import com.coolhiman.lordsassistant.vision.FrameAnalyzer
import com.coolhiman.lordsassistant.vision.ImageBitmapConverter
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var liveScanner: LiveMapScanner
    private lateinit var actionOrchestrator: ActionOrchestrator
    private lateinit var actionSchedulerAdapter: LiveActionSchedulerAdapter
    private lateinit var actionJournal: ActionExecutionJournal
    private lateinit var actionAuditLog: ActionAuditLogStore
    private lateinit var recoveryEpochStore: ActionRecoveryEpochStore
    private lateinit var actionAttemptIdStore: ActionAttemptIdStore
    private val recoveryQuarantine = ActionRecoveryQuarantine()
    private var recoveryEpochPersistenceHealthy = true
    private var reconciledInitialEpoch = 0L
    private var previousScan: com.coolhiman.lordsassistant.map.LiveMapScanResult? = null
    private val busy = AtomicBoolean(false)
    private val processingToken = java.util.concurrent.atomic.AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())
    // Frame-analysis timeout must not depend on the main looper. The capture
    // listener and UI work share that looper, so a stalled callback must still
    // be able to release the frame gate and invalidate the old token safely.
    private val frameTimeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var frameTimeoutFuture: ScheduledFuture<*>? = null
    private var lastScanMs = 0L
    private val viewportGuard = ViewportGuard()
    private val captureHealth = CaptureHealthTracker()
    private val captureWatchdog = CaptureWatchdog()
    private val captureRuntime = CaptureRuntimeSessionTracker()
    private val memoryPressurePolicy = MemoryPressurePolicy()
    private val processingLatency = ProcessingLatencyTracker()
    private val captureQualityPolicy = CaptureQualityPolicy()
    private lateinit var captureDiagnosticsStore: CaptureSessionDiagnosticsStore
    private var captureSessionActive = false
    private val captureWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!captureSessionActive) return
            val now = System.currentTimeMillis()
            if (captureWatchdog.check(now)) {
                captureRuntime.recordStall()
                OverlayService.instance?.showStatus("CAPTURE STALLED • scanner stopped safely")
                persistCaptureDiagnostics()
                stopCaptureResources(CaptureStopReason.CAPTURE_STALLED)
                stopSelf()
                return
            }
            // Keep a lightweight live snapshot persisted even before the first
            // successful CV/OCR result. This makes MediaProjection/ImageReader
            // setup failures and zero-frame sessions diagnosable on-device.
            persistCaptureDiagnostics()
            handler.postDelayed(this, 1000L)
        }
    }

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val MAX_FRAME_AGE_MS = 1500L
        private const val FRAME_ANALYSIS_TIMEOUT_MS = 5000L
    }

    private fun persistRecoveryEpoch(): Boolean {
        recoveryEpochPersistenceHealthy = recoveryEpochStore.write(actionOrchestrator.currentRecoveryEpoch)
        if (!recoveryEpochPersistenceHealthy) {
            actionAuditLog.appendIfChanged(
                ActionAuditEvent(
                    timestampMs = System.currentTimeMillis(),
                    type = ActionAuditEventType.RECOVERY_EPOCH_PERSISTENCE_FAILED,
                    recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                    detail = "Recovery epoch could not be durably persisted; automatic execution remains fail-closed"
                )
            )
        }
        return recoveryEpochPersistenceHealthy
    }

    private fun persistCaptureDiagnostics() {
        if (!::captureDiagnosticsStore.isInitialized) return
        val runtime = captureRuntime.snapshot()
        val memory = memoryPressurePolicy.evaluate(
            usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
            maxBytes = Runtime.getRuntime().maxMemory()
        )
        val diagnostics = CaptureSessionDiagnostics.snapshot(
            capture = captureHealth.snapshot(),
            runtime = runtime,
            latency = processingLatency.snapshot(),
            quality = captureQualityPolicy.assess(
                captureHealth.snapshot(),
                processingLatency.snapshot(),
                memory
            ),
            candidateRejectionCounts = actionAuditLog.rejectionCountsForSession(runtime.sessionId)
        )
        captureDiagnosticsStore.save(diagnostics)
    }

    private fun stopCaptureResources(reason: CaptureStopReason = CaptureStopReason.USER_STOP) {
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        frameTimeoutFuture?.cancel(false)
        frameTimeoutFuture = null
        reader = null
        projection?.stop()
        projection = null
        viewportGuard.reset()
        handler.removeCallbacks(captureWatchdogRunnable)
        captureWatchdog.stop()
        captureHealth.stop()
        val wasActive = captureRuntime.snapshot().active
        if (wasActive) {
            captureRuntime.stop(reason)
            val memory = memoryPressurePolicy.evaluate(
                usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                maxBytes = Runtime.getRuntime().maxMemory()
            )
            val finalRuntime = captureRuntime.snapshot()
            val finalDiagnostics = CaptureSessionDiagnostics.snapshot(
                capture = captureHealth.snapshot(),
                runtime = finalRuntime,
                latency = processingLatency.snapshot(),
                quality = captureQualityPolicy.assess(
                    captureHealth.snapshot(),
                    processingLatency.snapshot(),
                    memory
                ),
                candidateRejectionCounts = actionAuditLog.rejectionCountsForSession(finalRuntime.sessionId)
            )
            captureDiagnosticsStore.save(finalDiagnostics)
            captureDiagnosticsStore.archive(finalDiagnostics)
        }
        captureSessionActive = false
    }

    override fun onCreate() {
        super.onCreate()
        analyzer = FrameAnalyzer()
        captureDiagnosticsStore = CaptureSessionDiagnosticsStore(this)
        liveScanner = LiveMapScanner(this)
        recoveryEpochStore = ActionRecoveryEpochStore(this)
        actionAttemptIdStore = ActionAttemptIdStore(this)
        actionJournal = ActionExecutionJournal(this)
        actionAuditLog = ActionAuditLogStore(this)
        actionSchedulerAdapter = LiveActionSchedulerAdapter(ActionScheduler())

        // Reconcile all durable provenance sources before creating the live
        // orchestrator. A stale journal epoch must never be allowed to seed a
        // lower recovery epoch after restart.
        val persistedRecoveryEpoch = recoveryEpochStore.read()
        val inFlightEntry = actionJournal.readInFlight()
        reconciledInitialEpoch = maxOf(
            persistedRecoveryEpoch,
            inFlightEntry?.recoveryEpoch ?: 0L
        )
        actionOrchestrator = ActionOrchestrator(
            initialRecoveryEpoch = reconciledInitialEpoch,
            attemptIdAllocator = { minimumPreviousId -> actionAttemptIdStore.allocateNext(minimumPreviousId) }
        )
        inFlightEntry?.let { entry ->
            actionOrchestrator.restoreUnknown(entry.attemptId)
            persistRecoveryEpoch()
            recoveryQuarantine.restore(
                attemptId = entry.attemptId,
                recoveryEpoch = entry.recoveryEpoch,
                captureSessionId = entry.captureSessionId
            )
            actionAuditLog.append(
                ActionAuditEvent(
                    timestampMs = System.currentTimeMillis(),
                    type = ActionAuditEventType.RESTART_QUARANTINE,
                    attemptId = entry.attemptId,
                    recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                    captureSessionId = entry.captureSessionId,
                    detail = "Recovered in-flight action from capture session " +
                        (entry.captureSessionId?.toString() ?: "legacy/unknown") +
                        "; recovered epoch=" + entry.recoveryEpoch +
                        "; automatic retry quarantined"
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        stopCaptureResources()
        captureHealth.reset()
        startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: run {
            stopCaptureResources(CaptureStopReason.CAPTURE_SETUP_FAILED)
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)
        if (projection == null) {
            captureRuntime.recordFailure("MediaProjection unavailable")
            persistCaptureDiagnostics()
            stopCaptureResources(CaptureStopReason.CAPTURE_SETUP_FAILED)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        captureSessionActive = true

        // A new MediaProjection session is a hard temporal/provenance boundary.
        // Do not let the previous session's frame, scheduler queue, camera
        // continuity, or target stability satisfy current-session safety checks.
        previousScan = null
        lastScanMs = 0L
        actionSchedulerAdapter.resetForCaptureSession()
        liveScanner.resetCaptureSession()

        val durableSessionId = captureDiagnosticsStore.allocateNextSessionId()
        captureRuntime.start(durableSessionId)
        persistCaptureDiagnostics()

        val captureStartedAt = System.currentTimeMillis()
        captureHealth.start(captureStartedAt)
        captureWatchdog.start(captureStartedAt)
        handler.removeCallbacks(captureWatchdogRunnable)
        handler.postDelayed(captureWatchdogRunnable, 1000L)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (captureSessionActive) {
                    captureSessionActive = false
                    stopCaptureResources(CaptureStopReason.PROJECTION_STOPPED)
                    stopSelf()
                }
            }
        }, handler)

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        reader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )

        reader?.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            captureHealth.frameArrived(now)
            captureWatchdog.frameArrived(now)
            if (now - lastScanMs < 500L || !busy.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                captureHealth.frameDropped()
                return@setOnImageAvailableListener
            }
            lastScanMs = now
            val memoryBeforeImage = memoryPressurePolicy.evaluate(
                usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                maxBytes = Runtime.getRuntime().maxMemory()
            )
            if (memoryBeforeImage.level == MemoryPressureLevel.CRITICAL) {
                captureRuntime.recordFailure("Critical memory pressure")
                captureHealth.frameDropped()
                OverlayService.instance?.showStatus("MEMORY PRESSURE • scanner stopped safely")
                busy.set(false)
                stopCaptureResources(CaptureStopReason.CAPTURE_ERROR)
                stopSelf()
                return@setOnImageAvailableListener
            }
            if (memoryBeforeImage.level == MemoryPressureLevel.WARNING) {
                captureHealth.frameDropped()
                OverlayService.instance?.showStatus("MEMORY PRESSURE • frame dropped safely")
                busy.set(false)
                return@setOnImageAvailableListener
            }

            val image = try {
                source.acquireLatestImage()
            } catch (_: Throwable) {
                null
            }
            if (image == null) {
                captureHealth.frameDropped()
                busy.set(false)
                return@setOnImageAvailableListener
            }

            if (image.timestamp > 0L) {
                val ageNs = System.nanoTime() - image.timestamp
                if (ageNs > MAX_FRAME_AGE_MS * 1_000_000L) {
                    image.close()
                    captureHealth.frameDropped(stale = true)
                    busy.set(false)
                    return@setOnImageAvailableListener
                }
            }

            val bitmap = try {
                ImageBitmapConverter.toBitmap(image)
            } catch (_: Throwable) {
                captureHealth.frameDropped()
                busy.set(false)
                return@setOnImageAvailableListener
            } finally {
                image.close()
            }

            if (!viewportGuard.accept(bitmap.width, bitmap.height)) {
                // ImageReader/VirtualDisplay dimensions are fixed for this
                // session. A dimension change therefore indicates rotation or
                // another display-geometry transition; do not re-baseline onto
                // an old reader and risk applying stale screen/world geometry.
                captureHealth.viewportReset()
                captureRuntime.recordViewportChange()
                captureHealth.frameDropped()
                OverlayService.instance?.showStatus(
                    "DISPLAY CHANGED • scanner stopped safely; restart scanner"
                )
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
                stopCaptureResources(CaptureStopReason.VIEWPORT_CHANGED)
                stopSelf()
                return@setOnImageAvailableListener
            }

            captureHealth.frameAccepted()

            val frameStartedAt = System.currentTimeMillis()
            val frameToken = processingToken.incrementAndGet()
            frameTimeoutFuture?.cancel(false)
            frameTimeoutFuture = frameTimeoutExecutor.schedule({
                if (processingToken.get() == frameToken && busy.compareAndSet(true, false)) {
                    processingToken.compareAndSet(frameToken, frameToken + 1L)
                    captureRuntime.recordFailure("Frame analysis timeout (independent watchdog)")
                    captureHealth.frameDropped()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    handler.post {
                        OverlayService.instance?.showStatus("FRAME ANALYSIS TIMEOUT • retrying safely")
                    }
                }
            }, FRAME_ANALYSIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            try {
                analyzer.analyze(bitmap, defaultKingdom = 0) { result ->
                    frameTimeoutFuture?.cancel(false)
                    frameTimeoutFuture = null
                    if (processingToken.get() != frameToken) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return@analyze
                    }
                    try {
                    val scanStartedAt = System.currentTimeMillis()
                    val scan = liveScanner.scan(
                        bitmap = bitmap,
                        defaultKingdom = result.coordinate?.kingdom ?: 0,
                        ocrCoordinate = result.coordinate,
                        textRegions = result.textRegions,
                        popupState = result.popup
                    )
                    val scanProcessingMs = System.currentTimeMillis() - scanStartedAt
                    val totalProcessingMs = System.currentTimeMillis() - frameStartedAt
                    processingLatency.record(result.ocrProcessingMs, scanProcessingMs, totalProcessingMs)
                    val latency = processingLatency.snapshot()
                    val captureSnapshot = captureHealth.snapshot()
                    val memorySnapshot = memoryPressurePolicy.evaluate(
                        usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                        maxBytes = Runtime.getRuntime().maxMemory()
                    )
                    val captureQuality = captureQualityPolicy.assess(captureSnapshot, latency, memorySnapshot)
                    val origin = scan.origin
                    val status = buildString {
                        append("LIVE MAP  •  ")
                        append(scan.detectedTiles)
                        append(" tiles / ")
                        append(scan.plan.ranked.size)
                        append(" targets")
                        if (origin != null) {
                            append("\nK").append(origin.kingdom)
                            append(" X").append(origin.x)
                            append(" Y").append(origin.y)
                        } else {
                            append("\nCalibrate + expose K/X/Y for ranking")
                        }
                        append("\nCamera: ").append(scan.cameraState.name)
                        append("  Validation: ").append(scan.validation.stage.name)
                        append("\nAction: ").append(scan.actionButton?.kind?.name ?: "NOT DETECTED")
                        if (scan.validation.reasons.isNotEmpty()) {
                            append("\nBlocked: ").append(scan.validation.reasons.joinToString(", ") { reason ->
                                reason.name.replace('_', ' ')
                            })
                        }
                        append("\n").append(scan.processingMs).append("ms")
                        append("\nCapture quality: ").append(captureQuality.name)
                    }
                    val prefs = com.coolhiman.lordsassistant.data.PreferencesStore(this@ScreenCaptureService).load()

                    // Feed only independently validated current-frame candidates
                    // with a real detected action-button point into the scheduler.
                    // Ranked map-memory targets without a verified interaction
                    // control are never manufactured into actionable candidates.
                    val reconciliation = com.coolhiman.lordsassistant.target.LiveActionCandidateReconciler
                        .reconcile(scan.actionCandidates)
                    val currentCandidates = reconciliation.eligible.map { candidate ->
                        ActionScheduleCandidate(
                            target = candidate.target,
                            priority = 0,
                            plannerRank = candidate.plannerRank,
                            plannerScore = candidate.plannerScore,
                            stabilityFrames = candidate.stability.consecutiveFrames,
                            validationSafe = true,
                            queuedAtMs = now
                        )
                    }
                    actionSchedulerAdapter.update(currentCandidates).forEach { droppedTarget ->
                        actionAuditLog.appendIfChanged(
                            ActionAuditEvent(
                                timestampMs = now,
                                type = ActionAuditEventType.CANDIDATE_DROPPED,
                                captureSessionId = captureRuntime.snapshot().sessionId,
                                target = droppedTarget,
                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                detail = "removed by latest-scan reconciliation"
                            )
                        )
                    }
                    scan.actionCandidates.forEach { candidate ->
                        val rejection = com.coolhiman.lordsassistant.target.LiveActionCandidatePolicy.rejectionReason(candidate)
                        if (rejection == null) {
                            actionAuditLog.appendIfChanged(
                                ActionAuditEvent(
                                    timestampMs = now,
                                    type = ActionAuditEventType.CANDIDATE_QUEUED,
                                    captureSessionId = captureRuntime.snapshot().sessionId,
                                    target = candidate.target,
                                    recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                    detail = "independently validated current-frame candidate"
                                )
                            )
                        } else {
                            actionAuditLog.appendIfChanged(
                                ActionAuditEvent(
                                    timestampMs = now,
                                    type = ActionAuditEventType.CANDIDATE_REJECTED,
                                    captureSessionId = captureRuntime.snapshot().sessionId,
                                    target = candidate.target,
                                    recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                    detail = rejection.name
                                )
                            )
                        }
                    }
                    val liveCaptureSessionId = captureRuntime.snapshot().sessionId
                    if (!prefs.automaticActions && ActionManualRecoveryStore.consumeResetRequest() &&
                        actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.UNKNOWN
                    ) {
                        val recoveryResult = actionOrchestrator.reset()
                        val recoveryPersisted = persistRecoveryEpoch()
                        actionAuditLog.append(
                            ActionAuditEvent(
                                timestampMs = now,
                                type = ActionAuditEventType.RECOVERY_RESET,
                                            captureSessionId = liveCaptureSessionId,
                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                detail = if (recoveryPersisted && recoveryResult.lifecycle.state == ActionLifecycleState.IDLE) {
                                    "Deliberate UNKNOWN recovery completed"
                                } else {
                                    "Deliberate UNKNOWN recovery did not establish a fresh durable boundary"
                                }
                            )
                        )
                        val journalCleared = if (recoveryPersisted && recoveryResult.lifecycle.state == ActionLifecycleState.IDLE) {
                            actionJournal.clear()
                        } else {
                            false
                        }
                        val quarantineReleased = recoveryQuarantine.releaseAfterDeliberateRecovery(
                            lifecycleIdle = recoveryResult.lifecycle.state == ActionLifecycleState.IDLE,
                            recoveryEpochPersisted = recoveryPersisted,
                            journalCleared = journalCleared,
                            currentCaptureSessionId = liveCaptureSessionId
                        )
                        if (quarantineReleased) {
                            previousScan = null
                        }
                        ActionDiagnosticsStore.latest?.let { latest ->
                            ActionDiagnosticsStore.latest = latest.copy(
                                lifecycle = actionOrchestrator.lifecycleSnapshot,
                                evidence = null,
                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                recoveryEpochPersistenceHealthy = recoveryEpochPersistenceHealthy,
                                journalAttemptId = actionJournal.readInFlight()?.attemptId,
                                journalRecoveryEpoch = actionJournal.readInFlight()?.recoveryEpoch,
                                journalRecoveryEpochPersisted = actionJournal.readInFlight()?.recoveryEpochPersisted == true,
                                reconciledInitialEpoch = reconciledInitialEpoch,
                                restartQuarantine = recoveryQuarantine.active,
                                timestampMs = now
                            )
                        }
                    }
                    if (!recoveryEpochPersistenceHealthy &&
                        actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.IDLE &&
                        !recoveryQuarantine.active
                    ) {
                        if (persistRecoveryEpoch() && actionJournal.clear()) {
                            previousScan = null
                        }
                    }
                    val active = actionOrchestrator.lifecycleSnapshot.state
                    if (prefs.automaticActions && recoveryEpochPersistenceHealthy && !recoveryQuarantine.active) {
                        when {
                            ActionRecoveryPolicy.mayStartAutomaticAttempt(actionOrchestrator.lifecycleSnapshot) -> {
                                val previous = previousScan
                                val safetyState = ActionSchedulerSafetyState(
                                    lifecycle = actionOrchestrator.lifecycleSnapshot,
                                    automaticActionsEnabled = prefs.automaticActions,
                                    restartQuarantine = recoveryQuarantine.active,
                                    recoveryEpochPersistenceHealthy = recoveryEpochPersistenceHealthy
                                )
                                val decision = actionSchedulerAdapter.select(now, safetyState)
                                if (decision.candidate == null) {
                                    actionAuditLog.appendIfChanged(
                                        ActionAuditEvent(
                                            timestampMs = now,
                                            type = ActionAuditEventType.SCHEDULER_BLOCKED,
                                            captureSessionId = liveCaptureSessionId,
                                            recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                            detail = decision.reason?.name ?: "NO_DECISION"
                                        )
                                    )
                                }
                                val scheduled = if (decision.candidate != null) {
                                    actionSchedulerAdapter.claim(now, safetyState).candidate
                                } else null
                                val previousFrame = previous
                                val previousCandidate = scheduled?.let { selected ->
                                    previousFrame?.actionCandidates?.firstOrNull { it.target.identity() == selected.target.identity() }
                                }
                                val currentCandidate = scheduled?.let { selected ->
                                    scan.actionCandidates.firstOrNull { it.target.identity() == selected.target.identity() }
                                }
                                if (previousCandidate != null &&
                                    currentCandidate != null &&
                                    previousCandidate.validation.safe &&
                                    previousCandidate.validation.stage == com.coolhiman.lordsassistant.target.TargetValidationStage.SAFE_TO_INTERACT &&
                                    currentCandidate.validation.safe &&
                                    currentCandidate.validation.stage == com.coolhiman.lordsassistant.target.TargetValidationStage.SAFE_TO_INTERACT
                                ) {
                                    actionAuditLog.append(ActionAuditEvent(
                                        timestampMs = now,
                                        type = ActionAuditEventType.CANDIDATE_SELECTED,
                                            captureSessionId = liveCaptureSessionId,
                                        target = scheduled.target,
                                        recoveryEpoch = actionOrchestrator.currentRecoveryEpoch
                                    ))
                                    val requestResult = actionOrchestrator.request(
                                        automaticActionsEnabled = true,
                                        selected = scheduled.target,
                                        validation = previousCandidate.validation,
                                        beforeObservation = previousCandidate.observation,
                                        popupBefore = previousFrame?.popupState,
                                        baselineMarchSignals = previousFrame?.marchSignals.orEmpty(),
                                        captureSessionId = liveCaptureSessionId,
                                        nowMs = now
                                    )
                                    actionAuditLog.append(
                                        ActionAuditEvent(
                                            timestampMs = now,
                                            captureSessionId = liveCaptureSessionId,
                                            type = if (requestResult.lifecycle.failure == ActionLifecycleFailure.ATTEMPT_ID_PERSISTENCE_FAILED) {
                                                ActionAuditEventType.ATTEMPT_ID_PERSISTENCE_FAILED
                                            } else {
                                                ActionAuditEventType.ACTION_REQUESTED
                                            },
                                            attemptId = requestResult.session?.attemptId,
                                            recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                            target = scheduled.target,
                                            detail = requestResult.lifecycle.failure?.name
                                        )
                                    )
                                    actionOrchestrator.revalidate(
                                        latestObservation = currentCandidate.observation,
                                        latestValidation = currentCandidate.validation,
                                        latestAction = currentCandidate.actionButton
                                    )
                                    if (actionOrchestrator.lifecycleSnapshot.state != ActionLifecycleState.REVALIDATED) {
                                        actionAuditLog.appendIfChanged(
                                            ActionAuditEvent(
                                                timestampMs = now,
                                                type = ActionAuditEventType.REVALIDATION_FAILED,
                                            captureSessionId = liveCaptureSessionId,
                                                attemptId = actionOrchestrator.session?.attemptId,
                                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                                target = scheduled.target,
                                                detail = actionOrchestrator.lifecycleSnapshot.failure?.name
                                            )
                                        )
                                    }
                                    if (actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.REVALIDATED) {
                                        actionAuditLog.append(
                                            ActionAuditEvent(
                                                timestampMs = now,
                                                type = ActionAuditEventType.ACTION_REVALIDATED,
                                            captureSessionId = liveCaptureSessionId,
                                                attemptId = actionOrchestrator.session?.attemptId,
                                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                                target = scheduled.target
                                            )
                                        )
                                        val session = actionOrchestrator.session
                                        val provenance = session?.let {
                                            ActionDispatchProvenance(
                                                attemptId = it.attemptId,
                                                recoveryEpoch = it.recoveryEpoch,
                                                startedAtMs = now,
                                                captureSessionId = liveCaptureSessionId
                                            )
                                        }
                                        if (provenance != null &&
                                            provenance.matches(actionOrchestrator.session) &&
                                            actionJournal.markInFlight(provenance)
                                        ) {
                                            actionAuditLog.append(ActionAuditEvent(
                                                timestampMs = now,
                                                type = ActionAuditEventType.DISPATCH_BARRIER_OPENED,
                                            captureSessionId = liveCaptureSessionId,
                                                attemptId = provenance.attemptId,
                                                recoveryEpoch = provenance.recoveryEpoch,
                                                target = scheduled.target
                                            ))
                                            // Keep scheduler in-flight state aligned with the
                                            // durable dispatch barrier. This blocks another live
                                            // selection until the guarded dispatch returns.
                                            actionSchedulerAdapter.markDispatchStarted(now)
                                            actionOrchestrator.dispatch(now) {
                                                runCatching {
                                                    LmAccessibilityService.instance?.tapRevalidated(
                                                        selected = currentCandidate.target,
                                                        latestObservation = currentCandidate.observation,
                                                        latestValidation = currentCandidate.validation,
                                                        latestAction = currentCandidate.actionButton
                                                    ) == true
                                                }.getOrDefault(false)
                                            }.also { result ->
                                                actionSchedulerAdapter.markActionFinished()
                                                actionAuditLog.append(ActionAuditEvent(
                                                    timestampMs = now,
                                                    captureSessionId = liveCaptureSessionId,
                                                    type = if (result.lifecycle.state == ActionLifecycleState.FAILED) ActionAuditEventType.DISPATCH_FAILED else ActionAuditEventType.DISPATCH_SUCCEEDED,
                                                    attemptId = provenance.attemptId,
                                                    recoveryEpoch = provenance.recoveryEpoch,
                                                    target = scheduled.target,
                                                    detail = result.lifecycle.state.name
                                                ))
                                                if (result.lifecycle.state == ActionLifecycleState.FAILED) actionJournal.clear()
                                            }
                                        } else if (provenance != null) {
                                            actionAuditLog.appendIfChanged(
                                                ActionAuditEvent(
                                                    timestampMs = now,
                                                    type = ActionAuditEventType.DISPATCH_BARRIER_FAILED,
                                            captureSessionId = liveCaptureSessionId,
                                                    attemptId = provenance.attemptId,
                                                    recoveryEpoch = provenance.recoveryEpoch,
                                                    target = scheduled.target,
                                                    detail = "durable in-flight journal barrier could not be committed"
                                                )
                                            )
                                            actionOrchestrator.reset()
                                            persistRecoveryEpoch()
                                            actionJournal.clear()
                                            // Durable epoch health remains the execution gate.
                                        }
                                    }
                                }
                            }
                            active == ActionLifecycleState.WAITING_FOR_RESULT -> {
                                actionOrchestrator.observeMarch(scan.marchSignals, now)
                                val verification = actionOrchestrator.verifyPostAction(
                                    afterObservation = scan.selectedObservation,
                                    popupAfter = scan.popupState,
                                    nowMs = now
                                )
                                if (verification.lifecycle.state == ActionLifecycleState.UNKNOWN) {
                                    val failure = verification.lifecycle.failure
                                    actionAuditLog.appendIfChanged(
                                        ActionAuditEvent(
                                            timestampMs = now,
                                            captureSessionId = liveCaptureSessionId,
                                            type = if (failure == ActionLifecycleFailure.VERIFICATION_TIMEOUT) {
                                                ActionAuditEventType.VERIFICATION_TIMEOUT
                                            } else {
                                                ActionAuditEventType.UNKNOWN_ENTERED
                                            },
                                            attemptId = actionOrchestrator.session?.attemptId,
                                            recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                            detail = failure?.name ?: "POST_ACTION_EVIDENCE_INCONCLUSIVE"
                                        )
                                    )
                                }
                                if (verification.lifecycle.state == ActionLifecycleState.SUCCEEDED ||
                                    verification.lifecycle.state == ActionLifecycleState.FAILED
                                ) {
                                    actionAuditLog.append(ActionAuditEvent(
                                        timestampMs = now,
                                        captureSessionId = liveCaptureSessionId,
                                        type = if (verification.lifecycle.state == ActionLifecycleState.SUCCEEDED) ActionAuditEventType.VERIFICATION_SUCCEEDED else ActionAuditEventType.VERIFICATION_FAILED,
                                        attemptId = actionOrchestrator.session?.attemptId,
                                        recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                        detail = verification.lifecycle.state.name
                                    ))
                                    actionJournal.clear()
                                    if (verification.lifecycle.state == ActionLifecycleState.SUCCEEDED) {
                                        actionOrchestrator.session?.selected?.let { completedTarget ->
                                            actionSchedulerAdapter.markTargetCompleted(completedTarget, now)
                                        }
                                    }
                                }
                            }
                        }
                    } else if (active != ActionLifecycleState.IDLE && !recoveryQuarantine.active) {
                        val resetResult = actionOrchestrator.reset()
                        val persisted = persistRecoveryEpoch()
                        if (resetResult.lifecycle.state == ActionLifecycleState.IDLE && persisted) {
                            actionJournal.clear()
                        }
                    }

                    ActionDiagnosticsStore.latest = ActionDiagnosticsSnapshot.fromScan(
                        scan = scan,
                        lifecycle = actionOrchestrator.lifecycleSnapshot,
                        evidence = actionOrchestrator.lastPostActionEvidence,
                        actionAttemptId = actionOrchestrator.session?.attemptId
                            ?: actionJournal.readInFlight()?.attemptId,
                        recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                        recoveryEpochPersistenceHealthy = recoveryEpochPersistenceHealthy,
                        journalAttemptId = actionJournal.readInFlight()?.attemptId,
                        journalRecoveryEpoch = actionJournal.readInFlight()?.recoveryEpoch,
                        journalRecoveryEpochPersisted = actionJournal.readInFlight()?.recoveryEpochPersisted == true,
                        journalCaptureSessionId = actionJournal.readInFlight()?.captureSessionId,
                        reconciledInitialEpoch = reconciledInitialEpoch,
                        restartQuarantine = recoveryQuarantine.active,
                        timestampMs = now
                    )

                    val health = captureHealth.snapshot()
                    val runtime = captureRuntime.snapshot()
                    val memory = memoryPressurePolicy.evaluate(
                        usedBytes = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                        maxBytes = Runtime.getRuntime().maxMemory()
                    )
                    captureHealth.processingFinished(System.currentTimeMillis() - now)
                    val diagnostics = CaptureSessionDiagnostics.snapshot(
                        capture = captureHealth.snapshot(),
                        runtime = runtime,
                        latency = processingLatency.snapshot(),
                        quality = captureQualityPolicy.assess(
                            captureHealth.snapshot(),
                            processingLatency.snapshot(),
                            memory
                        ),
                        candidateRejectionCounts = actionAuditLog.rejectionCountsForSession(runtime.sessionId)
                    )
                    captureDiagnosticsStore.save(diagnostics)
                    OverlayService.instance?.showStatus(
                        status + "\nAuto lifecycle: " + actionOrchestrator.lifecycleSnapshot.state.name +
                            "\nCapture: " + diagnostics.averageProcessingMs.toLong() + "ms avg / " +
                            diagnostics.dropRatePercent.toInt() + "% dropped / " +
                            diagnostics.staleFrames + " stale" +
                            "\nSession: #" + diagnostics.sessionId + " / restarts " + diagnostics.restartCount +
                            " / stalls " + diagnostics.stallCount +
                            " / viewport changes " + diagnostics.viewportChangeCount +
                            " / memory " + memory.level.name +
                            "\nOCR: " + diagnostics.averageOcrMs.toLong() + "ms avg / Scan: " +
                            diagnostics.averageScannerMs.toLong() + "ms avg / Quality: " +
                            diagnostics.quality.name
                    )
                    OverlayService.instance?.showTargets(scan.plan.ranked)
                    previousScan = scan
                    } finally {
                        if (processingToken.compareAndSet(frameToken, frameToken + 1L)) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            busy.set(false)
                        }
                    }
                }
            } catch (error: Throwable) {
                frameTimeoutFuture?.cancel(false)
                frameTimeoutFuture = null
                processingToken.compareAndSet(frameToken, frameToken + 1L)
                captureRuntime.recordFailure(
                    "Frame analyzer exception: " + (error.message ?: error.javaClass.simpleName).take(160)
                )
                captureHealth.frameDropped()
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
                OverlayService.instance?.showStatus("FRAME ANALYZER ERROR • retrying safely")
            }
        }, handler)

        try {
            projection?.createVirtualDisplay(
            "LMCompanion",
            metrics.widthPixels,
            metrics.heightPixels,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface,
            null,
            null
            )
        } catch (_: Throwable) {
            captureRuntime.recordFailure("VirtualDisplay creation failed")
            stopCaptureResources(CaptureStopReason.CAPTURE_SETUP_FAILED)
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun createChannel() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("capture", "Screen scanner", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification() = Notification.Builder(this, "capture")
        .setContentTitle("LM Companion")
        .setContentText("Screen scanner active")
        .setSmallIcon(android.R.drawable.ic_menu_search)
        .build()

    override fun onDestroy() {
        stopCaptureResources(CaptureStopReason.SERVICE_DESTROYED)
        liveScanner.close()
        analyzer.close()
        frameTimeoutExecutor.shutdownNow()
        ActionDiagnosticsStore.latest = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}