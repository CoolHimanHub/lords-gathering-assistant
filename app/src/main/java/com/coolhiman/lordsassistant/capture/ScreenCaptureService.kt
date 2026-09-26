package com.coolhiman.lordsassistant.capture

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.util.DisplayMetrics
import com.coolhiman.lordsassistant.map.LiveMapScanner
import com.coolhiman.lordsassistant.map.GridLearningController
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
    private var virtualDisplay: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var scannerHud: TextView? = null
    private var scannerHudManager: WindowManager? = null
    private lateinit var analyzer: FrameAnalyzer
    private lateinit var liveScanner: LiveMapScanner
    private lateinit var gridLearningController: GridLearningController
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
    // ImageReader callbacks and full-resolution RGBA -> Bitmap conversion must
    // never share the main/UI looper. A blocked UI thread can otherwise make
    // the capture watchdog observe a false stall even while MediaProjection is
    // delivering frames normally.
    private val captureThread = HandlerThread("LM-Capture")
    private val captureHandler: Handler
    init {
        captureThread.start()
        captureHandler = Handler(captureThread.looper)
    }
    // Frame-analysis timeout must not depend on the main looper. The capture
    // listener and UI work share that looper, so a stalled callback must still
    // be able to release the frame gate and invalidate the old token safely.
    private val frameTimeoutExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    // Capture-stall detection must not share the main/UI looper. A delayed main
    // queue can postpone the check while ImageReader continues receiving frames.
    // The watchdog therefore samples capture-thread state from its own scheduler
    // and only posts the fail-closed shutdown back to the main thread.
    private val captureWatchdogExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    // Bitmap conversion is the expensive CPU/memory boundary of capture. Keep it off the ImageReader callback thread so a slow allocation/GC cannot starve frame-arrival callbacks and trigger a false capture stall.
    private val captureProcessingExecutor: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor()
    private val testTimerExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private var testTimerFuture: ScheduledFuture<*>? = null
    @Volatile private var testTimerSelectedMinutes: Int = 0
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
    // Lifecycle classification for VirtualDisplay callbacks. Android can invoke
    // VirtualDisplay.Callback.onStopped() as a consequence of our own release,
    // so that callback must never overwrite the actual service stop reason.
    @Volatile private var virtualDisplayReleaseExpected = false
    @Volatile private var externalVirtualDisplayStopObserved = false
    @Volatile private var captureStage = "STARTING"
    private val captureWatchdogRunnable = object : Runnable {
        override fun run() {
            if (!captureSessionActive) return
            val now = System.currentTimeMillis()
            // Stall detection runs independently on captureWatchdogExecutor.
            // This main-looper task is intentionally limited to diagnostics/HUD
            // refresh so UI backlog cannot create a false capture stall.
            // Keep the scanner HUD attached to the foreground capture session.
            // Android can detach overlay windows independently of the service;
            // reattach before persisting the next live diagnostic snapshot.
            if (captureSessionActive) ensureScannerHud()
            // Keep a lightweight live snapshot persisted even before the first
            // successful CV/OCR result. This makes MediaProjection/ImageReader
            // setup failures and zero-frame sessions diagnosable on-device.
            persistCaptureDiagnostics()
            handler.postDelayed(this, 1000L)
        }
    }

    companion object {
        @Volatile var instance: ScreenCaptureService? = null

        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "data"
        private const val MAX_FRAME_AGE_MS = 1000L
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

    private fun ensureScannerHud() {
        // OverlayService is the single user-facing scanner surface. Never
        // create a second accessibility HUD: two stacked windows can hide the
        // timer controls and make the game screen appear touch-blocked.
        if (!Settings.canDrawOverlays(this)) return

        val existing = scannerHud
        if (existing != null) {
            val attached = existing.parent != null
            val visible = existing.windowVisibility == View.VISIBLE && existing.isShown
            if (attached && visible) return
            runCatching { scannerHudManager?.removeView(existing) }
            scannerHud = null
            scannerHudManager = null
        }

        runCatching {
            val type = if (android.os.Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val windowContext =
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    createWindowContext(type, null)
                } else {
                    this
                }
            val manager = windowContext.getSystemService(WINDOW_SERVICE) as WindowManager
            val view = TextView(windowContext).apply {
                text = "LM • SCANNER  ● STARTING\\nScreen scanner active"
                textSize = 15f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xEE111111.toInt())
                setPadding(22, 16, 22, 16)
                minWidth = 260
                elevation = 24f
                visibility = View.VISIBLE
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }
            manager.addView(view, lp)
            scannerHudManager = manager
            scannerHud = view
        }.onFailure { error ->
            scannerHud = null
            scannerHudManager = null
            captureRuntime.recordFailure(
                "Scanner HUD attach failed: " +
                    (error.message ?: error.javaClass.simpleName).take(160)
            )
        }
    }

    private fun updateScannerHud(
        sessionId: Long,
        totalFrames: Long,
        acceptedFrames: Long,
        droppedFrames: Long,
        processedFrames: Long,
        stage: String,
        quality: String
    ) {
        handler.post {
            val hudText = "LM • SCANNER  " +
                (if (stage == "ERROR" || stage == "STOPPED") "■" else "●") + " " + stage + "\n" +
                "Session #" + sessionId + " • " + totalFrames + " frames\n" +
                "Accepted " + acceptedFrames + " • Processed " + processedFrames + " • Dropped " + droppedFrames + "\n" +
                "Quality: " + quality
            OverlayService.instance?.showStatus(hudText)
            if (OverlayService.instance == null) {
                ensureScannerHud()
                scannerHud?.post { scannerHud?.text = hudText }
            }
        }
    }

    private fun removeScannerHud() {
        // OverlayService owns the interactive scanner surface. Only remove the
        // legacy non-touchable fallback created by this service.
        val view = scannerHud
        scannerHud = null
        runCatching { if (view != null) scannerHudManager?.removeView(view) }
        scannerHudManager = null
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
            memoryPressureLevel = memory.level,
            candidateRejectionCounts = actionAuditLog.rejectionCountsForSession(runtime.sessionId)
        )
        captureDiagnosticsStore.save(diagnostics)
        updateScannerHud(runtime.sessionId, diagnostics.frames, diagnostics.acceptedFrames, diagnostics.droppedFrames, diagnostics.capture.processedFrames, captureStage, diagnostics.quality.name)
        OverlayService.instance?.showCaptureHealth(
            sessionId = runtime.sessionId,
            totalFrames = diagnostics.frames,
            acceptedFrames = diagnostics.acceptedFrames,
            droppedFrames = diagnostics.droppedFrames,
            processedFrames = diagnostics.capture.processedFrames,
            stage = captureStage,
            quality = diagnostics.quality.name
        )
    }

    fun isCaptureSessionActive(): Boolean = captureSessionActive

    fun startGridLearning(): Boolean {
        if (!captureSessionActive) {
            OverlayService.instance?.setGridLearningUi(false)
            OverlayService.instance?.showStatus("GRID LEARN • start screen scanner first")
            return false
        }
        gridLearningController.start()
        OverlayService.instance?.setGridLearningUi(true)
        OverlayService.instance?.showStatus("GRID LEARN • ACTIVE • probing map tiles only")
        return true
    }

    fun stopGridLearning() {
        if (::gridLearningController.isInitialized) gridLearningController.stop()
        OverlayService.instance?.setGridLearningUi(false)
        OverlayService.instance?.showStatus("GRID LEARN • STOPPED • samples saved")
    }

    fun isGridLearningActive(): Boolean =
        ::gridLearningController.isInitialized && gridLearningController.isActive()

    fun stopTestCapture() {
        if (!captureSessionActive) return
        captureRuntime.recordFailure("Test stopped by user")
        persistCaptureDiagnostics()
        stopCaptureResources(CaptureStopReason.USER_STOP)
        if (::gridLearningController.isInitialized) gridLearningController.stop()
        stopSelf()
    }

    fun configureTestTimer(minutes: Int) {
        if (!captureSessionActive) return
        val durationMinutes = minutes.coerceIn(1, 10)
        testTimerSelectedMinutes = durationMinutes
        testTimerFuture?.cancel(false)
        val durationMs = durationMinutes * 60_000L
        val deadlineMs = System.currentTimeMillis() + durationMs
        captureRuntime.recordFailure("Test timer armed for $durationMinutes minute(s)")
        OverlayService.instance?.showTestTimer(durationMs, durationMinutes, true)

        testTimerFuture = testTimerExecutor.scheduleAtFixedRate({
            if (!captureSessionActive) return@scheduleAtFixedRate
            val remainingMs = (deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            handler.post {
                if (captureSessionActive) {
                    OverlayService.instance?.showTestTimer(remainingMs, durationMinutes, true)
                }
            }
            if (remainingMs <= 0L) {
                testTimerFuture?.cancel(false)
                handler.post {
                    if (!captureSessionActive) return@post
                    captureRuntime.recordFailure(
                        "Test timer completed after $durationMinutes minute(s)"
                    )
                    persistCaptureDiagnostics()
                    stopCaptureResources(CaptureStopReason.TEST_TIMER)
                    OverlayService.instance?.showTestTimer(0L, durationMinutes, false)
                    OverlayService.instance?.showStatus(
                        "TEST COMPLETE • $durationMinutes min • diagnostics saved"
                    )
                    stopSelf()
                }
            }
        }, 0L, 1L, TimeUnit.SECONDS)
    }

    private fun stopCaptureResources(reason: CaptureStopReason = CaptureStopReason.USER_STOP) {
        // Mark the shutdown before releasing the producer resources. The
        // VirtualDisplay callback is asynchronous and may report onStopped()
        // after release(); without this barrier it can overwrite a real
        // CAPTURE_STALLED/PROJECTION_STOPPED reason with the generic
        // "VirtualDisplay stopped" message.
        virtualDisplayReleaseExpected = true
        captureSessionActive = false
        if (::gridLearningController.isInitialized) gridLearningController.stop()

        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        frameTimeoutFuture?.cancel(false)
        frameTimeoutFuture = null
        testTimerFuture?.cancel(false)
        testTimerFuture = null
        testTimerSelectedMinutes = 0
        reader = null
        virtualDisplay?.release()
        virtualDisplay = null
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
                memoryPressureLevel = memory.level,
                candidateRejectionCounts = actionAuditLog.rejectionCountsForSession(finalRuntime.sessionId)
            )
            captureDiagnosticsStore.save(finalDiagnostics)
            captureDiagnosticsStore.archive(finalDiagnostics)
        }
        captureStage = when (reason) {
            CaptureStopReason.USER_STOP -> "STOPPED"
            CaptureStopReason.TEST_TIMER -> "TEST COMPLETE"
            else -> "ERROR"
        }
        // Keep the expected-release barrier through the synchronous teardown.
        // Any callback arriving after this point also sees captureSessionActive=false
        // and therefore cannot be misclassified as an external stop.
        virtualDisplayReleaseExpected = false
        externalVirtualDisplayStopObserved = false
        removeScannerHud()
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        analyzer = FrameAnalyzer()
        captureDiagnosticsStore = CaptureSessionDiagnosticsStore(this)
        liveScanner = LiveMapScanner(this)
        gridLearningController = GridLearningController(this)
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
        externalVirtualDisplayStopObserved = false
        captureStage = "STARTING"
        ensureScannerHud()
        // Re-assert the diagnostic overlay from the already-running foreground
        // capture service. This makes visibility independent of the activity
        // lifecycle and of the separate overlay preference.
        runCatching { startService(Intent(this, OverlayService::class.java)) }
        handler.postDelayed({
            OverlayService.instance?.showStatus("LM • SCANNER  ● STARTING\\nOpening screen capture…")
        }, 150L)

        // A new MediaProjection session is a hard temporal/provenance boundary.
        // Do not let the previous session's frame, scheduler queue, camera
        // continuity, or target stability satisfy current-session safety checks.
        previousScan = null
        lastScanMs = 0L
        if (::gridLearningController.isInitialized) gridLearningController.stop()
        OverlayService.instance?.setGridLearningUi(false)
        val durableSessionId = captureDiagnosticsStore.allocateNextSessionId()
        val captureBoundary = actionOrchestrator.beginCaptureSession(durableSessionId)
        val recoveryPersistedAfterCaptureBoundary = persistRecoveryEpoch()
        actionSchedulerAdapter.resetForCaptureSession()
        liveScanner.resetCaptureSession()
        if (!recoveryPersistedAfterCaptureBoundary || captureBoundary.lifecycle.failure == ActionLifecycleFailure.RECOVERY_EPOCH_EXHAUSTED) {
            captureRuntime.recordFailure(
                "Action recovery boundary could not be durably established"
            )
        }
        captureRuntime.start(durableSessionId)
        persistCaptureDiagnostics()

        val captureStartedAt = System.currentTimeMillis()
        captureHealth.start(captureStartedAt)
        captureWatchdog.start(captureStartedAt)
        handler.removeCallbacks(captureWatchdogRunnable)
        handler.postDelayed(captureWatchdogRunnable, 1000L)
        OverlayService.consumeRequestedTestDuration()?.let { minutes ->
            configureTestTimer(minutes)
        }
        captureWatchdogExecutor.scheduleAtFixedRate({
            if (!captureSessionActive) return@scheduleAtFixedRate
            val now = System.currentTimeMillis()
            if (captureWatchdog.check(now)) {
                val healthAtStall = captureHealth.snapshot()
                val lastFrameAt = healthAtStall.lastFrameAtMs
                val noFrameForMs = if (lastFrameAt == null) {
                    -1L
                } else {
                    (now - lastFrameAt).coerceAtLeast(0L)
                }
                handler.post {
                    if (!captureSessionActive) return@post
                    if (externalVirtualDisplayStopObserved) {
                        captureRuntime.recordFailure(
                            "VirtualDisplay stopped externally before watchdog shutdown"
                        )
                        OverlayService.instance?.showStatus(
                            "VIRTUAL DISPLAY STOPPED • scanner stopped safely"
                        )
                        persistCaptureDiagnostics()
                        stopCaptureResources(CaptureStopReason.PROJECTION_STOPPED)
                        stopSelf()
                    } else {
                        captureRuntime.recordStall()
                        captureRuntime.recordFailure(
                            "Capture watchdog: no ImageReader frame for " +
                                noFrameForMs + " ms"
                        )
                        OverlayService.instance?.showStatus(
                            "CAPTURE STALLED • no frame for " + noFrameForMs + "ms • scanner stopped safely"
                        )
                        persistCaptureDiagnostics()
                        stopCaptureResources(CaptureStopReason.CAPTURE_STALLED)
                        stopSelf()
                    }
                }
            }
        }, 1000L, 1000L, TimeUnit.MILLISECONDS)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                if (!captureSessionActive) return
                // This callback is the authoritative signal that the
                // MediaProjection token was stopped by the system/user. Do not
                // let our own teardown path generate the same message.
                captureRuntime.recordFailure("MediaProjection stopped externally")
                stopCaptureResources(CaptureStopReason.PROJECTION_STOPPED)
                stopSelf()
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
            if (now - lastScanMs < 300L || !busy.compareAndSet(false, true)) {
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

            val frameStartedAt = System.currentTimeMillis()
            val frameToken = processingToken.incrementAndGet()
            frameTimeoutFuture?.cancel(false)
            frameTimeoutFuture = frameTimeoutExecutor.schedule({
                if (processingToken.get() == frameToken &&
                    busy.compareAndSet(true, false)
                ) {
                    processingToken.compareAndSet(frameToken, frameToken + 1L)
                    captureRuntime.recordFailure(
                        "Frame analysis timeout: " + analyzer.diagnosticState()
                    )
                    captureStage = "TIMEOUT"
                    captureHealth.frameDropped()
                    handler.post {
                        OverlayService.instance?.showStatus(
                            "FRAME ANALYSIS TIMEOUT • " + analyzer.diagnosticState()
                        )
                    }
                }
            }, FRAME_ANALYSIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)

            try {
                captureProcessingExecutor.execute {
                    val bitmap = try {
                        ImageBitmapConverter.toBitmap(image)
                    } catch (_: Throwable) {
                        handler.post {
                            if (processingToken.get() == frameToken &&
                                busy.compareAndSet(true, false)
                            ) {
                                processingToken.compareAndSet(frameToken, frameToken + 1L)
                                frameTimeoutFuture?.cancel(false)
                                frameTimeoutFuture = null
                                captureHealth.frameDropped()
                                captureRuntime.recordFailure("Frame bitmap conversion failed")
                                captureStage = "CONVERSION ERROR"
                                OverlayService.instance?.showStatus(
                                    "FRAME BITMAP CONVERSION FAILED • retrying safely"
                                )
                            }
                        }
                        return@execute
                    } finally {
                        image.close()
                    }

                    handler.post {
                        if (processingToken.get() != frameToken || !captureSessionActive) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@post
                        }
                        captureHealth.frameAccepted()
                captureStage = "ANALYZING"

                try {
                    val analysisStarted = analyzer.analyze(bitmap, defaultKingdom = 0) { result ->
                        frameTimeoutFuture?.cancel(false)
                        frameTimeoutFuture = null
                        if (processingToken.get() != frameToken) {
                            if (!bitmap.isRecycled) bitmap.recycle()
                            return@analyze
                        }
                        try {
                        captureStage = "LIVE SCAN"
                        val scanStartedAt = System.currentTimeMillis()
                        val scan = liveScanner.scan(
                            bitmap = bitmap,
                            defaultKingdom = result.coordinate?.kingdom ?: 0,
                            ocrCoordinate = result.coordinate,
                            textRegions = result.textRegions,
                            popupState = result.popup
                        )
                        gridLearningController.onFrame(
                            width = bitmap.width,
                            height = bitmap.height,
                            frameObservations = scan.frameObservations,
                            popupState = scan.popupState,
                            actionButtonDetections = scan.actionButtonDetections,
                            cameraStable = scan.cameraState == com.coolhiman.lordsassistant.map.CameraState.STABLE,
                            nowMs = now
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
                            append(scan.plan.discoveredTargetCount)
                            append(" discovered / ")
                            append(scan.plan.ranked.size + scan.plan.rankedMonsters.size)
                            append(" ranked")
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
                            if (scan.plan.rankedDiscoveries.isNotEmpty()) {
                                val top = scan.plan.rankedDiscoveries.first().observation
                                append("\nTop: ").append(top.kind?.name ?: "TARGET")
                                    .append(" L").append(top.level ?: "?").append(" @ ")
                                    .append(top.coordinate?.let { c -> "K" + c.kingdom + " X" + c.x + " Y" + c.y } ?: "screen")
                            }
                            if (scan.validation.reasons.isNotEmpty()) {
                                append("\nBlocked: ").append(scan.validation.reasons.joinToString(", ") { reason ->
                                    reason.name.replace('_', ' ')
                                })
                            }
                            append("\nVision: ").append(scan.detectedTiles)
                                .append(" tiles / ").append(scan.semanticTargetDetections).append(" semantic / ")
                                .append(scan.templateCount).append(" templates / ")
                                .append(scan.badgeDetections).append(" badges / OpenCV ")
                                .append(if (scan.openCvReady) "READY" else scan.openCvDiagnostic)
                                .append(" / ").append(scan.actionButtonDetections).append(" action buttons / ")
                                .append(scan.actionCandidates.size).append(" candidates")
                            append("\n").append(scan.processingMs).append("ms")
                            append("\nCapture quality: ").append(captureQuality.name)
                            if (gridLearningController.isActive()) {
                                append("\n").append(gridLearningController.statusLine())
                            }
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
                                queuedAtMs = now,
                                captureSessionId = liveCaptureSessionId
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
                                        recoveryEpochPersistenceHealthy = recoveryEpochPersistenceHealthy,
                                        captureSessionId = liveCaptureSessionId
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
                                            latestAction = currentCandidate.actionButton,
                                            captureSessionId = liveCaptureSessionId
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
                                                actionOrchestrator.dispatch(now, captureSessionId = liveCaptureSessionId) {
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
                                        nowMs = now,
                                        captureSessionId = liveCaptureSessionId
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
                            memoryPressureLevel = memory.level,
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
                    if (!analysisStarted) {
                        frameTimeoutFuture?.cancel(false)
                        frameTimeoutFuture = null
                        processingToken.compareAndSet(frameToken, frameToken + 1L)
                        captureStage = "OCR BUSY"
                        captureHealth.frameDropped()
                        if (!bitmap.isRecycled) bitmap.recycle()
                        busy.set(false)
                        OverlayService.instance?.showStatus(
                            "OCR BUSY • " + analyzer.diagnosticState()
                        )
                    }
                } catch (error: Throwable) {
                    frameTimeoutFuture?.cancel(false)
                    frameTimeoutFuture = null
                    processingToken.compareAndSet(frameToken, frameToken + 1L)
                    captureStage = "ERROR"
                    captureRuntime.recordFailure(
                        "Frame analyzer exception: " + (error.message ?: error.javaClass.simpleName).take(160)
                    )
                    captureHealth.frameDropped()
                    if (!bitmap.isRecycled) bitmap.recycle()
                    busy.set(false)
                    OverlayService.instance?.showStatus("FRAME ANALYZER ERROR • retrying safely")
                }
            

                    }
                }
            } catch (error: Throwable) {
                frameTimeoutFuture?.cancel(false)
                frameTimeoutFuture = null
                processingToken.compareAndSet(frameToken, frameToken + 1L)
                captureRuntime.recordFailure(
                    "Frame processing dispatch failed: " + (error.message ?: error.javaClass.simpleName).take(160)
                )
                captureHealth.frameDropped()
                busy.set(false)
                OverlayService.instance?.showStatus("FRAME PROCESSING DISPATCH FAILED • retrying safely")
            }
            return@setOnImageAvailableListener
        }, captureHandler)

        try {
            // Keep a strong reference for the entire capture session. The virtual
            // display owns the producer side of the ImageReader surface; dropping
            // the returned object here leaves its lifetime implicit and can make
            // a long-running projection stop producing frames unexpectedly.
            virtualDisplay = projection?.createVirtualDisplay(
                "LMCompanion",
                metrics.widthPixels,
                metrics.heightPixels,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                object : VirtualDisplay.Callback() {
                    override fun onPaused() {
                        if (captureSessionActive) {
                            captureRuntime.recordFailure("VirtualDisplay paused")
                        }
                    }
                    override fun onResumed() {
                        if (captureSessionActive) {
                            captureRuntime.recordFailure("VirtualDisplay resumed")
                        }
                    }
                    override fun onStopped() {
                        if (captureSessionActive && !virtualDisplayReleaseExpected) {
                            // This callback is the producer-side lifecycle
                            // signal. Stop immediately rather than waiting for
                            // the 3s watchdog; this preserves the real cause
                            // when the callback wins the race with the watchdog.
                            externalVirtualDisplayStopObserved = true
                            captureRuntime.recordFailure("VirtualDisplay stopped externally")
                            handler.post {
                                if (!captureSessionActive) return@post
                                OverlayService.instance?.showStatus(
                                    "VIRTUAL DISPLAY STOPPED • scanner stopped safely"
                                )
                                stopCaptureResources(CaptureStopReason.PROJECTION_STOPPED)
                                stopSelf()
                            }
                        }
                    }
                },
                captureHandler
            ) ?: throw IllegalStateException("VirtualDisplay creation returned null")
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
        instance = null
        liveScanner.close()
        analyzer.close()
        frameTimeoutExecutor.shutdownNow()
        captureWatchdogExecutor.shutdownNow()
        captureProcessingExecutor.shutdownNow()
        testTimerExecutor.shutdownNow()
        captureThread.quitSafely()
        removeScannerHud()
        ActionDiagnosticsStore.latest = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}