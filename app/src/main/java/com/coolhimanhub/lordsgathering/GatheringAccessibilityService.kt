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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class GatheringAccessibilityService : AccessibilityService() {

    // =============================================================
    // BASIC STATE
    // =============================================================

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var screenshotInProgress = false

    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    private var infoText: TextView? = null
    private var startStopButton: Button? = null
    private var scanButton: Button? = null

    private var scanCount = 0

    // =============================================================
    // RSS MODEL
    // =============================================================

    private data class RssCandidate(
        val type: String,
        val level: Int,
        val x: Int,
        val y: Int,
        val confidence: Int,
        val occupied: Boolean
    )

    // User-selected priority.
    // Current order matches the app screen:
    // Emerging, Gold, Ore, Wood, Food, Stone, Other
    //
    // We keep this here for V2.
    // Later this will read the actual saved preference automatically.
    private val rssPriority = listOf(
        "Emerging",
        "Gold",
        "Ore",
        "Wood",
        "Food",
        "Stone",
        "Other"
    )

    // =============================================================
    // ACCESSIBILITY SERVICE
    // =============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()

        try {
            windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            showFloatingControl()

        } catch (_: Exception) {

            handler.post {
                recreateOverlay()
            }
        }
    }

    override fun onAccessibilityEvent(
        event: AccessibilityEvent?
    ) {
        // Nothing required yet.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    // =============================================================
    // FLOATING CONTROL
    // =============================================================

    private fun showFloatingControl() {

        if (overlayView != null) {
            return
        }

        val wm =
            windowManager
                ?: try {
                    getSystemService(WINDOW_SERVICE) as WindowManager
                } catch (_: Exception) {
                    return
                }

        windowManager = wm

        val container =
            LinearLayout(this).apply {

                orientation = LinearLayout.VERTICAL

                setPadding(
                    10,
                    8,
                    10,
                    8
                )

                setBackgroundColor(
                    Color.rgb(65, 65, 65)
                )
            }

        // =========================================================
        // TITLE / DRAG HANDLE
        // =========================================================

        val title =
            TextView(this).apply {

                text = "Lords Assistant V2"

                textSize = 16f

                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER

                setPadding(
                    8,
                    4,
                    8,
                    8
                )
            }

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

                    when (event.actionMasked) {

                        MotionEvent.ACTION_DOWN -> {

                            startX = event.rawX
                            startY = event.rawY

                            startParamX = params.x
                            startParamY = params.y

                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {

                            val dx =
                                event.rawX - startX

                            val dy =
                                event.rawY - startY

                            params.x =
                                (
                                    startParamX + dx
                                ).toInt()

                            params.y =
                                (
                                    startParamY + dy
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

                        MotionEvent.ACTION_UP -> {
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

                text = "▶ START"

                setOnClickListener {

                    try {

                        if (running) {
                            stopAutomation()
                        } else {
                            startAutomation()
                        }

                    } catch (e: Exception) {

                        updateInfo(
                            "Button error: " +
                                e.javaClass.simpleName
                        )
                    }
                }
            }

        // =========================================================
        // SCAN
        // =========================================================

        val scanBtn =
            Button(this).apply {

                text = "🔍 SCAN"

                setOnClickListener {

                    try {

                        scanScreen()

                    } catch (e: Exception) {

                        updateInfo(
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
                    "V2 Scanner ready\n" +
                    "SAFE TEST: no troop sent"

                textSize = 11f

                setTextColor(Color.WHITE)

                gravity = Gravity.CENTER

                setPadding(
                    4,
                    5,
                    4,
                    2
                )
            }

        infoText = info
        startStopButton = startButton
        scanButton = scanBtn

        container.addView(title)
        container.addView(startButton)
        container.addView(scanBtn)
        container.addView(info)

        // =========================================================
        // OVERLAY PARAMETERS
        // =========================================================

        val params =
            WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )

        params.gravity =
            Gravity.TOP or Gravity.START

        params.x = 100
        params.y = 150

        overlayParams = params

        // =========================================================
        // ADD OVERLAY
        // =========================================================

        try {

            wm.addView(
                container,
                params
            )

            overlayView = container

            updateInfo(
                "V2 Scanner ready\n" +
                "Press SCAN\n" +
                "SAFE TEST: no troop sent"
            )

        } catch (_: Exception) {

            overlayView = null
            overlayParams = null

            infoText = null
            startStopButton = null
            scanButton = null

            handler.postDelayed(
                {
                    recreateOverlay()
                },
                1000
            )
        }
    }

    // =============================================================
    // RECREATE
    // =============================================================

    private fun recreateOverlay() {

        try {

            if (overlayView != null) {
                return
            }

            showFloatingControl()

        } catch (_: Exception) {
        }
    }

    // =============================================================
    // START
    // =============================================================

    private fun startAutomation() {

        if (running) {
            return
        }

        running = true

        updateStartStopButton()

        updateInfo(
            "V2 AUTO SCAN started\n" +
            "Scanning every 4 seconds\n" +
            "No map movement\n" +
            "SAFE TEST: no troop sent"
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

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        updateStartStopButton()

        updateInfo(
            "STOPPED\n" +
            "Overlay remains active\n" +
            "SAFE TEST: no troop sent"
        )
    }

    // =============================================================
    // AUTO SCAN LOOP
    // =============================================================

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (!running) {
                    return
                }

                try {

                    scanScreen()

                } catch (e: Exception) {

                    updateInfo(
                        "Scan exception: " +
                            e.javaClass.simpleName
                    )
                }

                if (running) {

                    handler.postDelayed(
                        this,
                        4000
                    )
                }
            }
        }

    // =============================================================
    // SCREENSHOT
    // =============================================================

    private fun scanScreen() {

        if (
            Build.VERSION.SDK_INT <
                Build.VERSION_CODES.R
        ) {

            updateInfo(
                "Android version does not support\n" +
                "Accessibility screenshot API"
            )

            return
        }

        if (screenshotInProgress) {

            updateInfo(
                "Scan already running..."
            )

            return
        }

        screenshotInProgress = true

        scanCount++

        updateInfo(
            "Scan #$scanCount - capturing..."
        )

        try {

            takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {

                    override fun onSuccess(
                        screenshot: ScreenshotResult
                    ) {

                        processScreenshot(
                            screenshot
                        )
                    }

                    override fun onFailure(
                        errorCode: Int
                    ) {

                        screenshotInProgress = false

                        updateInfo(
                            "Scan #$scanCount\n" +
                            "Capture failed: $errorCode"
                        )
                    }
                }
            )

        } catch (e: Exception) {

            screenshotInProgress = false

            updateInfo(
                "Scan #$scanCount\n" +
                "Capture exception: " +
                e.javaClass.simpleName
            )
        }
    }

    // =============================================================
    // PROCESS SCREENSHOT
    // =============================================================

    private fun processScreenshot(
        screenshot: ScreenshotResult
    ) {

        var bitmap: Bitmap? = null

        try {

            val hardwareBitmap =
                Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer,
                    screenshot.colorSpace
                )

            if (hardwareBitmap == null) {

                updateInfo(
                    "Bitmap conversion failed"
                )

                return
            }

            bitmap =
                hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            if (bitmap == null) {

                updateInfo(
                    "Bitmap copy failed"
                )

                return
            }

            updateInfo(
                "Scan #$scanCount - detecting RSS..."
            )

            analyseScreen(
                bitmap
            )

        } catch (e: Exception) {

            updateInfo(
                "Scan #$scanCount\n" +
                "Analysis error: " +
                e.javaClass.simpleName
            )

        } finally {

            try {
                screenshot.hardwareBuffer.close()
            } catch (_: Exception) {
            }

            try {
                bitmap?.recycle()
            } catch (_: Exception) {
            }

            screenshotInProgress = false
        }
    }

    // =============================================================
    // MAIN RSS DETECTOR
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val candidates =
            detectBlueRssBadges(bitmap)

        if (candidates.isEmpty()) {

            updateInfo(
                "Scan #$scanCount\n" +
                "No RSS markers detected\n" +
                "Try another map position\n" +
                "SAFE TEST: no troop sent"
            )

            return
        }

        // ---------------------------------------------------------
        // Remove duplicates.
        // ---------------------------------------------------------

        val unique =
            removeDuplicateCandidates(
                candidates
            )

        // ---------------------------------------------------------
        // Sort using user's RSS priority.
        // ---------------------------------------------------------

        val sorted =
            unique.sortedWith(
                compareBy<RssCandidate> {

                    val index =
                        rssPriority.indexOf(
                            it.type
                        )

                    if (index < 0) {
                        999
                    } else {
                        index
                    }

                }.thenByDescending {
                    it.level
                }.thenBy {
                    it.confidence
                }
            )

        // ---------------------------------------------------------
        // Select first safe candidate.
        // ---------------------------------------------------------

        val selected =
            sorted.firstOrNull {
                !it.occupied
            }

        // ---------------------------------------------------------
        // Build compact display.
        // ---------------------------------------------------------

        val builder =
            StringBuilder()

        builder.append(
            "Scan #$scanCount\n"
        )

        builder.append(
            "RSS found: ${sorted.size}\n"
        )

        val displayCount =
            min(
                sorted.size,
                6
            )

        for (i in 0 until displayCount) {

            val r =
                sorted[i]

            builder.append(
                "${i + 1}. " +
                "${r.type} " +
                "Lv${r.level} " +
                "(${r.x},${r.y}) "
            )

            if (r.occupied) {
                builder.append(
                    "[OCCUPIED?]"
                )
            }

            builder.append("\n")
        }

        if (selected != null) {

            builder.append(
                "BEST: ${selected.type} " +
                "Lv${selected.level} " +
                "(${selected.x},${selected.y})\n"
            )

        } else {

            builder.append(
                "BEST: none - all candidates suspicious\n"
            )
        }

        builder.append(
            "SAFE TEST: no troop sent"
        )

        updateInfo(
            builder.toString()
        )
    }

    // =============================================================
    // BLUE RSS BADGE DETECTOR
    // =============================================================

    private fun detectBlueRssBadges(
        bitmap: Bitmap
    ): MutableList<RssCandidate> {

        val result =
            mutableListOf<RssCandidate>()

        val width =
            bitmap.width

        val height =
            bitmap.height

        // We deliberately downsample.
        // This keeps the phone responsive.
        val step = 3

        val gridWidth =
            (width + step - 1) / step

        val gridHeight =
            (height + step - 1) / step

        val visited =
            BooleanArray(
                gridWidth * gridHeight
            )

        val queueX =
            IntArray(
                gridWidth * gridHeight
            )

        val queueY =
            IntArray(
                gridWidth * gridHeight
            )

        for (gy in 20 until gridHeight - 10) {

            for (gx in 5 until gridWidth - 5) {

                val index =
                    gy * gridWidth + gx

                if (visited[index]) {
                    continue
                }

                val px =
                    min(
                        width - 1,
                        gx * step
                    )

                val py =
                    min(
                        height - 1,
                        gy * step
                    )

                if (!isRssBluePixel(
                        bitmap.getPixel(px, py)
                    )
                ) {

                    visited[index] = true
                    continue
                }

                // -------------------------------------------------
                // Flood fill.
                // -------------------------------------------------

                var head = 0
                var tail = 0

                queueX[tail] = gx
                queueY[tail] = gy
                tail++

                visited[index] = true

                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy

                var pixels = 0

                while (head < tail) {

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

                    val neighbours =
                        arrayOf(
                            intArrayOf(
                                cx + 1,
                                cy
                            ),
                            intArrayOf(
                                cx - 1,
                                cy
                            ),
                            intArrayOf(
                                cx,
                                cy + 1
                            ),
                            intArrayOf(
                                cx,
                                cy - 1
                            )
                        )

                    for (n in neighbours) {

                        val nx = n[0]
                        val ny = n[1]

                        if (
                            nx < 0 ||
                            ny < 0 ||
                            nx >= gridWidth ||
                            ny >= gridHeight
                        ) {
                            continue
                        }

                        val ni =
                            ny * gridWidth + nx

                        if (visited[ni]) {
                            continue
                        }

                        visited[ni] = true

                        val sx =
                            min(
                                width - 1,
                                nx * step
                            )

                        val sy =
                            min(
                                height - 1,
                                ny * step
                            )

                        if (
                            isRssBluePixel(
                                bitmap.getPixel(
                                    sx,
                                    sy
                                )
                            )
                        ) {

                            if (
                                tail <
                                queueX.size
                            ) {

                                queueX[tail] = nx
                                queueY[tail] = ny
                                tail++
                            }
                        }
                    }
                }

                // -------------------------------------------------
                // Component size.
                // -------------------------------------------------

                val componentWidth =
                    (maxX - minX + 1) * step

                val componentHeight =
                    (maxY - minY + 1) * step

                val componentArea =
                    componentWidth *
                    componentHeight

                // RSS level badges are small.
                //
                // This deliberately rejects:
                // - giant UI areas
                // - large blue buttons
                // - map-wide blue graphics
                if (
                    pixels < 12 ||
                    pixels > 800 ||
                    componentWidth < 8 ||
                    componentWidth > 70 ||
                    componentHeight < 8 ||
                    componentHeight > 60 ||
                    componentArea > 3500
                ) {
                    continue
                }

                val centerX =
                    ((minX + maxX) / 2) * step

                val centerY =
                    ((minY + maxY) / 2) * step

                // -------------------------------------------------
                // Analyse resource area around badge.
                // -------------------------------------------------

                val type =
                    classifyResource(
                        bitmap,
                        centerX,
                        centerY
                    )

                val level =
                    estimateLevel(
                        bitmap,
                        centerX,
                        centerY,
                        componentWidth,
                        componentHeight
                    )

                val confidence =
                    resourceConfidence(
                        bitmap,
                        centerX,
                        centerY,
                        type
                    )

                // Ignore very weak detections.
                if (confidence < 25) {
                    continue
                }

                val occupied =
                    looksOccupied(
                        bitmap,
                        centerX,
                        centerY
                    )

                result.add(
                    RssCandidate(
                        type = type,
                        level = level,
                        x = centerX,
                        y = centerY,
                        confidence = confidence,
                        occupied = occupied
                    )
                )
            }
        }

        return result
    }

    // =============================================================
    // BLUE PIXEL TEST
    // =============================================================

    private fun isRssBluePixel(
        pixel: Int
    ): Boolean {

        val r =
            Color.red(pixel)

        val g =
            Color.green(pixel)

        val b =
            Color.blue(pixel)

        return (
            b > 105 &&
            b > r * 1.20f &&
            b > g * 1.02f &&
            b - r > 25
        )
    }

    // =============================================================
    // RESOURCE CLASSIFICATION
    // =============================================================

    private fun classifyResource(
        bitmap: Bitmap,
        badgeX: Int,
        badgeY: Int
    ): String {

        val width =
            bitmap.width

        val height =
            bitmap.height

        /*
         * The blue level badge is normally near the lower-right
         * side of the RSS object.
         *
         * Therefore inspect a larger region above/left of it.
         */

        val left =
            max(
                0,
                badgeX - 110
            )

        val right =
            min(
                width - 1,
                badgeX + 25
            )

        val top =
            max(
                0,
                badgeY - 105
            )

        val bottom =
            min(
                height - 1,
                badgeY + 20
            )

        var yellow = 0
        var brown = 0
        var gray = 0
        var cyan = 0
        var orange = 0
        var green = 0
        var bright = 0

        var samples = 0

        var y = top

        while (y <= bottom) {

            var x = left

            while (x <= right) {

                val p =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(p)

                val g =
                    Color.green(p)

                val b =
                    Color.blue(p)

                // Ignore strong blue badge pixels.
                if (
                    !(b > 100 &&
                    b > r * 1.2f)
                ) {

                    samples++

                    // Yellow / wheat / gold.
                    if (
                        r > 150 &&
                        g > 115 &&
                        b < 100 &&
                        r > b * 1.5f
                    ) {
                        yellow++
                    }

                    // Brown / wooden material.
                    if (
                        r > 85 &&
                        g > 45 &&
                        g < 150 &&
                        b < 90 &&
                        r > g * 1.15f
                    ) {
                        brown++
                    }

                    // Stone.
                    if (
                        abs(r - g) < 30 &&
                        abs(g - b) < 30 &&
                        r in 90..220
                    ) {
                        gray++
                    }

                    // Ore / mineral cyan.
                    if (
                        b > 90 &&
                        g > 80 &&
                        b > r * 1.20f
                    ) {
                        cyan++
                    }

                    // Orange mineral / ore.
                    if (
                        r > 145 &&
                        g in 65..180 &&
                        b < 110 &&
                        r > g * 1.15f
                    ) {
                        orange++
                    }

                    // Green surroundings.
                    if (
                        g > 70 &&
                        g > r * 1.15f &&
                        g > b * 1.05f
                    ) {
                        green++
                    }

                    if (
                        r > 170 &&
                        g > 170 &&
                        b > 120
                    ) {
                        bright++
                    }
                }

                x += 4
            }

            y += 4
        }

        if (samples <= 0) {
            return "Other"
        }

        // ---------------------------------------------------------
        // Scores.
        // ---------------------------------------------------------

        val foodScore =
            yellow * 4 +
            bright * 2

        val goldScore =
            yellow * 3 +
            orange * 2 +
            bright * 2

        val woodScore =
            brown * 4 +
            green

        val stoneScore =
            gray * 5

        val oreScore =
            cyan * 4 +
            orange * 3 +
            gray

        val best =
            max(
                max(
                    max(
                        max(
                            foodScore,
                            goldScore
                        ),
                        woodScore
                    ),
                    stoneScore
                ),
                oreScore
            )

        if (best <= 0) {
            return "Other"
        }

        return when {

            oreScore == best &&
                oreScore > stoneScore * 1.15f ->
                "Ore"

            stoneScore == best ->
                "Stone"

            woodScore == best ->
                "Wood"

            goldScore == best &&
                goldScore > foodScore * 1.10f ->
                "Gold"

            foodScore == best ->
                "Food"

            else ->
                "Other"
        }
    }

    // =============================================================
    // CONFIDENCE
    // =============================================================

    private fun resourceConfidence(
        bitmap: Bitmap,
        badgeX: Int,
        badgeY: Int,
        type: String
    ): Int {

        val left =
            max(
                0,
                badgeX - 100
            )

        val right =
            min(
                bitmap.width - 1,
                badgeX + 20
            )

        val top =
            max(
                0,
                badgeY - 95
            )

        val bottom =
            min(
                bitmap.height - 1,
                badgeY + 10
            )

        var relevant = 0
        var total = 0

        var y = top

        while (y <= bottom) {

            var x = left

            while (x <= right) {

                val p =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(p)

                val g =
                    Color.green(p)

                val b =
                    Color.blue(p)

                if (
                    !(b > 100 &&
                    b > r * 1.2f)
                ) {

                    total++

                    val match =
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

                    if (match) {
                        relevant++
                    }
                }

                x += 5
            }

            y += 5
        }

        if (total == 0) {
            return 0
        }

        return min(
            100,
            (
                relevant * 100
            ) / total
        )
    }

    // =============================================================
    // LEVEL ESTIMATION
    // =============================================================

    private fun estimateLevel(
        bitmap: Bitmap,
        badgeX: Int,
        badgeY: Int,
        badgeWidth: Int,
        badgeHeight: Int
    ): Int {

        /*
         * V2 uses a conservative visual estimate.
         *
         * The exact digit recognition will be improved after
         * testing against your actual game screenshots.
         */

        val boxLeft =
            max(
                0,
                badgeX - badgeWidth / 2
            )

        val boxRight =
            min(
                bitmap.width - 1,
                badgeX + badgeWidth / 2
            )

        val boxTop =
            max(
                0,
                badgeY - badgeHeight / 2
            )

        val boxBottom =
            min(
                bitmap.height - 1,
                badgeY + badgeHeight / 2
            )

        var whitePixels = 0
        var bluePixels = 0

        var y = boxTop

        while (y <= boxBottom) {

            var x = boxLeft

            while (x <= boxRight) {

                val p =
                    bitmap.getPixel(
                        x,
                        y
                    )

                val r =
                    Color.red(p)

                val g =
                    Color.green(p)

                val b =
                    Color.blue(p)

                if (
                    r > 180 &&
                    g > 180 &&
                    b > 180
                ) {
                    whitePixels++
                }

                if (
                    b > 100 &&
                    b > r * 1.2f
                ) {
                    bluePixels++
                }

                x += 2
            }

            y += 2
        }

        /*
         * At this stage, if the digit cannot be reliably separated,
         * return 0 rather than inventing a level.
         */
        if (bluePixels < 5) {
            return 0
        }

        // Small white digit = usually 1.
        if (whitePixels in 2..12) {
            return 1
        }

        // Medium digit.
        if (whitePixels in 13..28) {
            return 2
        }

        // Larger / more complex digit.
        if (whitePixels > 28) {
            return 3
        }

        return 0
    }

    // =============================================================
    // OCCUPIED / MARCH WARNING
    // =============================================================

    private fun looksOccupied(
        bitmap: Bitmap,
        x: Int,
        y: Int
    ): Boolean {

        /*
         * IMPORTANT:
         *
         * We are deliberately conservative.
         *
         * This is NOT gathering logic.
         * It only marks a resource as suspicious when a strong
         * non-RSS coloured feature appears very close to it.
         *
         * Later we will build a dedicated march/path detector.
         */

        val left =
            max(
                0,
                x - 55
            )

        val right =
            min(
                bitmap.width - 1,
                x + 55
            )

        val top =
            max(
                0,
                y - 55
            )

        val bottom =
            min(
                bitmap.height - 1,
                y + 55
            )

        var suspicious = 0

        var yy = top

        while (yy <= bottom) {

            var xx = left

            while (xx <= right) {

                val p =
                    bitmap.getPixel(
                        xx,
                        yy
                    )

                val r =
                    Color.red(p)

                val g =
                    Color.green(p)

                val b =
                    Color.blue(p)

                val redSignal =
                    r > 175 &&
                    r > g * 1.35f &&
                    r > b * 1.35f

                val magentaSignal =
                    r > 130 &&
                    b > 100 &&
                    r > g * 1.25f

                val brightLine =
                    r > 205 &&
                    g > 205 &&
                    b > 205

                if (
                    redSignal ||
                    magentaSignal
                ) {
                    suspicious++
                }

                /*
                 * Do not treat ordinary white resource graphics
                 * as occupation by themselves.
                 */
                if (brightLine) {
                    suspicious += 0
                }

                xx += 4
            }

            yy += 4
        }

        /*
         * Conservative threshold.
         */
        return suspicious > 35
    }

    // =============================================================
    // REMOVE DUPLICATES
    // =============================================================

    private fun removeDuplicateCandidates(
        input: List<RssCandidate>
    ): List<RssCandidate> {

        val result =
            mutableListOf<RssCandidate>()

        for (candidate in input) {

            var duplicate = false

            for (existing in result) {

                val dx =
                    abs(
                        candidate.x -
                            existing.x
                    )

                val dy =
                    abs(
                        candidate.y -
                            existing.y
                    )

                if (
                    dx < 45 &&
                    dy < 45
                ) {

                    duplicate = true

                    /*
                     * Keep the stronger detection.
                     */
                    if (
                        candidate.confidence >
                        existing.confidence
                    ) {

                        result.remove(
                            existing
                        )

                        result.add(
                            candidate
                        )
                    }

                    break
                }
            }

            if (!duplicate) {
                result.add(candidate)
            }
        }

        return result
    }

    // =============================================================
    // UPDATE START BUTTON
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
    // UPDATE STATUS
    // =============================================================

    private fun updateInfo(
        message: String
    ) {

        handler.post {

            try {

                infoText?.text =
                    message

            } catch (_: Exception) {
            }
        }
    }

    // =============================================================
    // ACCESSIBILITY TAP
    // =============================================================
    // STILL NOT USED.
    //
    // V2 IS OBSERVATION ONLY.
    // =============================================================

    private fun tap(
        x: Float,
        y: Float
    ) {

        try {

            val path =
                Path()

            path.moveTo(
                x,
                y
            )

            val gesture =
                GestureDescription.Builder()
                    .addStroke(
                        GestureDescription.StrokeDescription(
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
    // SERVICE DESTROY
    // =============================================================

    override fun onDestroy() {

        running = false

        screenshotInProgress = false

        handler.removeCallbacks(
            scanRunnable
        )

        try {

            overlayView?.let {
                windowManager?.removeView(it)
            }

        } catch (_: Exception) {
        }

        overlayView = null
        overlayParams = null
        windowManager = null

        infoText = null
        startStopButton = null
        scanButton = null

        super.onDestroy()
    }
}
