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
import com.coolhiman.lordsassistant.target.ActionOrchestrator
import com.coolhiman.lordsassistant.target.ActionRecoveryPolicy
import com.coolhiman.lordsassistant.target.ActionManualRecoveryStore
import com.coolhiman.lordsassistant.target.ActionExecutionJournal
import com.coolhiman.lordsassistant.target.ActionRecoveryEpochStore
import com.coolhiman.lordsassistant.target.ActionAttemptIdStore
import com.coolhiman.lordsassistant.target.ActionDispatchProvenance
import com.coolhiman.lordsassistant.target.ActionScheduler
import com.coolhiman.lordsassistant.target.LiveActionSchedulerAdapter
import com.coolhiman.lordsassistant.target.ActionScheduleCandidate
import com.coolhiman.lordsassistant.target.ActionSchedulerSafetyState
import com.coolhiman.lordsassistant.target.ActionAuditEvent
import com.coolhiman.lordsassistant.target.ActionAuditEventType
import com.coolhiman.lordsassistant.target.ActionAuditLogStore
import com.coolhiman.lordsassistant.vision.FrameAnalyzer
import com.coolhiman.lordsassistant.vision.ImageBitmapConverter
import java.util.concurrent.atomic.AtomicBoolean

class ScreenCaptureService : Service() {
    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
    }

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
    private var restartQuarantine = false
    private var recoveryEpochPersistenceHealthy = true
    private var reconciledInitialEpoch = 0L
    private var previousScan: com.coolhiman.lordsassistant.map.LiveMapScanResult? = null
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var lastScanMs = 0L
    private val viewportGuard = ViewportGuard()

    private fun persistRecoveryEpoch(): Boolean {
        recoveryEpochPersistenceHealthy = recoveryEpochStore.write(actionOrchestrator.currentRecoveryEpoch)
        return recoveryEpochPersistenceHealthy
    }

    override fun onCreate() {
        super.onCreate()
        analyzer = FrameAnalyzer()
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
            restartQuarantine = true
            actionAuditLog.append(
                ActionAuditEvent(
                    timestampMs = System.currentTimeMillis(),
                    type = ActionAuditEventType.RESTART_QUARANTINE,
                    attemptId = entry.attemptId,
                    recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                    detail = "Recovered in-flight action; automatic retry quarantined"
                )
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        startForeground(42, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = manager.getMediaProjection(resultCode, data)

        val metrics = DisplayMetrics()
        val wm = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getMetrics(metrics)

        reader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )

        reader?.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastScanMs < 500L || !busy.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastScanMs = now

            val image = source.acquireLatestImage()
            if (image == null) {
                busy.set(false)
                return@setOnImageAvailableListener
            }

            val bitmap = ImageBitmapConverter.toBitmap(image)
            image.close()
            if (!viewportGuard.accept(bitmap.width, bitmap.height)) {
                if (!bitmap.isRecycled) bitmap.recycle()
                busy.set(false)
                return@setOnImageAvailableListener
            }

            analyzer.analyze(bitmap, defaultKingdom = 0) { result ->
                try {
                    val scan = liveScanner.scan(
                        bitmap = bitmap,
                        defaultKingdom = result.coordinate?.kingdom ?: 0,
                        ocrCoordinate = result.coordinate,
                        textRegions = result.textRegions,
                        popupState = result.popup
                    )
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
                    }
                    val prefs = com.coolhiman.lordsassistant.data.PreferencesStore(this@ScreenCaptureService).load()

                    // Feed only the already validated current-frame target into
                    // the multi-target boundary. Other ranked map targets do not
                    // yet have a verified interaction point, so they must not be
                    // manufactured into actionable scheduler candidates.
                    val currentCandidate = scan.selectedActionTarget?.let { target ->
                        ActionScheduleCandidate(
                            target = target,
                            priority = 1,
                            stabilityFrames = if (scan.targetStability.stable) 2 else 0,
                            validationSafe = scan.validation.safe &&
                                scan.validation.stage == com.coolhiman.lordsassistant.target.TargetValidationStage.SAFE_TO_INTERACT,
                            queuedAtMs = now
                        )
                    }
                    actionSchedulerAdapter.update(
                        listOfNotNull(currentCandidate)
                    )
                    currentCandidate?.let { candidate ->
                        actionAuditLog.appendIfChanged(
                            ActionAuditEvent(
                                timestampMs = now,
                                type = ActionAuditEventType.CANDIDATE_QUEUED,
                                target = candidate.target,
                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                detail = "safe current-frame candidate"
                            )
                        )
                    }
                    if (!prefs.automaticActions && ActionManualRecoveryStore.consumeResetRequest() &&
                        actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.UNKNOWN
                    ) {
                        val recoveryResult = actionOrchestrator.reset()
                        val recoveryPersisted = persistRecoveryEpoch()
                        actionAuditLog.append(
                            ActionAuditEvent(
                                timestampMs = now,
                                type = ActionAuditEventType.RECOVERY_RESET,
                                recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                detail = if (recoveryPersisted && recoveryResult.lifecycle.state == ActionLifecycleState.IDLE) {
                                    "Deliberate UNKNOWN recovery completed"
                                } else {
                                    "Deliberate UNKNOWN recovery did not establish a fresh durable boundary"
                                }
                            )
                        )
                        if (recoveryPersisted && recoveryResult.lifecycle.state == ActionLifecycleState.IDLE) {
                            actionJournal.clear()
                            restartQuarantine = false
                            previousScan = null
                        } else {
                            // Keep the service quarantined if the fresh recovery boundary
                            // cannot be durably persisted. Automatic execution must not
                            // resume with ambiguous epoch provenance.
                            restartQuarantine = true
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
                                restartQuarantine = restartQuarantine,
                                timestampMs = now
                            )
                        }
                    }
                    if (!recoveryEpochPersistenceHealthy && actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.IDLE) {
                        if (persistRecoveryEpoch()) {
                            actionJournal.clear()
                            restartQuarantine = false
                            previousScan = null
                        }
                    }
                    val active = actionOrchestrator.lifecycleSnapshot.state
                    if (prefs.automaticActions && recoveryEpochPersistenceHealthy && !restartQuarantine) {
                        when {
                            ActionRecoveryPolicy.mayStartAutomaticAttempt(actionOrchestrator.lifecycleSnapshot) -> {
                                val previous = previousScan
                                val safetyState = ActionSchedulerSafetyState(
                                    lifecycle = actionOrchestrator.lifecycleSnapshot,
                                    automaticActionsEnabled = prefs.automaticActions,
                                    restartQuarantine = restartQuarantine,
                                    recoveryEpochPersistenceHealthy = recoveryEpochPersistenceHealthy
                                )
                                val decision = actionSchedulerAdapter.select(now, safetyState)
                                if (decision.candidate == null) {
                                    actionAuditLog.appendIfChanged(
                                        ActionAuditEvent(
                                            timestampMs = now,
                                            type = ActionAuditEventType.SCHEDULER_BLOCKED,
                                            recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                            detail = decision.reason?.name ?: "NO_DECISION"
                                        )
                                    )
                                }
                                val scheduled = if (decision.candidate != null) {
                                    actionSchedulerAdapter.claim(now, safetyState).candidate
                                } else null
                                if (previous != null && scheduled != null &&
                                    previous.selectedActionTarget == scheduled.target &&
                                    previous.validation.safe &&
                                    previous.validation.stage == com.coolhiman.lordsassistant.target.TargetValidationStage.SAFE_TO_INTERACT
                                ) {
                                    actionAuditLog.append(ActionAuditEvent(
                                        timestampMs = now,
                                        type = ActionAuditEventType.CANDIDATE_SELECTED,
                                        target = scheduled.target,
                                        recoveryEpoch = actionOrchestrator.currentRecoveryEpoch
                                    ))
                                    val requestResult = actionOrchestrator.request(
                                        automaticActionsEnabled = true,
                                        selected = scheduled.target,
                                        validation = previous.validation,
                                        beforeObservation = previous.selectedObservation,
                                        popupBefore = previous.popupState,
                                        baselineMarchSignals = previous.marchSignals,
                                        nowMs = now
                                    )
                                    actionAuditLog.append(
                                        ActionAuditEvent(
                                            timestampMs = now,
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
                                        latestObservation = scan.selectedObservation,
                                        latestValidation = scan.validation,
                                        latestAction = scan.actionButton
                                    )
                                    if (actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.REVALIDATED) {
                                        actionAuditLog.append(
                                            ActionAuditEvent(
                                                timestampMs = now,
                                                type = ActionAuditEventType.ACTION_REVALIDATED,
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
                                                startedAtMs = now
                                            )
                                        }
                                        if (provenance != null &&
                                            provenance.matches(actionOrchestrator.session) &&
                                            actionJournal.markInFlight(provenance)
                                        ) {
                                            actionAuditLog.append(ActionAuditEvent(
                                                timestampMs = now,
                                                type = ActionAuditEventType.DISPATCH_BARRIER_OPENED,
                                                attemptId = provenance.attemptId,
                                                recoveryEpoch = provenance.recoveryEpoch,
                                                target = scheduled.target
                                            ))
                                            actionOrchestrator.dispatch(now) {
                                            LmAccessibilityService.instance?.tapRevalidated(
                                                selected = previous.selectedActionTarget,
                                                latestObservation = scan.selectedObservation,
                                                latestValidation = scan.validation,
                                                latestAction = scan.actionButton
                                            ) == true
                                            }.also { result ->
                                                actionAuditLog.append(ActionAuditEvent(
                                                    timestampMs = now,
                                                    type = if (result.lifecycle.state == ActionLifecycleState.FAILED) ActionAuditEventType.DISPATCH_FAILED else ActionAuditEventType.DISPATCH_SUCCEEDED,
                                                    attemptId = provenance.attemptId,
                                                    recoveryEpoch = provenance.recoveryEpoch,
                                                    target = scheduled.target,
                                                    detail = result.lifecycle.state.name
                                                ))
                                                if (result.lifecycle.state == ActionLifecycleState.FAILED) actionJournal.clear()
                                            }
                                        } else if (provenance != null) {
                                            actionOrchestrator.reset()
                                            persistRecoveryEpoch()
                                            actionJournal.clear()
                                            restartQuarantine = !recoveryEpochPersistenceHealthy
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
                                if (verification.lifecycle.state == ActionLifecycleState.SUCCEEDED ||
                                    verification.lifecycle.state == ActionLifecycleState.FAILED
                                ) {
                                    actionAuditLog.append(ActionAuditEvent(
                                        timestampMs = now,
                                        type = if (verification.lifecycle.state == ActionLifecycleState.SUCCEEDED) ActionAuditEventType.VERIFICATION_SUCCEEDED else ActionAuditEventType.VERIFICATION_FAILED,
                                        attemptId = actionOrchestrator.session?.attemptId,
                                        recoveryEpoch = actionOrchestrator.currentRecoveryEpoch,
                                        detail = verification.lifecycle.state.name
                                    ))
                                    actionJournal.clear()
                                }
                            }
                        }
                    } else if (active != ActionLifecycleState.IDLE && !restartQuarantine) {
                        val resetResult = actionOrchestrator.reset()
                        persistRecoveryEpoch()
                        if (resetResult.lifecycle.state == ActionLifecycleState.IDLE && recoveryEpochPersistenceHealthy) {
                            actionJournal.clear()
                        }
                        restartQuarantine = resetResult.lifecycle.state != ActionLifecycleState.IDLE ||
                            !recoveryEpochPersistenceHealthy
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
                        reconciledInitialEpoch = reconciledInitialEpoch,
                        restartQuarantine = restartQuarantine,
                        timestampMs = now
                    )

                    OverlayService.instance?.showStatus(
                        status + "\nAuto lifecycle: " + actionOrchestrator.lifecycleSnapshot.state.name
                    )
                    OverlayService.instance?.showTargets(scan.plan.ranked)
                    previousScan = scan
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy.set(false)
                }
            }
        }, handler)

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
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        projection?.stop()
        liveScanner.close()
        analyzer.close()
        ActionDiagnosticsStore.latest = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
