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
import android.widget.TextView

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
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
    // V6.3 SETTINGS
    // =============================================================

    private val scanInterval =
        4000L

    private val minimumConfidence =
        20

    private val maximumDisplayedTargets =
        6


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
                    "Lords Assistant V6.3"

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
                    "V6.3 Scanner ready\n" +
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
                "V6.3 Scanner ready\n" +
                    "Press SCAN\n" +
                    "OCR level detection: 1-5\n" +
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
            "V6.3 AUTO SCAN started\n" +
                "Scanning every 4 seconds\n" +
                "OCR level detection 1-5\n" +
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
                "Scanning stopped only\n" +
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

                safeStatus(
                    "Scan #$thisScan\n" +
                        "Bitmap conversion failed\n" +
                        "SAFE TEST: no troop sent"
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
                        "Bitmap copy failed\n" +
                        "SAFE TEST: no troop sent"
                )

                screenshotInProgress =
                    false

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

        val candidates =
            try {

                detectBlueRssBadges(
                    bitmap
                )

            } catch (_: Exception) {

                emptyList()
            }


        if (candidates.isEmpty()) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "No readable RSS badges found\n" +
                    "Level OCR accepts only 1-5\n" +
                    "Move map and SCAN again\n" +
                    "VISIBLE SCREEN ONLY\n" +
                    "SAFE TEST: no troop sent"
            )

            return
        }


        val cleaned =
            removeDuplicates(
                candidates
            )


        if (cleaned.isEmpty()) {

            safeStatus(
                "Scan #$thisScan\n" +
                    "No valid RSS targets\n" +
                    "VISIBLE SCREEN ONLY\n" +
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
            "\n"
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

            if (rss.occupied) {

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
                "Occupation score: "
            )

            output.append(
                best.occupationScore
            )

        } else {

            output.append(
                "SAFE TARGET: NONE\n"
            )

            output.append(
                "All detected RSS are suspicious/occupied"
            )
        }


        output.append(
            "\nVISIBLE SCREEN ONLY"
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
    // BLUE RSS BADGE DETECTION
    // =============================================================

    private fun detectBlueRssBadges(
        bitmap:
            Bitmap
    ): List<RssCandidate> {

        val width =
            bitmap.width

        val height =
            bitmap.height

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
            mutableListOf<RssCandidate>()


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


            return (

                b > 105 &&

                    b >
                    r * 1.18f &&

                    b >
                    g * 1.01f &&

                    b - r > 20
                )
        }


        for (
            gy in 5 until
                max(
                    5,
                    gridHeight - 5
                )
        ) {

            for (
                gx in 2 until
                    max(
                        2,
                        gridWidth - 2
                    )
            ) {

                val startIndex =
                    gy *
                        gridWidth +
                        gx


                if (
                    visited[startIndex] ||
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


                // -------------------------------------------------
                // BLUE BADGE SIZE FILTER
                // -------------------------------------------------

                if (
                    pixels !in 10..1800 ||
                    boxWidth !in 10..100 ||
                    boxHeight !in 8..70 ||
                    boxWidth *
                    boxHeight > 5000
                ) {
                    continue
                }


                val centerX =
                    (
                        (
                            minX +
                                maxX
                            ) / 2
                        ) * step


                val centerY =
                    (
                        (
                            minY +
                                maxY
                            ) / 2
                        ) * step


                // -------------------------------------------------
                // OCR THE BLUE LEVEL BADGE
                // -------------------------------------------------

                val level =
                    readLevelFromBadge(
                        bitmap,
                        minX * step,
                        minY * step,
                        boxWidth,
                        boxHeight
                    )


                // ONLY LEVELS 1-5 ARE VALID
                if (
                    level !in 1..5
                ) {
                    continue
                }


                val type =
                    classifyResource(
                        bitmap,
                        centerX,
                        centerY
                    )


                val confidence =
                    resourceConfidence(
                        bitmap,
                        centerX,
                        centerY,
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
                        centerX,
                        centerY
                    )


                val occupied =
                    occupation >= 35


                val targetScore =
                    calculateTargetScore(
                        type,
                        level,
                        confidence,
                        occupation
                    )


                result.add(

                    RssCandidate(

                        type =
                            type,

                        level =
                            level,

                        x =
                            centerX,

                        y =
                            centerY,

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
        }


        return result
    }


    // =============================================================
    // OCR LEVEL READER
    // =============================================================

    private fun readLevelFromBadge(
        bitmap:
            Bitmap,

        left:
            Int,

        top:
            Int,

        width:
            Int,

        height:
            Int
    ): Int {

        try {

            val extraX =
                max(
                    8,
                    width / 4
                )

            val extraY =
                max(
                    8,
                    height / 4
                )


            val cropLeft =
                max(
                    0,
                    left - extraX
                )

            val cropTop =
                max(
                    0,
                    top - extraY
                )

            val cropRight =
                min(
                    bitmap.width,
                    left +
                        width +
                        extraX
                )

            val cropBottom =
                min(
                    bitmap.height,
                    top +
                        height +
                        extraY
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
                return 0
            }


            val crop =
                Bitmap.createBitmap(
                    bitmap,
                    cropLeft,
                    cropTop,
                    cropWidth,
                    cropHeight
                )


            val input =
                InputImage.fromBitmap(
                    crop,
                    0
                )


            val visionText =
                try {

                    Tasks.await(
                        textRecognizer.process(
                            input
                        ),
                        1500,
                        TimeUnit.MILLISECONDS
                    )

                } catch (_: Exception) {

                    null
                }


            try {
                crop.recycle()
            } catch (_: Exception) {
            }


            if (visionText == null) {
                return 0
            }


            val raw =
                visionText.text


            // -----------------------------------------------------
            // OCR CLEANUP
            // -----------------------------------------------------

            val digits =
                raw
                    .replace(
                        "I",
                        "1",
                        ignoreCase = true
                    )
                    .replace(
                        "l",
                        "1"
                    )
                    .replace(
                        "O",
                        "0",
                        ignoreCase = true
                    )
                    .filter {
                        it.isDigit()
                    }


            // -----------------------------------------------------
            // GAME HAS ONLY LEVELS 1-5
            // -----------------------------------------------------

            for (char in digits) {

                val value =
                    char.digitToInt()

                if (
                    value in 1..5
                ) {
                    return value
                }
            }

        } catch (_: Exception) {
        }


        return 0
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
            when {

                priorityIndex < 0 ->
                    0

                else ->
                    (
                        rssPriority.size -
                            priorityIndex
                        ) * 100
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

        var yellow =
            0

        var brown =
            0

        var gray =
            0

        var cyan =
            0

        var orange =
            0


        val left =
            max(
                0,
                bx - 95
            )

        val right =
            min(
                bitmap.width - 1,
                bx - 12
            )

        val top =
            max(
                0,
                by - 65
            )

        val bottom =
            min(
                bitmap.height - 1,
                by + 15
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


                if (
                    !(
                        b > 100 &&
                            b >
                            r * 1.2f
                        )
                ) {

                    if (
                        r > 150 &&
                        g > 115 &&
                        b < 100
                    ) {
                        yellow++
                    }


                    if (
                        r > 85 &&
                        g in 45..155 &&
                        b < 95 &&
                        r >
                        g * 1.10f
                    ) {
                        brown++
                    }


                    if (
                        abs(r - g) < 28 &&
                        abs(g - b) < 28 &&
                        r in 85..220
                    ) {
                        gray++
                    }


                    if (
                        b > 90 &&
                        g > 80 &&
                        b >
                        r * 1.15f
                    ) {
                        cyan++
                    }


                    if (
                        r > 145 &&
                        g in 65..180 &&
                        b < 110 &&
                        r >
                        g * 1.12f
                    ) {
                        orange++
                    }
                }


                x += 5
            }


            y += 5
        }


        val food =
            yellow * 4

        val gold =
            yellow * 3 +
                orange * 2

        val wood =
            brown * 4

        val stone =
            gray * 5

        val ore =
            cyan * 4 +
                orange * 3


        val best =
            maxOf(
                food,
                gold,
                wood,
                stone,
                ore
            )


        return when {

            best <= 0 ->
                "Other"

            ore == best &&
                ore >
                stone * 1.15f ->
                "Ore"

            stone == best ->
                "Stone"

            wood == best ->
                "Wood"

            gold == best &&
                gold >
                food * 1.10f ->
                "Gold"

            food == best ->
                "Food"

            else ->
                "Other"
        }
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

        var total =
            0

        var match =
            0


        val left =
            max(
                0,
                bx - 75
            )

        val right =
            min(
                bitmap.width - 1,
                bx - 8
            )

        val top =
            max(
                0,
                by - 28
            )

        val bottom =
            min(
                bitmap.height - 1,
                by + 32
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


                if (
                    !(
                        b > 100 &&
                            b >
                            r * 1.2f
                        )
                ) {

                    total++


                    val matches =
                        when (type) {

                            "Food" ->

                                r > 150 &&
                                    g > 110 &&
                                    b < 110


                            "Gold" ->

                                r > 150 &&
                                    g > 100 &&
                                    b < 120


                            "Wood" ->

                                r > 85 &&
                                    g in 45..155 &&
                                    b < 100


                            "Stone" ->

                                abs(r - g) < 30 &&
                                    abs(g - b) < 30 &&
                                    r in 80..220


                            "Ore" ->

                                (
                                    b > 90 &&
                                        g > 75
                                    ) ||
                                    (
                                        r > 140 &&
                                            g > 60 &&
                                            b < 110
                                        )


                            else ->
                                false
                        }


                    if (matches) {
                        match++
                    }
                }


                x += 5
            }


            y += 5
        }


        return if (
            total == 0
        ) {

            0

        } else {

            min(
                100,
                match * 100 / total
            )
        }
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


        val left =
            max(
                0,
                centerX - 75
            )

        val right =
            min(
                bitmap.width - 1,
                centerX + 75
            )

        val top =
            max(
                0,
                centerY - 75
            )

        val bottom =
            min(
                bitmap.height - 1,
                centerY + 75
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
                    75 * 75
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


                    if (
                        r > 175 &&
                        r >
                        g * 1.35f &&
                        r >
                        b * 1.25f
                    ) {

                        suspicious += 2

                    } else if (
                        r > 130 &&
                        b > 100 &&
                        r >
                        g * 1.20f
                    ) {

                        suspicious += 1

                    } else if (
                        r > 190 &&
                        g > 170 &&
                        b > 120 &&
                        abs(r - g) < 70
                    ) {

                        suspicious += 1
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
                    samples / 5
                )
        )
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
                    ) < 45 &&

                    abs(
                        it.y -
                            candidate.y
                    ) < 45
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
