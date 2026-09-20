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
    private var previousScan: com.coolhiman.lordsassistant.map.LiveMapScanResult? = null
    private val busy = AtomicBoolean(false)
    private val handler = Handler(Looper.getMainLooper())
    private var lastScanMs = 0L
    private val viewportGuard = ViewportGuard()

    override fun onCreate() {
        super.onCreate()
        analyzer = FrameAnalyzer()
        liveScanner = LiveMapScanner(this)
        actionOrchestrator = ActionOrchestrator()
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
                    val active = actionOrchestrator.lifecycleSnapshot.state
                    if (prefs.automaticActions) {
                        when {
                            ActionRecoveryPolicy.mayStartAutomaticAttempt(active) -> {
                                val previous = previousScan
                                if (previous?.selectedActionTarget != null &&
                                    previous.validation.safe &&
                                    previous.validation.stage == com.coolhiman.lordsassistant.target.TargetValidationStage.SAFE_TO_INTERACT
                                ) {
                                    actionOrchestrator.request(
                                        automaticActionsEnabled = true,
                                        selected = previous.selectedActionTarget,
                                        validation = previous.validation,
                                        beforeObservation = previous.selectedObservation,
                                        popupBefore = previous.popupState,
                                        baselineMarchSignals = previous.marchSignals,
                                        nowMs = now
                                    )
                                    actionOrchestrator.revalidate(
                                        latestObservation = scan.selectedObservation,
                                        latestValidation = scan.validation,
                                        latestAction = scan.actionButton
                                    )
                                    if (actionOrchestrator.lifecycleSnapshot.state == ActionLifecycleState.REVALIDATED) {
                                        actionOrchestrator.dispatch(now) {
                                            LmAccessibilityService.instance?.tapRevalidated(
                                                selected = previous.selectedActionTarget,
                                                latestObservation = scan.selectedObservation,
                                                latestValidation = scan.validation,
                                                latestAction = scan.actionButton
                                            ) == true
                                        }
                                    }
                                }
                            }
                            active == ActionLifecycleState.WAITING_FOR_RESULT -> {
                                actionOrchestrator.observeMarch(scan.marchSignals, now)
                                actionOrchestrator.verifyPostAction(
                                    afterObservation = scan.selectedObservation,
                                    popupAfter = scan.popupState,
                                    nowMs = now
                                )
                            }
                        }
                    } else if (active != ActionLifecycleState.IDLE) {
                        actionOrchestrator.reset()
                    }

                    ActionDiagnosticsStore.latest = ActionDiagnosticsSnapshot.fromScan(
                        scan = scan,
                        lifecycle = actionOrchestrator.lifecycleSnapshot,
                        evidence = actionOrchestrator.lastPostActionEvidence,
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
