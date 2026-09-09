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
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sqrt

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false

    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    private var scanCount = 0

    // =============================================================
    // TEST SETTINGS
    // =============================================================

    /*
     * IMPORTANT:
     * This version NEVER sends troops.
     *
     * It only detects possible gathering tiles and reports them.
     */

    private val sampleStep = 4

    /*
     * Ignore the top/bottom UI areas.
     */
    private val topIgnore = 70
    private val bottomIgnore = 90

    /*
     * Distance around a resource candidate that is inspected
     * for possible troop/march movement.
     */
    private val marchSearchRadius = 110

    /*
     * Minimum visual cluster size.
     */
    private val minimumClusterPixels = 18

    // =============================================================
    // ACCESSIBILITY SERVICE
    // =============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for future UI detection.
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

        val container = LinearLayout(this).apply {

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

        // ---------------------------------------------------------
        // TITLE / DRAG HANDLE
        // ---------------------------------------------------------

        val title = TextView(this).apply {

            text = "Lords Assistant"

            textSize = 16f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                8,
                4,
                8,
                8
            )

            setOnTouchListener(
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

                                    windowManager?.updateViewLayout(
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
        }

        // ---------------------------------------------------------
        // START / STOP
        // ---------------------------------------------------------

        val startStop = Button(this).apply {

            text = "▶ START"

            setOnClickListener {

                if (running) {

                    stopAutomation()

                    text = "▶ START"

                } else {

                    startAutomation()

                    text = "■ STOP"
                }
            }
        }

        // ---------------------------------------------------------
        // SCAN
        // ---------------------------------------------------------

        val scanButton = Button(this).apply {

            text = "🔍 SCAN"

            setOnClickListener {

                scanScreen()
            }
        }

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

        val info = TextView(this).apply {

            text = "Scanner ready"

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

        container.addView(title)
        container.addView(startStop)
        container.addView(scanButton)
        container.addView(info)

        // ---------------------------------------------------------
        // OVERLAY WINDOW
        // ---------------------------------------------------------

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

        windowManager =
            getSystemService(
                WINDOW_SERVICE
            ) as WindowManager

        overlayParams = params

        try {

            windowManager?.addView(
                container,
                params
            )

            overlayView = container

        } catch (_: Exception) {

            overlayView = null
        }
    }

    // =============================================================
    // AUTOMATION
    // =============================================================

    private fun startAutomation() {

        if (running) {
            return
        }

        running = true

        updateInfo(
            "AUTO TEST - detection only"
        )

        handler.post(
            scanRunnable
        )
    }

    private fun stopAutomation() {

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        updateInfo(
            "Stopped - no troops sent"
        )
    }

    private val scanRunnable =
        object : Runnable {

            override fun run() {

                if (!running) {
                    return
                }

                scanScreen()

                handler.postDelayed(
                    this,
                    3000
                )
            }
        }

    // =============================================================
    // SCREEN CAPTURE
    // =============================================================

    private fun scanScreen() {

        scanCount++

        if (
            Build.VERSION.SDK_INT <
            Build.VERSION_CODES.R
        ) {

            updateInfo(
                "Scan #$scanCount - Android version unsupported"
            )

            return
        }

        updateInfo(
            "Scan #$scanCount - capturing..."
        )

        takeScreenshot(

            Display.DEFAULT_DISPLAY,

            mainExecutor,

            object : TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: ScreenshotResult
                ) {

                    try {

                        val hardwareBitmap =
                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                        if (hardwareBitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - bitmap conversion failed"
                            )

                            screenshot.hardwareBuffer.close()

                            return
                        }

                        val bitmap =
                            hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        if (bitmap == null) {

                            updateInfo(
                                "Scan #$scanCount - bitmap copy failed"
                            )

                            screenshot.hardwareBuffer.close()

                            return
                        }

                        analyseScreen(bitmap)

                        screenshot.hardwareBuffer.close()

                        bitmap.recycle()

                    } catch (e: Exception) {

                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                        }

                        updateInfo(
                            "Scan #$scanCount - error: " +
                                e.javaClass.simpleName
                        )
                    }
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    updateInfo(
                        "Scan #$scanCount - capture failed: " +
                            errorCode
                    )
                }
            }
        )
    }

    // =============================================================
    // IMAGE ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val width = bitmap.width
        val height = bitmap.height

        /*
         * We first search for visually strong resource-like areas.
         *
         * We intentionally do NOT automatically tap them.
         */

        val candidates =
            detectResourceCandidates(
                bitmap
            )

        var safeCount = 0
        var rejectedCount = 0

        val safeCoordinates =
            ArrayList<String>()

        for (candidate in candidates) {

            val occupied =
                hasPossibleMarch(
                    bitmap,
                    candidate.x,
                    candidate.y
                )

            if (occupied) {

                rejectedCount++

            } else {

                safeCount++

                safeCoordinates.add(
                    "${candidate.x},${candidate.y}"
                )
            }
        }

        // ---------------------------------------------------------
        // STATUS
        // ---------------------------------------------------------

        val coordinateText =
            if (safeCoordinates.isEmpty()) {
                "none"
            } else {
                safeCoordinates
                    .take(5)
                    .joinToString(" ")
            }

        updateInfo(

            "Scan #$scanCount - ${width}x$height\n" +

                "Candidates: ${candidates.size}\n" +

                "SAFE: $safeCount   " +
                "REJECTED: $rejectedCount\n" +

                "Possible safe XY: $coordinateText\n" +

                "TEST ONLY - no troop sent"
        )
    }

    // =============================================================
    // RESOURCE CANDIDATE DETECTION
    // =============================================================

    private data class Candidate(
        val x: Int,
        val y: Int
    )

    private fun detectResourceCandidates(
        bitmap: Bitmap
    ): List<Candidate> {

        val width = bitmap.width
        val height = bitmap.height

        /*
         * Small grid of sampled pixels.
         *
         * Resource tiles tend to contain strong local colour
         * differences compared with the surrounding terrain.
         */

        val points =
            ArrayList<Pair<Int, Int>>()

        var y = topIgnore

        while (
            y < height - bottomIgnore
        ) {

            var x = 20

            while (
                x < width - 20
            ) {

                val pixel =
                    bitmap.getPixel(
                        x,
                        y
                    )

                if (looksLikeResourceVisual(pixel)) {

                    /*
                     * Do not consider pixels inside the assistant
                     * floating window.
                     */
                    if (!insideOverlay(x, y)) {

                        points.add(
                            Pair(x, y)
                        )
                    }
                }

                x += sampleStep
            }

            y += sampleStep
        }

        /*
         * Group nearby pixels.
         */
        return clusterPoints(
            points
        )
    }

    // =============================================================
    // RESOURCE VISUAL FILTER
    // =============================================================

    private fun looksLikeResourceVisual(
        pixel: Int
    ): Boolean {

        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        val max =
            maxOf(
                r,
                g,
                b
            )

        val min =
            minOf(
                r,
                g,
                b
            )

        val saturation =
            max - min

        /*
         * Wheat / food.
         */
        val wheat =
            r > 130 &&
            g > 100 &&
            b < 120 &&
            r > b + 35

        /*
         * Wood / vegetation.
         */
        val wood =
            g > 75 &&
            g > r + 15 &&
            g > b + 5

        /*
         * Ore / stone.
         */
        val stone =
            r > 80 &&
            g > 80 &&
            b > 90 &&
            saturation < 75

        /*
         * Gold / yellow resource.
         */
        val gold =
            r > 140 &&
            g > 110 &&
            b < 120 &&
            r > b + 45

        /*
         * Strong coloured resource object.
         */
        val strongColour =
            saturation > 85 &&
            max > 125

        return (
            wheat ||
            wood ||
            stone ||
            gold ||
            strongColour
        )
    }

    // =============================================================
    // CLUSTER DETECTION
    // =============================================================

    private fun clusterPoints(
        points: List<Pair<Int, Int>>
    ): List<Candidate> {

        if (points.isEmpty()) {
            return emptyList()
        }

        val result =
            ArrayList<Candidate>()

        val used =
            BooleanArray(
                points.size
            )

        /*
         * Maximum grouping distance.
         */
        val clusterDistance = 65

        for (i in points.indices) {

            if (used[i]) {
                continue
            }

            val queue =
                ArrayDeque<Int>()

            queue.add(i)

            used[i] = true

            var sumX = 0L
            var sumY = 0L

            var count = 0

            while (queue.isNotEmpty()) {

                val index =
                    queue.removeFirst()

                val point =
                    points[index]

                sumX += point.first
                sumY += point.second

                count++

                for (j in points.indices) {

                    if (used[j]) {
                        continue
                    }

                    val other =
                        points[j]

                    val dx =
                        other.first - point.first

                    val dy =
                        other.second - point.second

                    val distance =
                        sqrt(
                            (
                                dx * dx +
                                dy * dy
                            ).toDouble()
                        )

                    if (
                        distance <=
                        clusterDistance
                    ) {

                        used[j] = true

                        queue.add(j)
                    }
                }
            }

            if (
                count >=
                minimumClusterPixels
            ) {

                val centerX =
                    (
                        sumX / count
                    ).toInt()

                val centerY =
                    (
                        sumY / count
                    ).toInt()

                /*
                 * Avoid duplicate candidates.
                 */
                var duplicate = false

                for (existing in result) {

                    val dx =
                        existing.x - centerX

                    val dy =
                        existing.y - centerY

                    if (
                        dx * dx +
                        dy * dy <
                        80 * 80
                    ) {

                        duplicate = true

                        break
                    }
                }

                if (!duplicate) {

                    result.add(
                        Candidate(
                            centerX,
                            centerY
                        )
                    )
                }
            }
        }

        return result
    }

    // =============================================================
    // MARCH / TROOP ROUTE DETECTION
    // =============================================================

    private fun hasPossibleMarch(
        bitmap: Bitmap,
        centerX: Int,
        centerY: Int
    ): Boolean {

        val width =
            bitmap.width

        val height =
            bitmap.height

        /*
         * Examine a ring around the candidate.
         *
         * We intentionally do not say:
         *
         * "orange = occupied"
         *
         * because you specifically told me that the march line
         * can be different colours/types.
         */

        val radius =
            marchSearchRadius

        val ringInner =
            35

        var edgeCount = 0

        var longDirectionalSegments = 0

        var previousStrong = false

        var consecutive = 0

        var angle = 0

        while (angle < 360) {

            val radians =
                Math.toRadians(
                    angle.toDouble()
                )

            val x =
                centerX +
                    (
                        kotlin.math.cos(radians) *
                        radius
                    ).toInt()

            val y =
                centerY +
                    (
                        kotlin.math.sin(radians) *
                        radius
                    ).toInt()

            if (
                x >= 2 &&
                x < width - 2 &&
                y >= 2 &&
                y < height - 2
            ) {

                val strong =
                    localEdgeStrength(
                        bitmap,
                        x,
                        y
                    ) > 55

                if (strong) {

                    edgeCount++

                    consecutive++

                    if (
                        consecutive >=
                        5
                    ) {

                        longDirectionalSegments++

                        consecutive = 0
                    }

                } else {

                    consecutive = 0
                }

                previousStrong = strong
            }

            angle += 2
        }

        /*
         * Additional direct-line analysis.
         *
         * Check several directions crossing the candidate.
         */

        var directionalHits = 0

        val directions =
            arrayOf(
                Pair(1, 0),
                Pair(-1, 0),
                Pair(0, 1),
                Pair(0, -1),
                Pair(1, 1),
                Pair(-1, -1),
                Pair(1, -1),
                Pair(-1, 1)
            )

        for (direction in directions) {

            val hits =
                countLineStructure(
                    bitmap,
                    centerX,
                    centerY,
                    direction.first,
                    direction.second,
                    ringInner,
                    radius
                )

            if (hits >= 8) {

                directionalHits++
            }
        }

        /*
         * Conservative safety rule:
         *
         * If there is enough directional structure around the
         * resource, reject it.
         *
         * This is intentionally biased toward SAFE behaviour:
         * false rejection is preferable to sending troops to an
         * occupied tile.
         */

        return (
            longDirectionalSegments >= 3 ||
            directionalHits >= 2 ||
            edgeCount >= 70
        )
    }

    // =============================================================
    // LINE STRUCTURE
    // =============================================================

    private fun countLineStructure(
        bitmap: Bitmap,
        startX: Int,
        startY: Int,
        dx: Int,
        dy: Int,
        minDistance: Int,
        maxDistance: Int
    ): Int {

        var hits = 0

        var distance =
            minDistance

        while (
            distance <= maxDistance
        ) {

            val x =
                startX +
                    dx * distance

            val y =
                startY +
                    dy * distance

            if (
                x < 2 ||
                y < 2 ||
                x >= bitmap.width - 2 ||
                y >= bitmap.height - 2
            ) {

                break
            }

            val strength =
                localEdgeStrength(
                    bitmap,
                    x,
                    y
                )

            if (strength > 55) {

                hits++
            }

            distance += 5
        }

        return hits
    }

    // =============================================================
    // EDGE / STRUCTURE ANALYSIS
    // =============================================================

    private fun localEdgeStrength(
        bitmap: Bitmap,
        x: Int,
        y: Int
    ): Int {

        val center =
            brightness(
                bitmap.getPixel(
                    x,
                    y
                )
            )

        val right =
            brightness(
                bitmap.getPixel(
                    x + 1,
                    y
                )
            )

        val left =
            brightness(
                bitmap.getPixel(
                    x - 1,
                    y
                )
            )

        val up =
            brightness(
                bitmap.getPixel(
                    x,
                    y - 1
                )
            )

        val down =
            brightness(
                bitmap.getPixel(
                    x,
                    y + 1
                )
            )

        val horizontal =
            abs(
                right - left
            )

        val vertical =
            abs(
                down - up
            )

        val centerDifference =
            (
                abs(center - right) +
                abs(center - left) +
                abs(center - up) +
                abs(center - down)
            ) / 4

        return maxOf(
            horizontal,
            vertical,
            centerDifference
        )
    }

    private fun brightness(
        pixel: Int
    ): Int {

        val r =
            Color.red(pixel)

        val g =
            Color.green(pixel)

        val b =
            Color.blue(pixel)

        return (
            299 * r +
            587 * g +
            114 * b
        ) / 1000
    }

    // =============================================================
    // IGNORE ASSISTANT WINDOW
    // =============================================================

    private fun insideOverlay(
        x: Int,
        y: Int
    ): Boolean {

        val params =
            overlayParams
                ?: return false

        val view =
            overlayView
                ?: return false

        val width =
            view.width

        val height =
            view.height

        if (
            width <= 0 ||
            height <= 0
        ) {

            return false
        }

        val left =
            params.x

        val top =
            params.y

        val right =
            left + width

        val bottom =
            top + height

        return (
            x >= left &&
            x <= right &&
            y >= top &&
            y <= bottom
        )
    }

    // =============================================================
    // UPDATE STATUS
    // =============================================================

    private fun updateInfo(
        message: String
    ) {

        val container =
            overlayView
                ?: return

        if (
            container.childCount < 4
        ) {

            return
        }

        val info =
            container.getChildAt(3)
                as? TextView
                ?: return

        info.text =
            message
    }

    // =============================================================
    // ACCESSIBILITY TAP
    // =============================================================

    private fun tap(
        x: Float,
        y: Float
    ) {

        /*
         * Kept for the future gathering stage.
         *
         * THIS VERSION NEVER CALLS tap().
         */

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
    }

    // =============================================================
    // DESTROY
    // =============================================================

    override fun onDestroy() {

        stopAutomation()

        overlayView?.let {

            try {

                windowManager?.removeView(
                    it
                )

            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
        windowManager = null

        super.onDestroy()
    }
}
