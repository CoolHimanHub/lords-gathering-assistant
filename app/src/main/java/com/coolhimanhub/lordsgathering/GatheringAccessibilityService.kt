package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.widget.TextView

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

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

    private val textRecognizer =
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
    // V6.4 SETTINGS
    // =============================================================

    private val scanInterval =
        4000L

    private val minimumConfidence =
        12

    private val maximumDisplayedTargets =
        12


    // =============================================================
    // RESOURCE PRIORITY
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
    // RSS CANDIDATE
    // =============================================================

    private data class RssCandidate(

        val type: String,

        val level: Int,

        val x: Int,

        val y: Int,

        val confidence: Int,

        val occupied: Boolean,

        val occupationScore: Int,

        val targetScore: Int
    )


    // =============================================================
    // OCR BADGE CANDIDATE
    // =============================================================

    private data class BadgeCandidate(

        val level: Int,

        val x: Int,

        val y: Int,

        val box: Rect,

        val ocrConfidence: Int
    )


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
                    "Lords Assistant V6.4"

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
        // STATUS
        // =========================================================

        val info =
            TextView(this).apply {

                text =
                    "V6.4 Scanner ready\n" +
                        "SAFE TEST: no troop sent"

                textSize =
                    11f

                setTextColor(
                    Color.WHITE
                )

                gravity =
                    Gravity.CENTER

                setPadding(
                    4,
                    5,
                    4,
                    2
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

        container.addView(
            info
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
            150

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
                "V6.4 Scanner ready\n" +
                    "Improved Lv1-Lv5 detection\n" +
                    "Full-screen OCR + visual detection\n" +
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
            "V6.4 AUTO SCAN started\n" +
                "Every 4 seconds\n" +
                "Lv1-Lv5 detection\n" +
                "Visible screen only\n" +
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
            "Scan #$thisScan - capturing..."
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

                screenshotInProgress =
                    false

                safeStatus(
                    "Scan #$thisScan\n" +
                        "Bitmap conversion failed\n" +
                        "SAFE TEST: no troop sent"
                )

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

                screenshotInProgress =
                    false

                safeStatus(
                    "Scan #$thisScan\n" +
                        "Bitmap copy failed\n" +
                        "SAFE TEST: no troop sent"
                )

                return
            }


            safeStatus(
                "Scan #$thisScan - analysing..."
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

            screenshotInProgress =
                false

            safeStatus(
                "Scan #$thisScan\n" +
                    "Processing error: " +
                    e.javaClass.simpleName +
                    "\nSAFE TEST: no troop sent"
            )

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
        // FULL SCREEN OCR
        // ---------------------------------------------------------

        safeStatus(
            "Scan #$thisScan\n" +
                "Step 1/3: finding level badges..."
        )

        val ocrBadges =
            findBadgesUsingFullScreenOcr(
                bitmap
            )


        // ---------------------------------------------------------
        // STEP 2
        // VISUAL BLUE BADGE DETECTION
        // ---------------------------------------------------------

        safeStatus(
            "Scan #$thisScan\n" +
                "Step 2/3: visual badge detection..."
        )

        val visualBadges =
            findBlueBadgeBoxes(
                bitmap
            )


        // ---------------------------------------------------------
        // STEP 3
        // MERGE BOTH METHODS
        // ---------------------------------------------------------

        safeStatus(
            "Scan #$thisScan\n" +
                "Step 3/3: combining detections..."
        )

        val allBadgeCandidates =
            mutableListOf<BadgeCandidate>()


        allBadgeCandidates.addAll(
            ocrBadges
        )


        for (
            box in visualBadges
        ) {

            val level =
                readLevelFromBadge(
                    bitmap,
                    box
                )

            if (
                level in 1..5
            ) {

                val centerX =
                    box.centerX()

                val centerY =
                    box.centerY()

                allBadgeCandidates.add(

                    BadgeCandidate(

                        level =
                            level,

                        x =
                            centerX,

                        y =
                            centerY,

                        box =
                            box,

                        ocrConfidence =
                            75
                    )
                )
            }
        }


        val mergedBadges =
            mergeBadgeCandidates(
                allBadgeCandidates
            )


        if (
            mergedBadges.isEmpty()
        ) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "No RSS level badges detected\n" +
                    "Lv1-Lv5 accepted\n" +
                    "VISIBLE SCREEN ONLY\n" +
                    "SAFE TEST: no troop sent"
            )

            return
        }


        // ---------------------------------------------------------
        // CONVERT BADGES INTO RSS CANDIDATES
        // ---------------------------------------------------------

        val rssCandidates =
            mutableListOf<RssCandidate>()


        for (
            badge in mergedBadges
        ) {

            val type =
                classifyResource(
                    bitmap,
                    badge.x,
                    badge.y
                )


            val confidence =
                resourceConfidence(
                    bitmap,
                    badge.x,
                    badge.y,
                    type
                )


            if (
                confidence <
                minimumConfidence
            ) {
                continue
            }


            val occupation =
                occupationScore(
                    bitmap,
                    badge.x,
                    badge.y
                )


            val occupied =
                occupation >= 55


            val targetScore =
                calculateTargetScore(
                    type,
                    badge.level,
                    confidence,
                    occupation
                )


            rssCandidates.add(

                RssCandidate(

                    type =
                        type,

                    level =
                        badge.level,

                    x =
                        badge.x,

                    y =
                        badge.y,

                    confidence =
                        confidence,

                    occupied =
                        occupied,

                    occupationScore =
                        occupation,

                    targetScore =
                        targetScore
                )
            )
        }


        val cleaned =
            removeDuplicates(
                rssCandidates
            )


        if (
            cleaned.isEmpty()
        ) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "Badges detected but no valid RSS\n" +
                    "Try SCAN again\n" +
                    "SAFE TEST: no troop sent"
            )

            return
        }


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


        val best =
            emptyTargets.firstOrNull()


        val output =
            StringBuilder()


        output.append(
            "Scan #$thisScan\n"
        )

        output.append(
            "RSS found: "
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
            "\n\n"
        )


        for (
            i in 0 until
                min(
                    sorted.size,
                    maximumDisplayedTargets
                )
        ) {

            val rss =
                sorted[i]


            output.append(
                "${i + 1}. "
            )

            output.append(
                rss.type
            )

            output.append(
                " Lv"
            )

            output.append(
                rss.level
            )

            output.append(
                " ("
            )

            output.append(
                rss.x
            )

            output.append(
                ","
            )

            output.append(
                rss.y
            )

            output.append(
                ") C"
            )

            output.append(
                rss.confidence
            )

            output.append(
                " S"
            )

            output.append(
                rss.targetScore
            )


            if (
                rss.occupied
            ) {

                output.append(
                    " [OCCUPIED]"
                )
            }


            output.append(
                "\n"
            )
        }


        output.append(
            "\n"
        )


        if (
            best != null
        ) {

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
                " @ "
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
                "\nConfidence: "
            )

            output.append(
                best.confidence
            )

            output.append(
                "\nOccupation score: "
            )

            output.append(
                best.occupationScore
            )

        } else {

            output.append(
                "SAFE TARGET: NONE\n"
            )

            output.append(
                "All detected RSS are occupied/suspicious"
            )
        }


        output.append(
            "\n\nVISIBLE SCREEN ONLY"
        )

        output.append(
            "\nLEVEL RANGE: 1-5"
        )

        output.append(
            "\nSAFE TEST: no troop sent"
        )


        safeStatus(
            output.toString()
        )
    }


    // =============================================================
    // FULL SCREEN OCR
    // =============================================================

    private fun findBadgesUsingFullScreenOcr(
        bitmap:
            Bitmap
    ): List<BadgeCandidate> {

        val result =
            mutableListOf<BadgeCandidate>()


        try {

            val scale =
                1.35f


            val scaledWidth =
                max(
                    1,
                    (bitmap.width * scale).toInt()
                )


            val scaledHeight =
                max(
                    1,
                    (bitmap.height * scale).toInt()
                )


            val scaled =
                Bitmap.createScaledBitmap(
                    bitmap,
                    scaledWidth,
                    scaledHeight,
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
                        2200,
                        TimeUnit.MILLISECONDS
                    )

                } catch (_: Exception) {

                    null
                }


            try {
                scaled.recycle()
            } catch (_: Exception) {
            }


            if (
                visionText == null
            ) {
                return result
            }


            for (
                block in visionText.textBlocks
            ) {

                for (
                    line in block.lines
                ) {

                    for (
                        element in line.elements
                    ) {

                        val raw =
                            element.text
                                .trim()


                        val level =
                            parseLevel(
                                raw
                            )


                        if (
                            level !in 1..5
                        ) {
                            continue
                        }


                        val originalBox =
                            element.boundingBox
                                ?: continue


                        val left =
                            (
                                originalBox.left /
                                    scale
                                ).toInt()


                        val top =
                            (
                                originalBox.top /
                                    scale
                                ).toInt()


                        val right =
                            (
                                originalBox.right /
                                    scale
                                ).toInt()


                        val bottom =
                            (
                                originalBox.bottom /
                                    scale
                                ).toInt()


                        val box =
                            Rect(
                                left,
                                top,
                                right,
                                bottom
                            )


                        if (
                            box.width() < 5 ||
                            box.height() < 5
                        ) {
                            continue
                        }


                        val blueScore =
                            badgeBlueScore(
                                bitmap,
                                box
                            )


                        // -------------------------------------------------
                        // IMPORTANT:
                        // Ignore normal UI numbers.
                        // We only accept OCR digits surrounded by
                        // enough badge-blue pixels.
                        // -------------------------------------------------

                        if (
                            blueScore < 0.10f
                        ) {
                            continue
                        }


                        val centerX =
                            box.centerX()

                        val centerY =
                            box.centerY()


                        val confidence =
                            min(
                                100,
                                55 +
                                    (
                                        blueScore *
                                            45
                                        ).toInt()
                            )


                        result.add(

                            BadgeCandidate(

                                level =
                                    level,

                                x =
                                    centerX,

                                y =
                                    centerY,

                                box =
                                    box,

                                ocrConfidence =
                                    confidence
                            )
                        )
                    }
                }
            }

        } catch (_: Exception) {
        }


        return result
    }


    // =============================================================
    // LEVEL PARSER
    // =============================================================

    private fun parseLevel(
        text:
            String
    ): Int {

        val cleaned =
            text
                .replace(
                    "I",
                    "1",
                    ignoreCase = true
                )
                .replace(
                    "L",
                    "1",
                    ignoreCase = true
                )
                .replace(
                    "l",
                    "1"
                )
                .trim()


        if (
            cleaned.length != 1
        ) {
            return 0
        }


        val value =
            cleaned[0]
                .digitToIntOrNull()
                ?: return 0


        return if (
            value in 1..5
        ) {
            value
        } else {
            0
        }
    }


    // =============================================================
    // VISUAL BLUE BADGE DETECTOR
    // =============================================================

    private fun findBlueBadgeBoxes(
        bitmap:
            Bitmap
    ): List<Rect> {

        val width =
            bitmap.width

        val height =
            bitmap.height


        // 2-pixel sampling gives substantially more coverage
        // than the previous V6.3 detector.
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


        val result =
            mutableListOf<Rect>()


        fun isBadgeBlue(
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


            return (
                b >= 105 &&
                    b - r >= 35 &&
                    b > g * 1.02f &&
                    r < 150
                )
        }


        for (
            gy in 3 until
                max(
                    3,
                    gridHeight - 3
                )
        ) {

            for (
                gx in 1 until
                    max(
                        1,
                        gridWidth - 1
                    )
            ) {

                val start =
                    gy *
                        gridWidth +
                        gx


                if (
                    visited[start]
                ) {
                    continue
                }


                if (
                    !isBadgeBlue(
                        gx,
                        gy
                    )
                ) {

                    visited[start] =
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


                visited[start] =
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
                            cx
                        )


                    val ny =
                        intArrayOf(
                            cy,
                            cy,
                            cy + 1,
                            cy - 1
                        )


                    for (
                        k in 0..3
                    ) {

                        val xx =
                            nx[k]

                        val yy =
                            ny[k]


                        if (
                            xx < 0 ||
                            yy < 0 ||
                            xx >= gridWidth ||
                            yy >= gridHeight
                        ) {
                            continue
                        }


                        val index =
                            yy *
                                gridWidth +
                                xx


                        if (
                            visited[index]
                        ) {
                            continue
                        }


                        visited[index] =
                            true


                        if (
                            isBadgeBlue(
                                xx,
                                yy
                            ) &&
                            tail <
                            queueX.size
                        ) {

                            queueX[tail] =
                                xx

                            queueY[tail] =
                                yy

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


                // -------------------------------------------------
                // RELAXED BUT COMPACT BADGE FILTER
                // -------------------------------------------------

                if (
                    pixels < 5 ||
                    pixels > 900 ||
                    boxWidth < 7 ||
                    boxWidth > 75 ||
                    boxHeight < 7 ||
                    boxHeight > 55
                ) {
                    continue
                }


                val ratio =
                    boxWidth.toFloat() /
                        max(
                            1,
                            boxHeight
                        )


                if (
                    ratio < 0.40f ||
                    ratio > 3.0f
                ) {
                    continue
                }


                val rect =
                    Rect(

                        minX * step,

                        minY * step,

                        min(
                            width,
                            (
                                maxX + 1
                            ) * step
                        ),

                        min(
                            height,
                            (
                                maxY + 1
                            ) * step
                        )
                    )


                // -------------------------------------------------
                // BADGE MUST HAVE BLUE DENSITY
                // -------------------------------------------------

                val blueScore =
                    badgeBlueScore(
                        bitmap,
                        rect
                    )


                if (
                    blueScore < 0.18f
                ) {
                    continue
                }


                result.add(
                    rect
                )
            }
        }


        return result
    }


    // =============================================================
    // BADGE BLUE SCORE
    // =============================================================

    private fun badgeBlueScore(
        bitmap:
            Bitmap,

        box:
            Rect
    ): Float {

        val marginX =
            max(
                5,
                box.width() / 2
            )


        val marginY =
            max(
                5,
                box.height() / 2
            )


        val left =
            max(
                0,
                box.left - marginX
            )


        val top =
            max(
                0,
                box.top - marginY
            )


        val right =
            min(
                bitmap.width - 1,
                box.right + marginX
            )


        val bottom =
            min(
                bitmap.height - 1,
                box.bottom + marginY
            )


        var blue =
            0


        var total =
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


                total++


                if (
                    b >= 95 &&
                    b - r >= 30 &&
                    b > g * 1.01f
                ) {

                    blue++
                }


                x += 2
            }


            y += 2
        }


        if (
            total == 0
        ) {
            return 0f
        }


        return blue.toFloat() /
            total.toFloat()
    }


    // =============================================================
    // INDIVIDUAL BADGE OCR
    // =============================================================

    private fun readLevelFromBadge(
        bitmap:
            Bitmap,

        badge:
            Rect
    ): Int {

        try {

            val extraX =
                max(
                    12,
                    badge.width()
                )


            val extraY =
                max(
                    12,
                    badge.height()
                )


            val left =
                max(
                    0,
                    badge.left - extraX
                )


            val top =
                max(
                    0,
                    badge.top - extraY
                )


            val right =
                min(
                    bitmap.width,
                    badge.right + extraX
                )


            val bottom =
                min(
                    bitmap.height,
                    badge.bottom + extraY
                )


            val cropWidth =
                right - left


            val cropHeight =
                bottom - top


            if (
                cropWidth <= 0 ||
                cropHeight <= 0
            ) {
                return 0
            }


            val crop =
                Bitmap.createBitmap(
                    bitmap,
                    left,
                    top,
                    cropWidth,
                    cropHeight
                )


            // -----------------------------------------------------
            // ATTEMPT 1
            // ORIGINAL, UPSCALED
            // -----------------------------------------------------

            val level1 =
                ocrSmallBadge(
                    crop,
                    false
                )


            if (
                level1 in 1..5
            ) {

                crop.recycle()

                return level1
            }


            // -----------------------------------------------------
            // ATTEMPT 2
            // HIGH-CONTRAST VERSION
            // -----------------------------------------------------

            val level2 =
                ocrSmallBadge(
                    crop,
                    true
                )


            try {
                crop.recycle()
            } catch (_: Exception) {
            }


            if (
                level2 in 1..5
            ) {
                return level2
            }

        } catch (_: Exception) {
        }


        return 0
    }


    // =============================================================
    // SMALL BADGE OCR
    // =============================================================

    private fun ocrSmallBadge(
        source:
            Bitmap,

        threshold:
            Boolean
    ): Int {

        try {

            val scale =
                3


            val scaled =
                Bitmap.createScaledBitmap(
                    source,
                    max(
                        30,
                        source.width * scale
                    ),
                    max(
                        30,
                        source.height * scale
                    ),
                    true
                )


            val imageBitmap =
                if (
                    threshold
                ) {

                    createWhiteDigitBitmap(
                        scaled
                    )

                } else {

                    scaled
                }


            val input =
                InputImage.fromBitmap(
                    imageBitmap,
                    0
                )


            val text =
                try {

                    Tasks.await(
                        textRecognizer.process(
                            input
                        ),
                        1000,
                        TimeUnit.MILLISECONDS
                    )

                } catch (_: Exception) {

                    null
                }


            if (
                text != null
            ) {

                val raw =
                    text.text


                val digits =
                    raw
                        .replace(
                            "I",
                            "1",
                            ignoreCase = true
                        )
                        .replace(
                            "L",
                            "1",
                            ignoreCase = true
                        )
                        .replace(
                            "l",
                            "1"
                        )
                        .filter {
                            it.isDigit()
                        }


                for (
                    char in digits
                ) {

                    val value =
                        char.digitToInt()


                    if (
                        value in 1..5
                    ) {

                        try {
                            imageBitmap.recycle()
                        } catch (_: Exception) {
                        }


                        if (
                            imageBitmap !== scaled
                        ) {

                            try {
                                scaled.recycle()
                            } catch (_: Exception) {
                            }
                        }


                        return value
                    }
                }
            }


            try {
                imageBitmap.recycle()
            } catch (_: Exception) {
            }


            if (
                imageBitmap !== scaled
            ) {

                try {
                    scaled.recycle()
                } catch (_: Exception) {
                }
            }

        } catch (_: Exception) {
        }


        return 0
    }


    // =============================================================
    // CREATE HIGH CONTRAST BADGE
    // =============================================================

    private fun createWhiteDigitBitmap(
        source:
            Bitmap
    ): Bitmap {

        val result =
            Bitmap.createBitmap(
                source.width,
                source.height,
                Bitmap.Config.ARGB_8888
            )


        var y =
            0


        while (
            y < source.height
        ) {

            var x =
                0


            while (
                x < source.width
            ) {

                val pixel =
                    source.getPixel(
                        x,
                        y
                    )


                val r =
                    Color.red(pixel)

                val g =
                    Color.green(pixel)

                val b =
                    Color.blue(pixel)


                val maxChannel =
                    maxOf(
                        r,
                        g,
                        b
                    )


                val minChannel =
                    minOf(
                        r,
                        g,
                        b
                    )


                val brightness =
                    (
                        r +
                            g +
                            b
                        ) / 3


                val lowSaturation =
                    maxChannel -
                        minChannel <
                        80


                val isWhite =
                    brightness > 155 &&
                        lowSaturation


                if (
                    isWhite
                ) {

                    result.setPixel(
                        x,
                        y,
                        Color.WHITE
                    )

                } else {

                    result.setPixel(
                        x,
                        y,
                        Color.BLACK
                    )
                }


                x++
            }


            y++
        }


        return result
    }


    // =============================================================
    // MERGE BADGE CANDIDATES
    // =============================================================

    private fun mergeBadgeCandidates(
        input:
            List<BadgeCandidate>
    ): List<BadgeCandidate> {

        val output =
            mutableListOf<BadgeCandidate>()


        for (
            candidate in input
        ) {

            val existing =
                output.indexOfFirst {

                    abs(
                        it.x -
                            candidate.x
                    ) < 32 &&

                    abs(
                        it.y -
                            candidate.y
                    ) < 32
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


                // If two methods found the same badge,
                // prefer the candidate with stronger OCR.
                if (
                    candidate.ocrConfidence >
                    old.ocrConfidence
                ) {

                    output[existing] =
                        candidate
                }
            }
        }


        return output
    }


    // =============================================================
    // RESOURCE CLASSIFICATION
    // =============================================================

    private fun classifyResource(
        bitmap:
            Bitmap,

        bx:
            Int,

        by:
            Int
    ): String {

        var food =
            0

        var wood =
            0

        var stone =
            0

        var ore =
            0

        var gold =
            0


        // ---------------------------------------------------------
        // RSS OBJECT IS GENERALLY ABOVE/LEFT OF THE LEVEL BADGE
        // ---------------------------------------------------------

        val left =
            max(
                0,
                bx - 100
            )


        val right =
            min(
                bitmap.width - 1,
                bx - 8
            )


        val top =
            max(
                0,
                by - 75
            )


        val bottom =
            min(
                bitmap.height - 1,
                by + 10
            )


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


                // Ignore the blue badge itself.
                if (
                    !isStrongBlue(
                        r,
                        g,
                        b
                    )
                ) {

                    // FOOD / WHEAT
                    if (
                        r > 145 &&
                        g > 110 &&
                        b < 125 &&
                        r > g * 0.95f
                    ) {

                        food++
                    }


                    // WOOD / LOGS
                    if (
                        r > 75 &&
                        g in 40..160 &&
                        b < 105 &&
                        r > g * 1.08f
                    ) {

                        wood++
                    }


                    // STONE / GREY ROCK
                    if (
                        abs(
                            r - g
                        ) < 38 &&

                        abs(
                            g - b
                        ) < 38 &&

                        r in 75..215
                    ) {

                        stone++
                    }


                    // ORE / BLUE-CYAN MINERAL
                    if (
                        b > 85 &&
                        g > 65 &&
                        b > r * 1.12f
                    ) {

                        ore++
                    }


                    // GOLD / GOLDEN MATERIAL
                    if (
                        r > 145 &&
                        g > 105 &&
                        b < 115 &&
                        r > b * 1.35f
                    ) {

                        gold++
                    }
                }


                x += 4
            }


            y += 4
        }


        val scores =
            mapOf(
                "Food" to food,
                "Wood" to wood,
                "Stone" to stone,
                "Ore" to ore,
                "Gold" to gold
            )


        val best =
            scores.maxByOrNull {
                it.value
            }


        if (
            best == null ||
            best.value < 8
        ) {

            return "Other"
        }


        return best.key
    }


    // =============================================================
    // STRONG BLUE TEST
    // =============================================================

    private fun isStrongBlue(
        r: Int,
        g: Int,
        b: Int
    ): Boolean {

        return (
            b > 100 &&
                b > r * 1.15f &&
                b > g * 1.02f
            )
    }


    // =============================================================
    // RESOURCE CONFIDENCE
    // =============================================================

    private fun resourceConfidence(
        bitmap:
            Bitmap,

        bx:
            Int,

        by:
            Int,

        type:
            String
    ): Int {

        var totalRelevant =
            0

        var matched =
            0


        val left =
            max(
                0,
                bx - 95
            )


        val right =
            min(
                bitmap.width - 1,
                bx - 8
            )


        val top =
            max(
                0,
                by - 70
            )


        val bottom =
            min(
                bitmap.height - 1,
                by + 10
            )


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


                val strongBlue =
                    isStrongBlue(
                        r,
                        g,
                        b
                    )


                val greenBackground =
                    g > r * 1.15f &&
                        g > b * 1.05f &&
                        g > 75


                if (
                    !strongBlue &&
                    !greenBackground
                ) {

                    totalRelevant++


                    val matches =
                        when (
                            type
                        ) {

                            "Food" ->

                                r > 140 &&
                                    g > 105 &&
                                    b < 130


                            "Wood" ->

                                r > 75 &&
                                    g in 35..165 &&
                                    b < 110 &&
                                    r > g * 1.05f


                            "Stone" ->

                                abs(
                                    r - g
                                ) < 42 &&

                                    abs(
                                        g - b
                                    ) < 42 &&

                                    r in 70..220


                            "Ore" ->

                                b > 85 &&
                                    g > 65 &&
                                    b > r * 1.08f


                            "Gold" ->

                                r > 140 &&
                                    g > 100 &&
                                    b < 125 &&
                                    r > b * 1.25f


                            else ->
                                false
                        }


                    if (
                        matches
                    ) {

                        matched++
                    }
                }


                x += 4
            }


            y += 4
        }


        if (
            totalRelevant == 0
        ) {
            return 0
        }


        val confidence =
            matched * 100 /
                totalRelevant


        return confidence.coerceIn(
            0,
            100
        )
    }


    // =============================================================
    // OCCUPATION SCORE
    // =============================================================

    private fun occupationScore(
        bitmap:
            Bitmap,

        centerX:
            Int,

        centerY:
            Int
    ): Int {

        var suspicious =
            0

        var samples =
            0


        val radius =
            70


        val left =
            max(
                0,
                centerX - radius
            )


        val right =
            min(
                bitmap.width - 1,
                centerX + radius
            )


        val top =
            max(
                0,
                centerY - radius
            )


        val bottom =
            min(
                bitmap.height - 1,
                centerY + radius
            )


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
                    x - centerX

                val dy =
                    y - centerY


                if (
                    dx * dx +
                    dy * dy <=
                    radius * radius
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


                    samples++


                    // Strong red = possible marching/occupancy marker.
                    if (
                        r > 175 &&
                        r > g * 1.35f &&
                        r > b * 1.25f
                    ) {

                        suspicious += 2

                    } else if (
                        r > 135 &&
                        b > 95 &&
                        r > g * 1.20f
                    ) {

                        suspicious++

                    }
                }


                x += 4
            }


            y += 4
        }


        if (
            samples == 0
        ) {
            return 0
        }


        return min(
            100,
            suspicious * 100 /
                max(
                    1,
                    samples / 6
                )
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

        occupation:
            Int
    ): Int {

        val priorityIndex =
            rssPriority.indexOf(
                type
            )


        val priorityScore =
            if (
                priorityIndex >= 0
            ) {

                (
                    rssPriority.size -
                        priorityIndex
                    ) * 100

            } else {

                0
            }


        val levelScore =
            level * 35


        val confidenceScore =
            confidence


        val occupationPenalty =
            occupation * 5


        return (
            priorityScore +
                levelScore +
                confidenceScore -
                occupationPenalty
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
                    ) < 38 &&

                    abs(
                        it.y -
                            candidate.y
                    ) < 38
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

                    if (
                        running
                    ) {

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
