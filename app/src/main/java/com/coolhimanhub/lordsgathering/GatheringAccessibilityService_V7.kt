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

    private var scanCount = 0


    // =============================================================
    // V7 SETTINGS
    // =============================================================

    private val scanInterval =
        4000L

    /*
     * We deliberately keep this low.
     *
     * A tile should not be rejected just because the resource
     * classifier is uncertain.
     */
    private val minimumConfidence =
        18

    /*
     * V6.3 displayed only six.
     *
     * That was confusing because many detected tiles were hidden.
     */
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
                    "Lords Assistant V7"

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
        // START / STOP
        // =========================================================

        val startButton =
            Button(this).apply {

                text =
                    "▶ START"

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
        // SCAN BUTTON
        // =========================================================

        val scanBtn =
            Button(this).apply {

                text =
                    "🔍 SCAN"

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
        // STATUS TEXT
        // =========================================================

        val info =
            TextView(this).apply {

                text =
                    "V7 Scanner ready\n" +
                        "Detects RSS levels 1-5\n" +
                        "Flag/occupation detection enabled\n" +
                        "SAFE TEST: no troop sent"

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


        container.addView(
            title
        )

        container.addView(
            startButton
        )

        container.addView(
            scanBtn
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
                "V7 Scanner ready\n" +
                    "Press SCAN\n" +
                    "Levels 1-5\n" +
                    "Flag detection ON\n" +
                    "SAFE TEST: no troop sent"
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
    // START AUTOMATION
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
            "V7 AUTO SCAN started\n" +
                "Scanning every 4 seconds\n" +
                "Levels 1-5\n" +
                "Flag detection ON\n" +
                "No map movement\n" +
                "No troop sent"
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
    // STOP
    // =============================================================

    private fun stopAutomation() {

        running =
            false

        handler.removeCallbacks(
            scanRunnable
        )

        updateStartStopButton()

        safeStatus(
            "STOPPED\n" +
                "Scanning stopped\n" +
                "Overlay remains active\n" +
                "SAFE TEST: no troop sent"
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

                            closeScreenshot(
                                screenshot
                            )

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
                                "\nSAFE TEST: no troop sent"
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
                    "\nSAFE TEST: no troop sent"
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
                    "Reading RSS badges..."
            )


            analysisExecutor.execute {

                try {

                    if (serviceAlive) {

                        analyseScreen(
                            bitmap,
                            thisScan
                        )
                    }

                } catch (e: Exception) {

                    safeStatus(
                        "Scan #$thisScan\n" +
                            "Analysis exception: " +
                            e.javaClass.simpleName +
                            "\nSAFE TEST: no troop sent"
                    )

                } finally {

                    screenshotInProgress =
                        false

                    try {

                        bitmap.recycle()

                    } catch (_: Exception) {
                    }
                }
            }

        } catch (e: Exception) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "Processing error: " +
                    e.javaClass.simpleName +
                    "\nSAFE TEST: no troop sent"
            )

            screenshotInProgress =
                false

        } finally {

            closeScreenshot(
                screenshot
            )
        }
    }


    // =============================================================
    // CLOSE SCREENSHOT
    // =============================================================

    private fun closeScreenshot(
        screenshot:
            ScreenshotResult
    ) {

        try {

            screenshot.hardwareBuffer.close()

        } catch (_: Exception) {
        }
    }


    // =============================================================
    // MAIN ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap:
            Bitmap,

        thisScan:
            Int
    ) {

        // ---------------------------------------------------------
        // STEP 1
        // Find all blue level badges.
        // ---------------------------------------------------------

        val badges =
            try {

                detectBlueBadges(
                    bitmap
                )

            } catch (_: Exception) {

                emptyList()
            }


        if (badges.isEmpty()) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "Blue RSS badges not found\n" +
                    "Try SCAN again\n" +
                    "SAFE TEST: no troop sent"
            )

            return
        }


        safeStatus(
            "Scan #$thisScan\n" +
                "Badges found: " +
                badges.size +
                "\nRunning OCR..."
        )


        // ---------------------------------------------------------
        // STEP 2
        // V7: NO WHOLE-MAP LEVEL OCR.
        // Each resource level is read only from its own blue badge.
        // This prevents words/UI numbers from becoming fake levels.
        // ---------------------------------------------------------


        // ---------------------------------------------------------
        // STEP 3
        // Convert badges into resource candidates.
        // ---------------------------------------------------------

        val candidates =
            mutableListOf<RssCandidate>()


        for (badge in badges) {

            if (!serviceAlive) {
                break
            }


            val level =
                findLevelForBadge(
                    bitmap,
                    badge
                )


            /*
             * Do NOT create a fake candidate with Lv0.
             *
             * Only 1-5 are legitimate resource levels.
             */
            if (
                level !in 1..5
            ) {
                continue
            }


            val type =
                classifyResource(
                    bitmap,
                    badge.centerX,
                    badge.centerY,
                    badge.width,
                    badge.height
                )


            val confidence =
                resourceConfidence(
                    bitmap,
                    badge.centerX,
                    badge.centerY,
                    badge.width,
                    badge.height,
                    type
                )


            if (
                confidence <
                minimumConfidence
            ) {
                continue
            }


            val flagScore =
                detectResourceFlag(
                    bitmap,
                    badge.centerX,
                    badge.centerY,
                    badge.width,
                    badge.height
                )


            val occupied =
                flagScore >= 55


            val targetScore =
                calculateTargetScore(
                    type,
                    level,
                    confidence,
                    flagScore
                )


            candidates.add(

                RssCandidate(

                    type =
                        type,

                    level =
                        level,

                    x =
                        badge.centerX,

                    y =
                        badge.centerY,

                    confidence =
                        confidence,

                    occupied =
                        occupied,

                    flagScore =
                        flagScore,

                    targetScore =
                        targetScore,

                    badgeWidth =
                        badge.width,

                    badgeHeight =
                        badge.height
                )
            )
        }


        // ---------------------------------------------------------
        // STEP 4
        // Remove duplicate detections.
        // ---------------------------------------------------------

        val cleaned =
            removeDuplicates(
                candidates
            )


        if (cleaned.isEmpty()) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "Badges: " +
                    badges.size +
                    "\n" +
                    "No valid Lv1-Lv5 RSS identified\n" +
                    "SAFE TEST: no troop sent"
            )

            return
        }


        // ---------------------------------------------------------
        // STEP 5
        // Sort.
        // ---------------------------------------------------------

        val sorted =
            cleaned.sortedWith(

                compareByDescending<RssCandidate> {

                    it.targetScore

                }.thenByDescending {

                    it.level

                }.thenByDescending {

                    it.confidence
                }
            )


        val emptyTargets =
            sorted.filter {
                !it.occupied
            }


        val occupiedTargets =
            sorted.filter {
                it.occupied
            }


        val best =
            emptyTargets.firstOrNull()


        // ---------------------------------------------------------
        // COUNTS
        // ---------------------------------------------------------

        val level1 =
            sorted.count {
                it.level == 1
            }

        val level2 =
            sorted.count {
                it.level == 2
            }

        val level3 =
            sorted.count {
                it.level == 3
            }

        val level4 =
            sorted.count {
                it.level == 4
            }

        val level5 =
            sorted.count {
                it.level == 5
            }


        val food =
            sorted.count {
                it.type == "Food"
            }

        val wood =
            sorted.count {
                it.type == "Wood"
            }

        val stone =
            sorted.count {
                it.type == "Stone"
            }

        val ore =
            sorted.count {
                it.type == "Ore"
            }

        val gold =
            sorted.count {
                it.type == "Gold"
            }


        // ---------------------------------------------------------
        // OUTPUT
        // ---------------------------------------------------------

        val output =
            StringBuilder()


        output.append(
            "Scan #$thisScan\n"
        )

        output.append(
            "RSS FOUND: "
        )

        output.append(
            sorted.size
        )

        output.append(
            "\n"
        )

        output.append(
            "EMPTY: "
        )

        output.append(
            emptyTargets.size
        )

        output.append(
            "\n"
        )

        output.append(
            "FLAGGED/OCCUPIED: "
        )

        output.append(
            occupiedTargets.size
        )

        output.append(
            "\n\n"
        )


        output.append(
            "LEVELS: "
        )

        output.append(
            "L1="
        )

        output.append(
            level1
        )

        output.append(
            "  L2="
        )

        output.append(
            level2
        )

        output.append(
            "  L3="
        )

        output.append(
            level3
        )

        output.append(
            "  L4="
        )

        output.append(
            level4
        )

        output.append(
            "  L5="
        )

        output.append(
            level5
        )

        output.append(
            "\n"
        )


        output.append(
            "TYPE: "
        )

        output.append(
            "F="
        )

        output.append(
            food
        )

        output.append(
            " W="
        )

        output.append(
            wood
        )

        output.append(
            " S="
        )

        output.append(
            stone
        )

        output.append(
            " O="
        )

        output.append(
            ore
        )

        output.append(
            " G="
        )

        output.append(
            gold
        )

        output.append(
            "\n\n"
        )


        output.append(
            "DETECTED RSS:\n"
        )


        val displayCount =
            min(
                sorted.size,
                maximumDisplayedTargets
            )


        for (
            i in 0 until displayCount
        ) {

            val rss =
                sorted[i]


            output.append(
                String.format(
                    Locale.US,
                    "%2d. %-5s Lv%d (%d,%d) C%d F%d",
                    i + 1,
                    rss.type,
                    rss.level,
                    rss.x,
                    rss.y,
                    rss.confidence,
                    rss.flagScore
                )
            )


            if (rss.occupied) {

                output.append(
                    " [FLAGGED]"
                )
            }


            output.append(
                "\n"
            )
        }


        if (
            sorted.size >
            maximumDisplayedTargets
        ) {

            output.append(
                "\n... "
            )

            output.append(
                sorted.size -
                    maximumDisplayedTargets
            )

            output.append(
                " more detected"
            )

            output.append(
                "\n"
            )
        }


        output.append(
            "\n"
        )


        if (best != null) {

            output.append(
                "SAFE TARGET:\n"
            )

            output.append(
                best.type
            )

            output.append(
                " Lv"
            )

            output.append(
                best.level
            )

            output.append(
                " @ screen("
            )

            output.append(
                best.x
            )

            output.append(
                ","
            )

            output.append(
                best.y
            )

            output.append(
                ")"
            )

            output.append(
                "\n"
            )

            output.append(
                "Confidence: "
            )

            output.append(
                best.confidence
            )

            output.append(
                "\n"
            )

            output.append(
                "Flag score: "
            )

            output.append(
                best.flagScore
            )

        } else {

            output.append(
                "SAFE TARGET: NONE\n"
            )

            output.append(
                "All detected RSS are flagged/occupied"
            )
        }


        output.append(
            "\n\nCOORDINATES = SCREEN PIXELS (NOT WORLD X/Y)"
        )

        output.append(
            "\nLEVEL RANGE: 1-5"
        )

        output.append(
            "\nFLAG DETECTION: ON"
        )

        output.append(
            "\nLEVEL OCR: BLUE BADGE ONLY"
        )

        output.append(
            "\nNO TROOP SENT"
        )


        safeStatus(
            output.toString()
        )
    }


    // =============================================================
    // BLUE BADGE DETECTION
    // =============================================================

    private fun detectBlueBadges(
        bitmap:
            Bitmap
    ): List<BlueComponent> {

        val width =
            bitmap.width

        val height =
            bitmap.height


        /*
         * Two-pixel sampling gives a large performance improvement
         * while still retaining enough information for the badge.
         */
        val step =
            2


        val gridWidth =
            (
                width +
                    step -
                    1
                ) / step

        val gridHeight =
            (
                height +
                    step -
                    1
                ) / step


        val visited =
            BooleanArray(
                gridWidth *
                    gridHeight
            )


        val queueX =
            IntArray(
                gridWidth *
                    gridHeight
            )

        val queueY =
            IntArray(
                gridWidth *
                    gridHeight
            )


        val components =
            mutableListOf<BlueComponent>()


        fun isBlue(
            gx: Int,
            gy: Int
        ): Boolean {

            val x =
                min(
                    width - 1,
                    gx * step
                )

            val y =
                min(
                    height - 1,
                    gy * step
                )


            val pixel =
                bitmap.getPixel(
                    x,
                    y
                )


            val r =
                Color.red(pixel)

            val g =
                Color.green(pixel)

            val b =
                Color.blue(pixel)


            /*
             * V6.3 was too strict.
             *
             * A real badge can have blue values where B is not
             * dramatically greater than G.
             */
            return (

                b >= 90 &&

                    b - r >= 15 &&

                    b >= g * 0.92f &&

                    b >= r * 1.10f
                )
        }


        for (
            gy in 4 until
                max(
                    5,
                    gridHeight - 4
                )
        ) {

            for (
                gx in 2 until
                    max(
                        3,
                        gridWidth - 2
                    )
            ) {

                val startIndex =
                    gy *
                        gridWidth +
                        gx


                if (
                    visited[startIndex]
                ) {
                    continue
                }


                if (
                    !isBlue(
                        gx,
                        gy
                    )
                ) {

                    visited[startIndex] =
                        true

                    continue
                }


                var head =
                    0

                var tail =
                    0


                queueX[tail] =
                    gx

                queueY[tail] =
                    gy

                tail++


                visited[startIndex] =
                    true


                var minX =
                    gx

                var maxX =
                    gx

                var minY =
                    gy

                var maxY =
                    gy

                var pixels =
                    0


                while (
                    head < tail
                ) {

                    val cx =
                        queueX[head]

                    val cy =
                        queueY[head]

                    head++


                    pixels++


                    minX =
                        min(
                            minX,
                            cx
                        )

                    maxX =
                        max(
                            maxX,
                            cx
                        )

                    minY =
                        min(
                            minY,
                            cy
                        )

                    maxY =
                        max(
                            maxY,
                            cy
                        )


                    val nx =
                        intArrayOf(
                            cx + 1,
                            cx - 1,
                            cx,
                            cx,
                            cx + 1,
                            cx - 1,
                            cx + 1,
                            cx - 1
                        )

                    val ny =
                        intArrayOf(
                            cy,
                            cy,
                            cy + 1,
                            cy - 1,
                            cy + 1,
                            cy - 1,
                            cy - 1,
                            cy + 1
                        )


                    for (
                        k in nx.indices
                    ) {

                        val x2 =
                            nx[k]

                        val y2 =
                            ny[k]


                        if (
                            x2 < 0 ||
                            y2 < 0 ||
                            x2 >= gridWidth ||
                            y2 >= gridHeight
                        ) {
                            continue
                        }


                        val index =
                            y2 *
                                gridWidth +
                                x2


                        if (
                            visited[index]
                        ) {
                            continue
                        }


                        visited[index] =
                            true


                        if (
                            isBlue(
                                x2,
                                y2
                            ) &&
                            tail <
                            queueX.size
                        ) {

                            queueX[tail] =
                                x2

                            queueY[tail] =
                                y2

                            tail++
                        }
                    }
                }


                val boxWidth =
                    (
                        maxX -
                            minX +
                            1
                        ) * step


                val boxHeight =
                    (
                        maxY -
                            minY +
                            1
                        ) * step


                /*
                 * Badge geometry.
                 *
                 * We deliberately allow more variation than V6.3.
                 */
                if (
                    pixels !in 8..1800
                ) {
                    continue
                }


                if (
                    boxWidth !in 8..120
                ) {
                    continue
                }


                if (
                    boxHeight !in 8..90
                ) {
                    continue
                }


                if (
                    boxWidth *
                    boxHeight >
                    6500
                ) {
                    continue
                }


                /*
                 * A resource icon can contain blue/purple pixels.
                 * Large components are usually the icon, not the badge.
                 */
                val aspect =
                    boxWidth.toFloat() /
                        max(
                            1,
                            boxHeight
                        )


                if (
                    aspect < 0.35f ||
                    aspect > 3.2f
                ) {
                    continue
                }


                components.add(

                    BlueComponent(

                        minX =
                            minX * step,

                        minY =
                            minY * step,

                        maxX =
                            maxX * step,

                        maxY =
                            maxY * step,

                        pixels =
                            pixels
                    )
                )
            }
        }


        // ---------------------------------------------------------
        // MERGE FRAGMENTED BADGES
        // ---------------------------------------------------------

        val merged =
            mergeBlueComponents(
                components
            )


        return merged
    }


    // =============================================================
    // MERGE BLUE COMPONENTS
    // =============================================================

    private fun mergeBlueComponents(
        input:
            List<BlueComponent>
    ): List<BlueComponent> {

        val result =
            input
                .map {
                    it.copy()
                }
                .toMutableList()


        var changed =
            true


        while (changed) {

            changed =
                false


            outer@ for (
                i in result.indices
            ) {

                for (
                    j in i + 1 until
                        result.size
                ) {

                    val a =
                        result[i]

                    val b =
                        result[j]


                    val dx =
                        abs(
                            a.centerX -
                                b.centerX
                        )

                    val dy =
                        abs(
                            a.centerY -
                                b.centerY
                        )


                    /*
                     * Badge pieces are normally close together.
                     */
                    if (
                        dx <= 24 &&
                        dy <= 24
                    ) {

                        val merged =
                            BlueComponent(

                                minX =
                                    min(
                                        a.minX,
                                        b.minX
                                    ),

                                minY =
                                    min(
                                        a.minY,
                                        b.minY
                                    ),

                                maxX =
                                    max(
                                        a.maxX,
                                        b.maxX
                                    ),

                                maxY =
                                    max(
                                        a.maxY,
                                        b.maxY
                                    ),

                                pixels =
                                    a.pixels +
                                        b.pixels
                            )


                        result.removeAt(
                            j
                        )

                        result.removeAt(
                            i
                        )

                        result.add(
                            merged
                        )

                        changed =
                            true

                        break@outer
                    }
                }
            }
        }


        return result
    }


    // =============================================================
    // WHOLE-SCREEN OCR
    // =============================================================

    private fun readDigitsFromScreen(
        bitmap:
            Bitmap
    ): List<OcrDigit> {

        val cropLeft =
            0

        val cropTop =
            max(
                20,
                bitmap.height / 20
            )

        val cropRight =
            bitmap.width

        val cropBottom =
            min(
                bitmap.height,
                (bitmap.height * 0.90f)
                    .toInt()
            )


        val cropWidth =
            cropRight -
                cropLeft

        val cropHeight =
            cropBottom -
                cropTop


        if (
            cropWidth <= 0 ||
            cropHeight <= 0
        ) {
            return emptyList()
        }


        val crop =
            Bitmap.createBitmap(
                bitmap,
                cropLeft,
                cropTop,
                cropWidth,
                cropHeight
            )


        /*
         * Upscale OCR input.
         *
         * Small badge digits are the main reason Level 4 was
         * disappearing in earlier versions.
         */
        val scaled =
            Bitmap.createScaledBitmap(
                crop,
                crop.width * 2,
                crop.height * 2,
                true
            )


        val input =
            InputImage.fromBitmap(
                scaled,
                0
            )


        val visionText =
            try {

                Tasks.await(
                    textRecognizer.process(
                        input
                    ),
                    2500,
                    TimeUnit.MILLISECONDS
                )

            } catch (_: Exception) {

                null
            }


        val result =
            mutableListOf<OcrDigit>()


        if (visionText != null) {

            for (
                block in
                    visionText.textBlocks
            ) {

                for (
                    line in
                        block.lines
                ) {

                    for (
                        element in
                            line.elements
                    ) {

                        val raw =
                            element.text
                                .trim()


                        val box =
                            element.boundingBox
                                ?: continue


                        /*
                         * Keep only small pieces that contain a
                         * legitimate resource level.
                         */
                        val normalized =
                            normalizeLevelText(
                                raw
                            )


                        if (
                            normalized in 1..5
                        ) {

                            val cx =
                                box.centerX() / 2 +
                                    cropLeft

                            val cy =
                                box.centerY() / 2 +
                                    cropTop

                            val w =
                                max(
                                    1,
                                    box.width() / 2
                                )

                            val h =
                                max(
                                    1,
                                    box.height() / 2
                                )


                            result.add(

                                OcrDigit(

                                    digit =
                                        normalized,

                                    centerX =
                                        cx,

                                    centerY =
                                        cy,

                                    width =
                                        w,

                                    height =
                                        h,

                                    rawText =
                                        raw
                                )
                            )
                        }
                    }
                }
            }
        }


        try {
            scaled.recycle()
        } catch (_: Exception) {
        }

        try {
            crop.recycle()
        } catch (_: Exception) {
        }


        return result
    }


    // =============================================================
    // V7 STRICT LEVEL OCR
    // =============================================================

    /*
     * V7 RULE:
     * A resource level is a single digit 1-5 physically inside the
     * blue RSS badge. Letters are NEVER converted into numbers.
     * Therefore text such as "Stone" can never become Level 5.
     */
    private fun normalizeLevelText(
        raw: String
    ): Int {

        val cleaned =
            raw
                .trim()
                .replace("\n", "")
                .replace("\r", "")
                .replace(" ", "")

        if (cleaned.length != 1) {
            return 0
        }

        val ch = cleaned[0]

        return if (ch in '1'..'5') {
            ch.digitToInt()
        } else {
            0
        }
    }


    // =============================================================
    // LEVEL ASSOCIATION
    // =============================================================

    private fun findLevelForBadge(
        bitmap: Bitmap,
        badge: BlueComponent
    ): Int {

        return readLevelFromBadgeFallback(
            bitmap,
            badge
        )
    }


    // =============================================================
    // STRICT BADGE OCR
    // =============================================================

    private fun readLevelFromBadgeFallback(
        bitmap: Bitmap,
        badge: BlueComponent
    ): Int {

        // Tight crop: only the detected blue badge plus a 1-pixel margin.
        // Never OCR the surrounding resource name/map/UI.
        val left = max(0, badge.minX - 1)
        val top = max(0, badge.minY - 1)
        val right = min(bitmap.width, badge.maxX + 2)
        val bottom = min(bitmap.height, badge.maxY + 2)

        val width = right - left
        val height = bottom - top

        if (width < 6 || height < 6) {
            return 0
        }

        val crop =
            try {
                Bitmap.createBitmap(
                    bitmap,
                    left,
                    top,
                    width,
                    height
                )
            } catch (_: Exception) {
                return 0
            }

        val votes = mutableListOf<Int>()

        try {
            // Pass 1: original badge.
            val original = Bitmap.createScaledBitmap(
                crop,
                max(48, width * 8),
                max(48, height * 8),
                true
            )

            try {
                val digit = ocrStrictSingleDigit(original)
                if (digit in 1..5) votes.add(digit)
            } finally {
                try { original.recycle() } catch (_: Exception) {}
            }

            // Pass 2: isolate the white digit.
            val whiteMask = createWhiteDigitMask(crop)
            try {
                val scaled = Bitmap.createScaledBitmap(
                    whiteMask,
                    max(48, width * 10),
                    max(48, height * 10),
                    true
                )
                try {
                    val digit = ocrStrictSingleDigit(scaled)
                    if (digit in 1..5) votes.add(digit)
                } finally {
                    try { scaled.recycle() } catch (_: Exception) {}
                }
            } finally {
                try { whiteMask.recycle() } catch (_: Exception) {}
            }

            // Pass 3: brighter threshold for anti-aliased digits.
            val brightMask = createBrightDigitMask(crop)
            try {
                val scaled = Bitmap.createScaledBitmap(
                    brightMask,
                    max(48, width * 10),
                    max(48, height * 10),
                    true
                )
                try {
                    val digit = ocrStrictSingleDigit(scaled)
                    if (digit in 1..5) votes.add(digit)
                } finally {
                    try { scaled.recycle() } catch (_: Exception) {}
                }
            } finally {
                try { brightMask.recycle() } catch (_: Exception) {}
            }
        } finally {
            try { crop.recycle() } catch (_: Exception) {}
        }

        if (votes.size < 2) {
            // V7 deliberately refuses a one-pass guess.
            return 0
        }

        val counts = votes.groupingBy { it }.eachCount()
        val ranked = counts.entries.sortedByDescending { it.value }
        val best = ranked.first()
        val second = ranked.getOrNull(1)

        // Never guess when OCR passes disagree equally.
        if (second != null && best.value == second.value) {
            return 0
        }

        return best.key
    }


    // =============================================================
    // STRICT SINGLE-DIGIT OCR
    // =============================================================

    private fun ocrStrictSingleDigit(
        bitmap: Bitmap
    ): Int {

        val input =
            try {
                InputImage.fromBitmap(bitmap, 0)
            } catch (_: Exception) {
                return 0
            }

        val visionText =
            try {
                Tasks.await(
                    textRecognizer.process(input),
                    900,
                    TimeUnit.MILLISECONDS
                )
            } catch (_: Exception) {
                null
            }

        if (visionText == null) {
            return 0
        }

        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                for (element in line.elements) {
                    val value = normalizeLevelText(element.text)
                    if (value in 1..5) return value
                }
            }
        }

        // Strict whole-image fallback: only exactly "1".."5" is valid.
        return normalizeLevelText(visionText.text)
    }


    // =============================================================
    // WHITE DIGIT MASK
    // =============================================================

    private fun createWhiteDigitMask(
        source: Bitmap
    ): Bitmap {

        val out = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )

        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val maxRgb = maxOf(r, g, b)
                val minRgb = minOf(r, g, b)

                val isWhite =
                    r >= 155 &&
                        g >= 155 &&
                        b >= 155 &&
                        maxRgb - minRgb <= 70

                out.setPixel(
                    x,
                    y,
                    if (isWhite) Color.WHITE else Color.BLACK
                )
                x++
            }
            y++
        }

        return out
    }


    // =============================================================
    // BRIGHT DIGIT MASK
    // =============================================================

    private fun createBrightDigitMask(
        source: Bitmap
    ): Bitmap {

        val out = Bitmap.createBitmap(
            source.width,
            source.height,
            Bitmap.Config.ARGB_8888
        )

        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val pixel = source.getPixel(x, y)
                val r = Color.red(pixel)
                val g = Color.green(pixel)
                val b = Color.blue(pixel)
                val brightness = (r + g + b) / 3

                val isBright =
                    brightness >= 185 &&
                        r >= 135 &&
                        g >= 135 &&
                        b >= 135

                out.setPixel(
                    x,
                    y,
                    if (isBright) Color.WHITE else Color.BLACK
                )
                x++
            }
            y++
        }

        return out
    }


    // =============================================================
    // RESOURCE CLASSIFICATION
    // =============================================================

    private fun classifyResource(
        bitmap:
            Bitmap,

        badgeX:
            Int,

        badgeY:
            Int,

        badgeWidth:
            Int,

        badgeHeight:
            Int
    ): String {

        /*
         * The resource icon is normally immediately to the
         * LEFT / slightly ABOVE the blue level badge.
         *
         * This is deliberately larger than V6.3 but excludes
         * most of the badge itself.
         */

        val left =
            max(
                0,
                badgeX -
                    max(
                        115,
                        badgeWidth * 3
                    )
            )

        val right =
            min(
                bitmap.width - 1,
                badgeX -
                    max(
                        10,
                        badgeWidth / 4
                    )
            )

        val top =
            max(
                0,
                badgeY -
                    max(
                        78,
                        badgeHeight * 2
                    )
            )

        val bottom =
            min(
                bitmap.height - 1,
                badgeY +
                    max(
                        18,
                        badgeHeight / 2
                    )
            )


        var foodScore =
            0

        var goldScore =
            0

        var woodScore =
            0

        var stoneScore =
            0

        var oreScore =
            0


        var samples =
            0


        var y =
            top


        while (
            y <= bottom
        ) {

            var x =
                left


            while (
                x <= right
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )


                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)


                /*
                 * Ignore the strong blue badge itself.
                 */
                if (
                    b >
                        r * 1.12f &&
                    b >=
                        g * 0.90f
                ) {

                    x += 3

                    continue
                }


                samples++


                val maxRgb =
                    maxOf(
                        r,
                        g,
                        b
                    )

                val minRgb =
                    minOf(
                        r,
                        g,
                        b
                    )

                val saturation =
                    maxRgb -
                        minRgb


                // -------------------------------------------------
                // FOOD
                // Strong yellow wheat.
                // -------------------------------------------------

                if (
                    r >= 145 &&
                    g >= 120 &&
                    b <= 115 &&
                    r >= g * 0.95f &&
                    saturation >= 35
                ) {

                    foodScore +=
                        3
                }


                // -------------------------------------------------
                // GOLD
                // Golden/orange material.
                // -------------------------------------------------

                if (
                    r >= 155 &&
                    g >= 90 &&
                    g <= 190 &&
                    b <= 95 &&
                    r > g * 1.05f &&
                    saturation >= 50
                ) {

                    goldScore +=
                        3
                }


                // -------------------------------------------------
                // WOOD
                // Brown logs / timber.
                // -------------------------------------------------

                if (
                    r >= 70 &&
                    r <= 190 &&
                    g >= 35 &&
                    g <= 130 &&
                    b <= 85 &&
                    r > g * 1.18f &&
                    saturation >= 35
                ) {

                    woodScore +=
                        3
                }


                // -------------------------------------------------
                // STONE
                // Low-saturation gray rock.
                // -------------------------------------------------

                if (
                    r in 65..210 &&
                    g in 65..210 &&
                    b in 65..210 &&
                    abs(r - g) <= 25 &&
                    abs(g - b) <= 25 &&
                    saturation <= 35
                ) {

                    stoneScore +=
                        4
                }


                // -------------------------------------------------
                // ORE
                //
                // Ore is usually much more saturated blue/purple
                // than the gray stone.
                // -------------------------------------------------

                if (
                    saturation >= 45 &&
                    (
                        (
                            b > r * 1.05f &&
                            b > g * 0.95f
                        ) ||
                        (
                            r > b * 0.85f &&
                            b > g * 1.05f
                        )
                    )
                ) {

                    oreScore +=
                        4
                }


                x += 3
            }


            y += 3
        }


        if (
            samples <= 0
        ) {
            return "Other"
        }


        val scores =
            mapOf(
                "Food" to foodScore,
                "Gold" to goldScore,
                "Wood" to woodScore,
                "Stone" to stoneScore,
                "Ore" to oreScore
            )


        /*
         * Sort by score.
         */
        val ranked =
            scores.entries
                .sortedByDescending {
                    it.value
                }


        if (
            ranked.isEmpty()
        ) {
            return "Other"
        }


        val best =
            ranked[0]

        val second =
            if (
                ranked.size > 1
            ) {

                ranked[1]

            } else {

                null
            }


        /*
         * Do not blindly call everything Ore.
         *
         * Ore needs a meaningful score advantage over Stone.
         */
        if (
            best.key == "Ore" &&
            second != null &&
            second.key == "Stone" &&
            best.value <
                second.value * 1.12f
        ) {

            return "Stone"
        }


        return best.key
    }


    // =============================================================
    // RESOURCE CONFIDENCE
    // =============================================================

    private fun resourceConfidence(
        bitmap:
            Bitmap,

        badgeX:
            Int,

        badgeY:
            Int,

        badgeWidth:
            Int,

        badgeHeight:
            Int,

        type:
            String
    ): Int {

        if (
            type == "Other"
        ) {
            return 0
        }


        val left =
            max(
                0,
                badgeX -
                    max(
                        105,
                        badgeWidth * 3
                    )
            )

        val right =
            min(
                bitmap.width - 1,
                badgeX -
                    max(
                        10,
                        badgeWidth / 4
                    )
            )

        val top =
            max(
                0,
                badgeY -
                    max(
                        70,
                        badgeHeight * 2
                    )
            )

        val bottom =
            min(
                bitmap.height - 1,
                badgeY +
                    max(
                        15,
                        badgeHeight / 2
                    )
            )


        var total =
            0

        var matches =
            0


        var y =
            top


        while (
            y <= bottom
        ) {

            var x =
                left


            while (
                x <= right
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )


                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)


                if (
                    b >
                        r * 1.12f &&
                    b >=
                        g * 0.90f
                ) {

                    x += 4

                    continue
                }


                total++


                val maxRgb =
                    maxOf(
                        r,
                        g,
                        b
                    )

                val minRgb =
                    minOf(
                        r,
                        g,
                        b
                    )

                val saturation =
                    maxRgb -
                        minRgb


                val match =
                    when (type) {

                        "Food" ->

                            r >= 145 &&
                                g >= 120 &&
                                b <= 115 &&
                                saturation >= 30


                        "Gold" ->

                            r >= 150 &&
                                g >= 90 &&
                                b <= 105 &&
                                r > g * 1.05f


                        "Wood" ->

                            r >= 70 &&
                                r <= 195 &&
                                g >= 35 &&
                                g <= 135 &&
                                b <= 90 &&
                                r > g * 1.15f


                        "Stone" ->

                            r in 65..210 &&
                                g in 65..210 &&
                                b in 65..210 &&
                                abs(r - g) <= 28 &&
                                abs(g - b) <= 28 &&
                                saturation <= 38


                        "Ore" ->

                            saturation >= 45 &&
                                (
                                    (
                                        b > r * 1.05f &&
                                            b > g * 0.95f
                                        ) ||
                                        (
                                            r > b * 0.85f &&
                                                b > g * 1.05f
                                            )
                                    )


                        else ->
                            false
                    }


                if (match) {
                    matches++
                }


                x += 4
            }


            y += 4
        }


        if (
            total <= 0
        ) {
            return 0
        }


        return min(
            100,
            matches * 100 / total
        )
    }


    // =============================================================
    // FLAG / OCCUPATION DETECTION
    // =============================================================

    private fun detectResourceFlag(
        bitmap:
            Bitmap,

        badgeX:
            Int,

        badgeY:
            Int,

        badgeWidth:
            Int,

        badgeHeight:
            Int
    ): Int {

        /*
         * IMPORTANT:
         *
         * V6.3 searched a huge circle for red/orange pixels.
         *
         * That is not what we want.
         *
         * We specifically search the area around the RESOURCE
         * for the small red flag/pennant.
         */

        val left =
            max(
                0,
                badgeX -
                    max(
                        105,
                        badgeWidth * 3
                    )
            )

        val right =
            min(
                bitmap.width - 1,
                badgeX +
                    max(
                        18,
                        badgeWidth
                    )
            )

        val top =
            max(
                0,
                badgeY -
                    max(
                        75,
                        badgeHeight * 3
                    )
            )

        val bottom =
            min(
                bitmap.height - 1,
                badgeY +
                    max(
                        30,
                        badgeHeight
                    )
            )


        var redPixels =
            0

        var strongRed =
            0

        var redTop =
            0

        var redMiddle =
            0

        var redBottom =
            0


        var samples =
            0


        var y =
            top


        while (
            y <= bottom
        ) {

            var x =
                left


            while (
                x <= right
            ) {

                val dx =
                    x -
                        badgeX

                val dy =
                    y -
                        badgeY


                /*
                 * Don't inspect the blue badge itself.
                 */
                if (
                    dx * dx +
                    dy * dy <
                    max(
                        20,
                        badgeWidth
                    ) *
                    max(
                        20,
                        badgeWidth
                    )
                ) {

                    x += 3

                    continue
                }


                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )


                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)


                samples++


                /*
                 * Red flag pixels.
                 */
                if (
                    r >= 150 &&
                    r > g * 1.35f &&
                    r > b * 1.25f
                ) {

                    redPixels++


                    if (
                        dy < -10
                    ) {

                        redTop++

                    } else if (
                        dy <= 10
                    ) {

                        redMiddle++

                    } else {

                        redBottom++
                    }


                    if (
                        r >= 190 &&
                        r > g * 1.45f &&
                        r > b * 1.35f
                    ) {

                        strongRed++
                    }
                }


                x += 3
            }


            y += 3
        }


        if (
            samples <= 0
        ) {
            return 0
        }


        /*
         * A flag normally produces a concentrated red cluster.
         */
        var score =
            0


        if (
            redPixels >= 8
        ) {
            score += 15
        }

        if (
            redPixels >= 16
        ) {
            score += 15
        }

        if (
            redPixels >= 28
        ) {
            score += 15
        }

        if (
            strongRed >= 4
        ) {
            score += 10
        }

        if (
            strongRed >= 8
        ) {
            score += 10
        }


        /*
         * A flag is usually above the resource.
         */
        if (
            redTop >
                redBottom &&
            redTop >
                redMiddle / 2
        ) {

            score += 15
        }


        /*
         * Extremely large red areas are more likely to be some
         * unrelated UI/game graphic than the tiny RSS flag.
         */
        if (
            redPixels > 180
        ) {

            score -= 20
        }


        return score.coerceIn(
            0,
            100
        )
    }


    // =============================================================
    // TARGET SCORE
    // =============================================================

    private fun calculateTargetScore(
        type:
            String,

        level:
            Int,

        confidence:
            Int,

        flagScore:
            Int
    ): Int {

        val priorityIndex =
            rssPriority.indexOf(
                type
            )


        val priorityScore =
            if (
                priorityIndex < 0
            ) {

                0

            } else {

                (
                    rssPriority.size -
                        priorityIndex
                    ) * 100
            }


        val levelScore =
            level * 35


        val confidenceScore =
            confidence


        /*
         * Flagged tiles are heavily penalized.
         */
        val flagPenalty =
            flagScore * 8


        return (
            priorityScore +
                levelScore +
                confidenceScore -
                flagPenalty
            )
            .coerceAtLeast(0)
    }


    // =============================================================
    // REMOVE DUPLICATES
    // =============================================================

    private fun removeDuplicates(
        input:
            List<RssCandidate>
    ): List<RssCandidate> {

        val output =
            mutableListOf<RssCandidate>()


        for (
            candidate in input
        ) {

            val existing =
                output.indexOfFirst {

                    abs(
                        it.x -
                            candidate.x
                    ) < 42 &&

                    abs(
                        it.y -
                            candidate.y
                    ) < 42
                }


            if (
                existing < 0
            ) {

                output.add(
                    candidate
                )

            } else {

                val old =
                    output[existing]


                if (
                    candidate.targetScore >
                    old.targetScore
                ) {

                    output[existing] =
                        candidate
                }
            }
        }


        return output
    }


    // =============================================================
    // BUTTON UPDATE
    // =============================================================

    private fun updateStartStopButton() {

        handler.post {

            try {

                startStopButton?.text =

                    if (running) {

                        "■ STOP"

                    } else {

                        "▶ START"
                    }

            } catch (_: Exception) {
            }
        }
    }


    // =============================================================
    // STATUS
    // =============================================================

    private fun safeStatus(
        message:
            String
    ) {

        if (!serviceAlive) {
            return
        }

        handler.post {

            try {

                infoText?.text =
                    message

            } catch (_: Exception) {
            }
        }
    }


    // =============================================================
    // RESERVED TAP
    //
    // IMPORTANT:
    // This remains unused.
    // No troop is sent by V7.
    // =============================================================

    private fun tap(
        x:
            Float,

        y:
            Float
    ) {

        if (!serviceAlive) {
            return
        }


        try {

            val path =
                Path().apply {

                    moveTo(
                        x,
                        y
                    )
                }


            val gesture =
                GestureDescription
                    .Builder()
                    .addStroke(

                        GestureDescription
                            .StrokeDescription(
                                path,
                                0,
                                100
                            )
                    )
                    .build()


            dispatchGesture(
                gesture,
                null,
                null
            )

        } catch (_: Exception) {
        }
    }


    // =============================================================
    // DESTROY
    // =============================================================

    override fun onDestroy() {

        serviceAlive =
            false

        running =
            false

        screenshotInProgress =
            false


        handler.removeCallbacks(
            scanRunnable
        )


        try {

            analysisExecutor.shutdownNow()

        } catch (_: Exception) {
        }


        try {

            textRecognizer.close()

        } catch (_: Exception) {
        }


        try {

            overlayView?.let {

                windowManager?.removeView(
                    it
                )
            }

        } catch (_: Exception) {
        }


        overlayView =
            null

        overlayParams =
            null

        windowManager =
            null

        infoText =
            null

        startStopButton =
            null

        scanButton =
            null


        super.onDestroy()
    }
}
