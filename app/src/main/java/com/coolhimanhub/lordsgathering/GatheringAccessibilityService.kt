package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min


class GatheringAccessibilityService :
    AccessibilityService() {

    // =============================================================
    // BASIC STATE
    // =============================================================

    private val handler =
        Handler(Looper.getMainLooper())

    private val analysisExecutor =
        Executors.newSingleThreadExecutor()

    private val textRecognizer: TextRecognizer =
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

    @Volatile
    private var running = false

    @Volatile
    private var screenshotInProgress = false

    @Volatile
    private var serviceAlive = true

    private var overlayView: LinearLayout? = null

    private var overlayParams:
        WindowManager.LayoutParams? = null

    private var windowManager:
        WindowManager? = null

    private var infoText:
        TextView? = null

    private var startStopButton:
        Button? = null

    private var scanButton:
        Button? = null

    private var gatherButton:
        Button? = null

    private var scanCount = 0

    // =============================================================
    // AUTO GATHERING ENGINE & SCREEN ANALYZER
    // =============================================================

    private lateinit var gatheringEngine: AutoGatheringEngine
    private lateinit var screenAnalyzer: ScreenAnalyzer

    @Volatile
    private var autoGatheringActive = false


    // =============================================================
    // V10 SETTINGS
    // =============================================================

    private val scanInterval =
        4000L

    private val minimumConfidence =
        60

    private val maximumDisplayedTargets =
        20


    // =============================================================
    // RSS PRIORITY
    // =============================================================

    private val rssPriority =
        listOf(
            "Emerging",
            "Gold",
            "Ore",
            "Wood",
            "Food",
            "Stone",
            "Other"
        )


    // =============================================================
    // RESOURCE CANDIDATE
    // =============================================================

    private data class RssCandidate(

        val type: String,

        val level: Int,

        val x: Int,

        val y: Int,

        val confidence: Int,

        val occupied: Boolean,

        val flagScore: Int,

        val targetScore: Int,

        val badgeWidth: Int,

        val badgeHeight: Int
    )


    // =============================================================
    // OCR DIGIT
    // =============================================================

    private data class OcrDigit(

        val digit: Int,

        val centerX: Int,

        val centerY: Int,

        val width: Int,

        val height: Int,

        val rawText: String
    )


    // =============================================================
    // BLUE COMPONENT
    // =============================================================

    private data class BlueComponent(

        var minX: Int,

        var minY: Int,

        var maxX: Int,

        var maxY: Int,

        var pixels: Int
    ) {

        val centerX: Int
            get() = (minX + maxX) / 2

        val centerY: Int
            get() = (minY + maxY) / 2

        val width: Int
            get() = maxX - minX + 1

        val height: Int
            get() = maxY - minY + 1
    }


    // =============================================================
    // SERVICE CONNECTED
    // =============================================================

    override fun onServiceConnected() {

        super.onServiceConnected()

        serviceAlive = true

        try {

            windowManager =
                getSystemService(
                    WINDOW_SERVICE
                ) as WindowManager

            gatheringEngine =
                AutoGatheringEngine(this, handler)

            screenAnalyzer =
                ScreenAnalyzer()

            handler.post {

                if (serviceAlive) {
                    showFloatingControl()
                }
            }

        } catch (_: Exception) {

            safeStatus(
                "Service ready\n" +
                    "Overlay retrying..."
            )

            handler.postDelayed(
                {

                    if (serviceAlive) {
                        recreateOverlay()
                    }

                },
                1000
            )
        }
    }


    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // Reserved for future use.
    }


    override fun onInterrupt() {

        stopAutomation()
    }


    // =============================================================
    // FLOATING CONTROL
    // =============================================================

    private fun showFloatingControl() {

        if (!serviceAlive) {
            return
        }

        if (overlayView != null) {
            return
        }

        val wm =
            windowManager
                ?: try {

                    getSystemService(
                        WINDOW_SERVICE
                    ) as WindowManager

                } catch (_: Exception) {

                    return
                }

        windowManager = wm


        // ---------------------------------------------------------
        // CONTAINER
        // ---------------------------------------------------------

        val container =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    10,
                    8,
                    10,
                    8
                )

                setBackgroundColor(
                    Color.rgb(
                        65,
                        65,
                        65
                    )
                )
            }


        // ---------------------------------------------------------
        // TITLE
        // ---------------------------------------------------------

        val title =
            TextView(this).apply {

                text =
                    "Lords Assistant V12"

                textSize =
                    16f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    8,
                    4,
                    8,
                    8
                )
            }


        // ---------------------------------------------------------
        // DRAG HANDLE
        // ---------------------------------------------------------

        title.setOnTouchListener(
            object : View.OnTouchListener {

                private var startX = 0f
                private var startY = 0f
                private var startParamX = 0
                private var startParamY = 0

                override fun onTouch(
                    view: View?,
                    event: MotionEvent
                ): Boolean {

                    val params =
                        overlayParams
                            ?: return false

                    when (
                        event.actionMasked
                    ) {

                        MotionEvent.ACTION_DOWN -> {

                            startX =
                                event.rawX

                            startY =
                                event.rawY

                            startParamX =
                                params.x

                            startParamY =
                                params.y

                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {

                            params.x =
                                (
                                    startParamX +
                                        event.rawX -
                                        startX
                                    ).toInt()

                            params.y =
                                (
                                    startParamY +
                                        event.rawY -
                                        startY
                                    ).toInt()

                            try {

                                wm.updateViewLayout(
                                    container,
                                    params
                                )

                            } catch (_: Exception) {
                            }

                            return true
                        }

                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_CANCEL -> {

                            return true
                        }
                    }

                    return true
                }
            }
        )


        // =========================================================
        // START / STOP SCAN
        // =========================================================

        val startButton =
            Button(this).apply {

                text =
                    "▶ SCAN"

                setOnClickListener {

                    try {

                        if (running) {

                            stopAutomation()

                        } else {

                            startAutomation()
                        }

                    } catch (e: Exception) {

                        safeStatus(
                            "Button error: " +
                                e.javaClass.simpleName
                        )
                    }
                }
            }


        // =========================================================
        // MANUAL SCAN BUTTON
        // =========================================================

        val scanBtn =
            Button(this).apply {

                text =
                    "🔍 ONE SCAN"

                setOnClickListener {

                    try {

                        scanScreen()

                    } catch (e: Exception) {

                        safeStatus(
                            "Scan error: " +
                                e.javaClass.simpleName
                        )
                    }
                }
            }


        // =========================================================
        // AUTO GATHER BUTTON
        // =========================================================

        val gatherBtn =
            Button(this).apply {

                text =
                    "⚔ AUTO GATHER"

                setOnClickListener {

                    try {

                        if (autoGatheringActive) {

                            stopAutoGathering()

                        } else {

                            startAutoGathering()
                        }

                    } catch (e: Exception) {

                        safeStatus(
                            "Gather error: " +
                                e.javaClass.simpleName
                        )
                    }
                }
            }


        // =========================================================
        // STATUS TEXT
        // =========================================================

        val info =
            TextView(this).apply {

                text =
                    "V12 Scanner ready\n" +
                        "Detects RSS levels 1-5\n" +
                        "Flag/occupation detection enabled\n" +
                        "Auto-gathering available\n" +
                        "SAFE MODE: Ready"

                textSize =
                    10.5f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.LEFT

                setPadding(
                    6,
                    5,
                    6,
                    2
                )

                setLineSpacing(
                    0f,
                    1.05f
                )
            }


        infoText =
            info

        startStopButton =
            startButton

        scanButton =
            scanBtn

        gatherButton =
            gatherBtn


        container.addView(
            title
        )

        container.addView(
            startButton
        )

        container.addView(
            scanBtn
        )

        container.addView(
            gatherBtn
        )


        // ---------------------------------------------------------
        // SCROLLABLE RESULT AREA
        // ---------------------------------------------------------

        val scroll =
            ScrollView(this).apply {

                isFillViewport =
                    false

                setPadding(
                    0,
                    2,
                    0,
                    0
                )
            }

        scroll.addView(
            info,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        container.addView(
            scroll,
            LinearLayout.LayoutParams(
                470,
                520
            )
        )


        // =========================================================
        // OVERLAY PARAMETERS
        // =========================================================

        val params =
            WindowManager.LayoutParams(

                WindowManager.LayoutParams.WRAP_CONTENT,

                WindowManager.LayoutParams.WRAP_CONTENT,

                WindowManager.LayoutParams
                    .TYPE_ACCESSIBILITY_OVERLAY,

                WindowManager.LayoutParams
                    .FLAG_NOT_FOCUSABLE,

                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or
                Gravity.START

        params.x =
            100

        params.y =
            80

        overlayParams =
            params


        // =========================================================
        // ADD OVERLAY
        // =========================================================

        try {

            wm.addView(
                container,
                params
            )

            overlayView =
                container

            safeStatus(
                "V12 Scanner ready\n" +
                    "Press SCAN or ONE SCAN\n" +
                    "Levels 1-5\n" +
                    "AUTO GATHER enabled\n" +
                    "SAFE MODE: Ready"
            )

        } catch (_: Exception) {

            overlayView =
                null

            overlayParams =
                null

            infoText =
                null

            startStopButton =
                null

            scanButton =
                null

            gatherButton =
                null

            handler.postDelayed(
                {

                    if (serviceAlive) {
                        recreateOverlay()
                    }

                },
                1000
            )
        }
    }


    // =============================================================
    // RECREATE OVERLAY
    // =============================================================

    private fun recreateOverlay() {

        if (!serviceAlive) {
            return
        }

        if (overlayView != null) {
            return
        }

        try {

            showFloatingControl()

        } catch (_: Exception) {
        }
    }


    // =============================================================
    // START AUTOMATION (SCANNING)
    // =============================================================

    private fun startAutomation() {

        if (running) {
            return
        }

        if (!serviceAlive) {
            return
        }

        running =
            true

        updateStartStopButton()

        safeStatus(
            "V12 AUTO SCAN started\n" +
                "Scanning every 4 seconds\n" +
                "Levels 1-5\n" +
                "Analyzing: ${rssPriority.take(3).joinToString(", ")}\n" +
                "Status: ACTIVE"
        )

        handler.removeCallbacks(
            scanRunnable
        )

        handler.postDelayed(
            scanRunnable,
            700
        )
    }


    // =============================================================
    // STOP SCANNING
    // =============================================================

    private fun stopAutomation() {

        running =
            false

        handler.removeCallbacks(
            scanRunnable
        )

        updateStartStopButton()

        safeStatus(
            "SCAN STOPPED\n" +
                "Scanning paused\n" +
                "Auto-gathering: ${if (autoGatheringActive) "ACTIVE" else "IDLE"}\n" +
                "Ready for input"
        )
    }


    // =============================================================
    // START AUTO GATHERING
    // =============================================================

    private fun startAutoGathering() {

        if (autoGatheringActive) {
            return
        }

        if (!serviceAlive) {
            return
        }

        autoGatheringActive =
            true

        updateGatherButton()

        gatheringEngine.startGathering()

        safeStatus(
            "AUTO GATHERING STARTED\n" +
                "Gathering every 8 seconds\n" +
                "Priority: ${rssPriority.joinToString(", ")}\n" +
                "Status: ACTIVE"
        )
    }


    // =============================================================
    // STOP AUTO GATHERING
    // =============================================================

    private fun stopAutoGathering() {

        autoGatheringActive =
            false

        gatheringEngine.stopGathering()

        updateGatherButton()

        safeStatus(
            "AUTO GATHERING STOPPED\n" +
                "Gathering halted\n" +
                "Ready to restart\n" +
                "Status: IDLE"
        )
    }


    // =============================================================
    // AUTOMATIC SCAN LOOP
    // =============================================================

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (
                    !running ||
                    !serviceAlive
                ) {
                    return
                }

                try {

                    scanScreen()

                } catch (e: Exception) {

                    safeStatus(
                        "Scan exception: " +
                            e.javaClass.simpleName
                    )
                }

                if (
                    running &&
                    serviceAlive
                ) {

                    handler.postDelayed(
                        this,
                        scanInterval
                    )
                }
            }
        }


    // =============================================================
    // SCREEN SCANNER
    // =============================================================

    private fun scanScreen() {

        if (!serviceAlive) {
            return
        }

        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
        ) {

            safeStatus(
                "Android version does not support\n" +
                    "Accessibility screenshot API"
            )

            return
        }

        if (screenshotInProgress) {

            safeStatus(
                "Scan already running..."
            )

            return
        }

        screenshotInProgress =
            true

        scanCount++

        val thisScan =
            scanCount

        safeStatus(
            "Scan #$thisScan\n" +
                "Capturing screen..."
        )

        try {

            takeScreenshot(

                android.view.Display.DEFAULT_DISPLAY,

                mainExecutor,

                object :
                    TakeScreenshotCallback {

                    override fun onSuccess(
                        screenshot:
                            ScreenshotResult
                    ) {

                        if (!serviceAlive) {

                            screenshot.hardwareBuffer.close()

                            screenshotInProgress =
                                false

                            return
                        }

                        processScreenshot(
                            screenshot,
                            thisScan
                        )
                    }

                    override fun onFailure(
                        errorCode: Int
                    ) {

                        screenshotInProgress =
                            false

                        safeStatus(
                            "Scan #$thisScan\n" +
                                "Capture failed: " +
                                errorCode +
                                "\nStatus: Error"
                        )
                    }
                }
            )

        } catch (e: Exception) {

            screenshotInProgress =
                false

            safeStatus(
                "Scan #$thisScan\n" +
                    "Capture exception: " +
                    e.javaClass.simpleName +
                    "\nStatus: Error"
            )
        }
    }


    // =============================================================
    // PROCESS SCREENSHOT
    // =============================================================

    private fun processScreenshot(
        screenshot:
            ScreenshotResult,

        thisScan:
            Int
    ) {

        try {

            val hardwareBitmap =
                try {

                    Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer,
                        screenshot.colorSpace
                    )

                } catch (_: Exception) {

                    null
                }


            if (
                hardwareBitmap == null
            ) {

                safeStatus(
                    "Scan #$thisScan\n" +
                        "Bitmap conversion failed"
                )

                screenshotInProgress =
                    false

                return
            }


            val bitmap =
                try {

                    hardwareBitmap.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )

                } catch (_: Exception) {

                    null
                }


            if (bitmap == null) {

                safeStatus(
                    "Scan #$thisScan\n" +
                        "Bitmap copy failed"
                )

                screenshotInProgress =
                    false

                return
            }


            safeStatus(
                "Scan #$thisScan\n" +
                    "Analyzing screen..."
            )


            analysisExecutor.execute {

                try {

                    if (serviceAlive) {

                        analyseScreen(
                            bitmap,
                            thisScan
                        )
                    }

                } finally {

                    screenshotInProgress =
                        false

                    try {

                        bitmap.recycle()

                    } catch (_: Exception) {
                    }

                    try {

                        hardwareBitmap.recycle()

                    } catch (_: Exception) {
                    }

                    try {

                        screenshot.hardwareBuffer.close()

                    } catch (_: Exception) {
                    }
                }
            }

        } catch (e: Exception) {

            screenshotInProgress =
                false

            safeStatus(
                "Screenshot processing error: " +
                    e.javaClass.simpleName
            )
        }
    }


    // =============================================================
    // SCREEN ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap,
        scanNumber: Int
    ) {

        try {

            // Use ScreenAnalyzer to detect RSS tiles
            val detections =
                screenAnalyzer.analyzeScreenshot(bitmap)

            if (detections.isEmpty()) {

                safeStatus(
                    "Scan #$scanNumber\n" +
                        "No RSS tiles found\n" +
                        "Searching..."
                )

                return
            }

            // Filter by confidence and convert to targets
            val targets = detections
                .filter { it.confidence >= minimumConfidence }
                .map { detection ->
                    AutoGatheringEngine.RssTarget(
                        type = detection.type,
                        level = detection.level,
                        x = detection.centerX,
                        y = detection.centerY,
                        confidence = detection.confidence,
                        occupied = detection.occupied,
                        flagScore = if (detection.occupied) 10 else 0,
                        targetScore = calculateTargetScore(detection)
                    )
                }
                .sortedBy { rssPriority.indexOf(it.type) }

            if (targets.isEmpty()) {

                safeStatus(
                    "Scan #$scanNumber\n" +
                        "Found ${detections.size} tiles\n" +
                        "Confidence too low\n" +
                        "Confidence threshold: $minimumConfidence"
                )

                return
            }

            // Display top results
            val displayCount =
                minOf(targets.size, maximumDisplayedTargets)

            val resultText =
                StringBuilder()
                    .append("Scan #$scanNumber\n")
                    .append("Found $displayCount targets:\n")

            for (i in 0 until displayCount) {

                val target = targets[i]

                resultText
                    .append("${i + 1}. ${target.type} L${target.level}")
                    .append(if (target.occupied) " ⛔" else "")
                    .append(" (${target.confidence}%)\n")
            }

            safeStatus(resultText.toString())

            // If auto-gathering is active, process best target
            if (autoGatheringActive && targets.isNotEmpty()) {

                val selectedTarget =
                    gatheringEngine.processDetectedTargets(
                        targets
                    )

                if (selectedTarget != null) {

                    handler.post {

                        safeStatus(
                            "Scan #$scanNumber\n" +
                                "Targeting: ${selectedTarget.type} L${selectedTarget.level}\n" +
                                "Sending troops...\n" +
                                "Confidence: ${selectedTarget.confidence}%"
                        )
                    }

                    gatheringEngine.performGatheringAction(
                        selectedTarget
                    )
                }
            }

        } catch (e: Exception) {

            safeStatus(
                "Analysis error: " +
                    e.javaClass.simpleName + "\n" +
                    e.message
            )
        }
    }


    // =============================================================
    // HELPER FUNCTIONS
    // =============================================================

    private fun calculateTargetScore(
        detection: ScreenAnalyzer.RssDetection
    ): Int {

        var score = detection.confidence

        // Prefer higher levels
        score += detection.level * 10

        // Penalize occupied tiles
        if (detection.occupied) {
            score -= 30
        }

        return score
    }


    private fun updateStartStopButton() {

        handler.post {

            try {

                startStopButton?.text =
                    if (running) "⏸ STOP" else "▶ SCAN"

            } catch (_: Exception) {
            }
        }
    }


    private fun updateGatherButton() {

        handler.post {

            try {

                gatherButton?.text =
                    if (autoGatheringActive) "⏸ STOP GATHER" else "⚔ AUTO GATHER"

            } catch (_: Exception) {
            }
        }
    }


    private fun safeStatus(status: String) {

        handler.post {

            try {

                infoText?.text =
                    status

            } catch (_: Exception) {
            }
        }
    }
}
