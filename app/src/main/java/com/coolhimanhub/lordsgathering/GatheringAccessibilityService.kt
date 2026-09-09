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
    // ACCESSIBILITY SERVICE
    // =============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()

        try {
            windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            showFloatingControl()

        } catch (e: Exception) {

            // Do not allow an exception here to kill the service.
            handler.post {
                recreateOverlay()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Nothing required here yet.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    // =============================================================
    // FLOATING CONTROL
    // =============================================================

    private fun showFloatingControl() {

        // Already visible.
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

        // ---------------------------------------------------------
        // MAIN CONTAINER
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // TITLE / DRAG HANDLE
        // ---------------------------------------------------------

        val title =
            TextView(this).apply {

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
            }

        // Drag functionality.
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

        // ---------------------------------------------------------
        // START / STOP BUTTON
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // SCAN BUTTON
        // ---------------------------------------------------------

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

        // ---------------------------------------------------------
        // STATUS TEXT
        // ---------------------------------------------------------

        val info =
            TextView(this).apply {

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

        // Save references.
        infoText = info
        startStopButton = startButton
        scanButton = scanBtn

        // Add views.
        container.addView(title)
        container.addView(startButton)
        container.addView(scanBtn)
        container.addView(info)

        // ---------------------------------------------------------
        // OVERLAY PARAMETERS
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

        overlayParams = params

        // ---------------------------------------------------------
        // ADD OVERLAY
        // ---------------------------------------------------------

        try {

            wm.addView(
                container,
                params
            )

            overlayView = container

            updateInfo(
                "Scanner ready\nSAFE TEST: no troop sent"
            )

        } catch (e: Exception) {

            overlayView = null
            overlayParams = null

            infoText = null
            startStopButton = null
            scanButton = null

            // Don't crash the accessibility service.
            handler.postDelayed(
                {
                    recreateOverlay()
                },
                1000
            )
        }
    }

    // =============================================================
    // RECREATE OVERLAY
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
    // START AUTOMATION
    // =============================================================

    private fun startAutomation() {

        if (running) {
            return
        }

        running = true

        updateStartStopButton()

        updateInfo(
            "AUTO TEST started\n" +
                "Scanning every 3 seconds\n" +
                "SAFE TEST: no troop sent"
        )

        // IMPORTANT:
        // Do not immediately call screenshot here.
        // Give Android a moment after pressing START.

        handler.removeCallbacks(scanRunnable)

        handler.postDelayed(
            scanRunnable,
            500
        )
    }

    // =============================================================
    // STOP AUTOMATION
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
    // AUTOMATIC SCAN LOOP
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
                        3000
                    )
                }
            }
        }

    // =============================================================
    // SCREEN SCANNER
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

        // Prevent two screenshots at once.
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
                                "Capture failed: $errorCode\n" +
                                "SAFE TEST: no troop sent"
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

            // -----------------------------------------------------
            // Get hardware bitmap.
            // -----------------------------------------------------

            val hardwareBitmap =
                Bitmap.wrapHardwareBuffer(
                    screenshot.hardwareBuffer,
                    screenshot.colorSpace
                )

            if (hardwareBitmap == null) {

                updateInfo(
                    "Scan #$scanCount\n" +
                        "Bitmap conversion failed"
                )

                return
            }

            // -----------------------------------------------------
            // Convert hardware bitmap to ARGB_8888.
            // -----------------------------------------------------

            bitmap =
                hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            if (bitmap == null) {

                updateInfo(
                    "Scan #$scanCount\n" +
                        "Bitmap copy failed"
                )

                return
            }

            // -----------------------------------------------------
            // Analyse bitmap.
            // -----------------------------------------------------

            updateInfo(
                "Scan #$scanCount - analysing..."
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

            // -----------------------------------------------------
            // ALWAYS release screenshot resources.
            // -----------------------------------------------------

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
    // IMAGE ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val width =
            bitmap.width

        val height =
            bitmap.height

        var bluePixels = 0
        var orangePixels = 0
        var greenPixels = 0

        // Sample every 4 pixels.
        val step = 4

        var y = 80

        while (
            y < height - 30
        ) {

            var x = 10

            while (
                x < width - 10
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

                // -------------------------------------------------
                // BLUE
                // -------------------------------------------------

                if (
                    b > 110 &&
                    b > r * 1.25f &&
                    b > g * 1.05f
                ) {

                    bluePixels++
                }

                // -------------------------------------------------
                // ORANGE
                // -------------------------------------------------

                if (
                    r > 150 &&
                    g > 70 &&
                    g < 190 &&
                    b < 100 &&
                    r > g * 1.15f
                ) {

                    orangePixels++
                }

                // -------------------------------------------------
                // GREEN
                // -------------------------------------------------

                if (
                    g > 70 &&
                    g > r * 1.20f &&
                    g > b * 1.10f
                ) {

                    greenPixels++
                }

                x += step
            }

            y += step
        }

        // ---------------------------------------------------------
        // RESULT
        // ---------------------------------------------------------

        updateInfo(

            "Scan #$scanCount - " +
                "${width}x$height\n" +

                "Visual signals: " +

                "blue=$bluePixels " +

                "orange=$orangePixels " +

                "green=$greenPixels\n" +

                "SAFE TEST: no troop sent"
        )
    }

    // =============================================================
    // UPDATE START / STOP BUTTON
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
    // Kept available for the later gathering engine.
    // CURRENT VERSION NEVER CALLS IT.
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
