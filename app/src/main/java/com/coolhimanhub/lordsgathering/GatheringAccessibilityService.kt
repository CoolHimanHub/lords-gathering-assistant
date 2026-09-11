package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * V28 service.
 *
 * Flow: screenshot -> badge candidate -> single candidate tap -> screenshot/OCR
 * of opened panel -> TilePanelVerifier -> Gather button tap only when verified.
 * No blind multi-tile clicking is performed.
 */
class GatheringAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    @Volatile private var running = false
    @Volatile private var screenshotInProgress = false
    @Volatile private var serviceAlive = true
    @Volatile private var actionInProgress = false
    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null
    private var infoText: TextView? = null
    private var startStopButton: Button? = null
    private var scanButton: Button? = null
    private var gatherButton: Button? = null
    private var scanCount = 0
    private lateinit var gatheringEngine: AutoGatheringEngine
    private lateinit var screenAnalyzer: ScreenAnalyzer
    @Volatile private var autoGatheringActive = false
    private val scanInterval = 4000L
    private val panelWait = 750L
    private val minimumConfidence = 68
    private val maximumDisplayedTargets = 20
    private val rssPriority = listOf("Emerging", "Gold", "Ore", "Wood", "Food", "Stone", "Other")

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceAlive = true
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            gatheringEngine = AutoGatheringEngine(this, handler)
            screenAnalyzer = ScreenAnalyzer()
            handler.post { if (serviceAlive) showFloatingControl() }
        } catch (_: Exception) {
            safeStatus("Service ready\nOverlay retrying...")
            handler.postDelayed({ if (serviceAlive) recreateOverlay() }, 1000)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopAutomation() }

    private fun showFloatingControl() {
        if (!serviceAlive || overlayView != null) return
        val wm = windowManager ?: return
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(65, 65, 65))
        }
        val title = TextView(this).apply {
            text = "Lords Assistant V28"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)
        }
        title.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var startParamX = 0
            private var startParamY = 0
            override fun onTouch(view: View?, event: MotionEvent): Boolean {
                val p = overlayParams ?: return false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX; startY = event.rawY
                        startParamX = p.x; startParamY = p.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        p.x = (startParamX + event.rawX - startX).toInt()
                        p.y = (startParamY + event.rawY - startY).toInt()
                        try { wm.updateViewLayout(container, p) } catch (_: Exception) {}
                        return true
                    }
                    else -> return true
                }
            }
        })
        val startButton = Button(this).apply {
            text = "▶ SCAN"
            setOnClickListener {
                try { if (running) stopAutomation() else startAutomation() }
                catch (e: Exception) { safeStatus("Button error: ${e.javaClass.simpleName}") }
            }
        }
        val scanBtn = Button(this).apply {
            text = "🔍 ONE SCAN"
            setOnClickListener {
                try { scanScreen() }
                catch (e: Exception) { safeStatus("Scan error: ${e.javaClass.simpleName}") }
            }
        }
        val gatherBtn = Button(this).apply {
            text = "⚔ AUTO GATHER"
            setOnClickListener {
                try {
                    if (autoGatheringActive) stopAutoGathering() else startAutoGathering()
                } catch (e: Exception) { safeStatus("Gather error: ${e.javaClass.simpleName}") }
            }
        }
        val info = TextView(this).apply {
            text = "V28 Scanner ready\nBadge-first candidate detection\nOpened-panel verification required\nGather action only after verification"
            textSize = 10.5f
            setTextColor(Color.WHITE)
            gravity = Gravity.LEFT
            setPadding(6, 5, 6, 2)
            setLineSpacing(0f, 1.05f)
        }
        infoText = info; startStopButton = startButton; scanButton = scanBtn; gatherButton = gatherBtn
        container.addView(title); container.addView(startButton); container.addView(scanBtn); container.addView(gatherBtn)
        val scroll = ScrollView(this).apply { isFillViewport = false; setPadding(0, 2, 0, 0) }
        scroll.addView(info, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        container.addView(scroll, LinearLayout.LayoutParams(470, 520))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START; params.x = 100; params.y = 80; overlayParams = params
        try {
            wm.addView(container, params); overlayView = container
            safeStatus("V28 Scanner ready\nBadge-first candidates\nOne-candidate panel verification\nGather only after panel approval")
        } catch (_: Exception) {
            overlayView = null; overlayParams = null; infoText = null; startStopButton = null; scanButton = null; gatherButton = null
            handler.postDelayed({ if (serviceAlive) recreateOverlay() }, 1000)
        }
    }

    private fun recreateOverlay() { if (!serviceAlive || overlayView != null) return; showFloatingControl() }
    private fun startAutomation() {
        if (running || !serviceAlive) return
        running = true; updateStartStopButton()
        safeStatus("V28 AUTO SCAN started\nScanning every 4 seconds\nBadge candidates active")
        handler.removeCallbacks(scanRunnable); handler.postDelayed(scanRunnable, 700)
    }
    private fun stopAutomation() {
        running = false; handler.removeCallbacks(scanRunnable); updateStartStopButton()
        safeStatus("SCAN STOPPED\nAuto-gather: ${if (autoGatheringActive) "ACTIVE" else "IDLE"}")
    }
    private fun startAutoGathering() {
        if (autoGatheringActive || !serviceAlive) return
        autoGatheringActive = true; updateGatherButton(); gatheringEngine.startGathering()
        safeStatus("AUTO GATHER ACTIVE\nCandidate -> open panel -> OCR -> verify -> Gather")
    }
    private fun stopAutoGathering() {
        autoGatheringActive = false; gatheringEngine.stopGathering(); updateGatherButton()
        safeStatus("AUTO GATHER STOPPED\nNo further action will be sent")
    }
    private val scanRunnable = object : Runnable {
        override fun run() {
            if (!running || !serviceAlive) return
            try { scanScreen() } catch (e: Exception) { safeStatus("Scan exception: ${e.javaClass.simpleName}") }
            if (running && serviceAlive) handler.postDelayed(this, scanInterval)
        }
    }

    private fun scanScreen() {
        if (!serviceAlive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        if (screenshotInProgress || actionInProgress) return
        screenshotInProgress = true; scanCount++; val thisScan = scanCount
        safeStatus("Scan #$thisScan\nCapturing screen...")
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    if (!serviceAlive) { screenshot.hardwareBuffer.close(); screenshotInProgress = false; return }
                    processScreenshot(screenshot, thisScan)
                }
                override fun onFailure(errorCode: Int) {
                    screenshotInProgress = false
                    safeStatus("Scan #$thisScan\nCapture failed: $errorCode")
                }
            })
        } catch (e: Exception) {
            screenshotInProgress = false; safeStatus("Scan #$thisScan\nCapture exception: ${e.javaClass.simpleName}")
        }
    }

    private fun processScreenshot(screenshot: ScreenshotResult, thisScan: Int) {
        try {
            val hb = try { Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace) } catch (_: Exception) { null }
            if (hb == null) { screenshotInProgress = false; screenshot.hardwareBuffer.close(); return }
            val bitmap = try { hb.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
            if (bitmap == null) { screenshotInProgress = false; hb.recycle(); screenshot.hardwareBuffer.close(); return }
            analysisExecutor.execute {
                try { if (serviceAlive) analyseScreen(bitmap, thisScan) }
                finally {
                    screenshotInProgress = false
                    try { bitmap.recycle() } catch (_: Exception) {}
                    try { hb.recycle() } catch (_: Exception) {}
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            screenshotInProgress = false; safeStatus("Screenshot processing error: ${e.javaClass.simpleName}")
        }
    }

    private fun analyseScreen(bitmap: Bitmap, scanNumber: Int) {
        try {
            val detections = screenAnalyzer.analyzeScreenshot(bitmap)
            if (detections.isEmpty()) { safeStatus("Scan #$scanNumber\nNo badge candidates found"); return }
            val targets = detections.filter { it.confidence >= minimumConfidence }
                .map { d -> AutoGatheringEngine.RssTarget("RSS?", 0, d.centerX, d.centerY, d.confidence, false, 0, d.confidence, false, 0) }
                .sortedByDescending { it.confidence }
            val displayCount = min(targets.size, maximumDisplayedTargets)
            val resultText = StringBuilder().append("Scan #$scanNumber\nFound $displayCount badge candidates:\n")
            for (i in 0 until displayCount) {
                val t = targets[i]
                resultText.append("${i + 1}. RSS? @ ${t.x},${t.y} (${t.confidence}%)\n")
            }
            safeStatus(resultText.toString())
            if (autoGatheringActive && !actionInProgress) probeBestCandidate(targets)
        } catch (e: Exception) { safeStatus("Analysis error: ${e.javaClass.simpleName}\n${e.message}") }
    }

    private fun probeBestCandidate(targets: List<AutoGatheringEngine.RssTarget>) {
        val candidate = targets.maxByOrNull { it.confidence } ?: return
        actionInProgress = true
        safeStatus("Opening one candidate\n@ ${candidate.x},${candidate.y}\nWaiting for tile panel...")
        handler.post {
            if (!serviceAlive) { actionInProgress = false; return@post }
            if (!tapAt(candidate.x, candidate.y)) {
                actionInProgress = false
                safeStatus("Candidate tap failed\nNo Gather action sent")
                return@post
            }
            handler.postDelayed({ capturePanelForVerification(candidate) }, panelWait)
        }
    }

    private fun capturePanelForVerification(candidate: AutoGatheringEngine.RssTarget) {
        if (!serviceAlive || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { actionInProgress = false; return }
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val hb = try { Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace) } catch (_: Exception) { null }
                    val bitmap = try { hb?.copy(Bitmap.Config.ARGB_8888, false) } catch (_: Exception) { null }
                    try { hb?.recycle() } catch (_: Exception) {}
                    try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                    if (bitmap == null) { actionInProgress = false; safeStatus("Panel screenshot failed\nNo Gather action sent"); return }
                    runPanelOcr(bitmap, candidate)
                }
                override fun onFailure(errorCode: Int) {
                    actionInProgress = false; safeStatus("Panel capture failed: $errorCode\nNo Gather action sent")
                }
            })
        } catch (e: Exception) {
            actionInProgress = false; safeStatus("Panel capture exception: ${e.javaClass.simpleName}")
        }
    }

    private fun runPanelOcr(bitmap: Bitmap, candidate: AutoGatheringEngine.RssTarget) {
        val image = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(image)
            .addOnSuccessListener { result ->
                val verification = TilePanelVerifier.verify(result.text)
                if (!verification.safeToGather) {
                    actionInProgress = false
                    safeStatus("Panel rejected\n${verification.reason}\nNo Gather action sent")
                    return@addOnSuccessListener
                }
                safeStatus("Panel verified\n${verification.type} L${verification.level}\nGather available\nExecuting Gather")
                tapGatherFromOcr(result.textBlocks, bitmap)
            }
            .addOnFailureListener {
                actionInProgress = false
                safeStatus("Panel OCR failed\nNo Gather action sent")
            }
            .addOnCompleteListener { try { bitmap.recycle() } catch (_: Exception) {} }
    }

    private fun tapGatherFromOcr(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>, bitmap: Bitmap) {
        val gatherElement = blocks.asSequence()
            .flatMap { it.lines.asSequence() }
            .flatMap { it.elements.asSequence() }
            .firstOrNull { it.text.contains("gather", ignoreCase = true) }
        val rect = gatherElement?.boundingBox
        if (rect == null) {
            actionInProgress = false
            safeStatus("Panel verified but Gather control position was not found\nNo action sent")
            return
        }
        val x = (rect.left + rect.right) / 2
        val y = (rect.top + rect.bottom) / 2
        handler.postDelayed({
            if (!serviceAlive || !tapAt(x, y)) safeStatus("Gather tap failed") else safeStatus("Gather action sent\nTarget verified: ${gatheringEngine.getCurrentTarget()?.type ?: "RSS"}")
            gatheringEngine.clearCurrentTarget()
            actionInProgress = false
        }, 250)
    }

    private fun tapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return try { dispatchGesture(gesture, null, null) } catch (_: Exception) { false }
    }

    private fun updateStartStopButton() { handler.post { try { startStopButton?.text = if (running) "⏸ STOP" else "▶ SCAN" } catch (_: Exception) {} } }
    private fun updateGatherButton() { handler.post { try { gatherButton?.text = if (autoGatheringActive) "⏸ STOP GATHER" else "⚔ AUTO GATHER" } catch (_: Exception) {} } }
    private fun safeStatus(status: String) { handler.post { try { infoText?.text = status } catch (_: Exception) {} } }

    override fun onDestroy() {
        serviceAlive = false; running = false; autoGatheringActive = false; actionInProgress = false
        handler.removeCallbacksAndMessages(null)
        try { gatheringEngine.stopGathering() } catch (_: Exception) {}
        try { analysisExecutor.shutdownNow() } catch (_: Exception) {}
        try { textRecognizer.close() } catch (_: Exception) {}
        try { overlayView?.let { windowManager?.removeView(it) } } catch (_: Exception) {}
        overlayView = null
        super.onDestroy()
    }
}
