package com.coolhimanhub.lordsgathering

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Color
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
import java.util.concurrent.atomic.AtomicBoolean

class GatheringAccessibilityService : AccessibilityService() {

    // =============================================================
    // BASIC STATE
    // =============================================================

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var scanCount = 0

    private var overlayView: LinearLayout? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var windowManager: WindowManager? = null

    // Prevents two screenshots from being processed at once.
    private val scanInProgress = AtomicBoolean(false)

    // =============================================================
    // ACCESSIBILITY SERVICE
    // =============================================================

    override fun onServiceConnected() {
        super.onServiceConnected()

        handler.post {
            showFloatingControl()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Reserved for future game-screen detection.
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

            textSize = 18f

            setTextColor(Color.WHITE)

            gravity = Gravity.CENTER

            setPadding(
                8,
                6,
                8,
                10
            )
        }

        // Make title draggable.
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
                        overlayParams ?: return false

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
                                (startParamX + dx).toInt()

                            params.y =
                                (startParamY + dy).toInt()

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

        // ---------------------------------------------------------
        // START / STOP BUTTON
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
        // SCAN BUTTON
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
                5,
                6,
                5,
                4
            )

            maxLines = 6
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

        } catch (e: Exception) {

            overlayView = null

            // Do not crash the Accessibility Service.
            handler.postDelayed(
                {
                    showFloatingControl()
                },
                2000
            )
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
            "AUTO TEST\nScanning only\nNo troop sent"
        )

        handler.removeCallbacks(scanRunnable)

        handler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        updateInfo(
            "Stopped\nNo troop sent"
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
    // SCREENSHOT
    // =============================================================

    private fun scanScreen() {

        if (!running && scanCount > 0) {
            // Manual SCAN is still allowed.
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

            updateInfo(
                "Android 11+ required"
            )

            return
        }

        // Don't start another screenshot while one is processing.
        if (!scanInProgress.compareAndSet(false, true)) {

            updateInfo(
                "Scan busy - waiting..."
            )

            return
        }

        scanCount++

        updateInfo(
            "Scan #$scanCount\nCapturing..."
        )

        try {

            takeScreenshot(

                Display.DEFAULT_DISPLAY,

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

                        scanInProgress.set(false)

                        updateInfo(
                            "Scan #$scanCount\n" +
                                "Capture failed: $errorCode"
                        )
                    }
                }
            )

        } catch (e: Exception) {

            scanInProgress.set(false)

            updateInfo(
                "Scan #$scanCount\n" +
                    "Capture error: " +
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

        var softwareBitmap: Bitmap? = null

        try {

            // -----------------------------------------------------
            // Hardware bitmap
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
            // Convert to software bitmap.
            // This is important because getPixel() cannot be
            // reliably used on HARDWARE bitmaps.
            // -----------------------------------------------------

            softwareBitmap =
                hardwareBitmap.copy(
                    Bitmap.Config.ARGB_8888,
                    false
                )

            if (softwareBitmap == null) {

                updateInfo(
                    "Scan #$scanCount\n" +
                        "Bitmap copy failed"
                )

                return
            }

            // -----------------------------------------------------
            // Analyse
            // -----------------------------------------------------

            analyseScreen(
                softwareBitmap
            )

        } catch (e: Exception) {

            updateInfo(
                "Scan #$scanCount\n" +
                    "Processing error: " +
                    e.javaClass.simpleName
            )

        } finally {

            // -----------------------------------------------------
            // Always close screenshot buffer.
            // -----------------------------------------------------

            try {
                screenshot.hardwareBuffer.close()
            } catch (_: Exception) {
            }

            // -----------------------------------------------------
            // Recycle software bitmap.
            // -----------------------------------------------------

            try {
                softwareBitmap?.recycle()
            } catch (_: Exception) {
            }

            scanInProgress.set(false)
        }
    }

    // =============================================================
    // LIGHTWEIGHT IMAGE ANALYSIS
    // =============================================================

    private fun analyseScreen(
        bitmap: Bitmap
    ) {

        val width =
            bitmap.width

        val height =
            bitmap.height

        var blue = 0
        var orange = 0
        var green = 0

        /*
         * IMPORTANT:
         *
         * We intentionally sample sparsely.
         *
         * Previous versions created huge collections of points
         * and performed expensive clustering.
         *
         * This version simply counts visual signals.
         */

        val step = 12

        val top = 80
        val bottom = 40

        var y = top

        while (y < height - bottom) {

            var x = 10

            while (x < width - 10) {

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
                    b > 115 &&
                    b > r * 1.20f &&
                    b > g * 1.03f
                ) {

                    blue++
                }

                // -------------------------------------------------
                // ORANGE / YELLOW
                // -------------------------------------------------

                if (
                    r > 145 &&
                    g > 65 &&
                    g < 205 &&
                    b < 115 &&
                    r > g * 1.10f
                ) {

                    orange++
                }

                // -------------------------------------------------
                // GREEN
                // -------------------------------------------------

                if (
                    g > 75 &&
                    g > r * 1.15f &&
                    g > b * 1.08f
                ) {

                    green++
                }

                x += step
            }

            y += step
        }

        // ---------------------------------------------------------
        // Display results
        // ---------------------------------------------------------

        updateInfo(

            "Scan #$scanCount - ${width}x$height\n" +

                "Visual signals\n" +

                "Blue: $blue\n" +

                "Orange: $orange\n" +

                "Green: $green\n" +

                "SAFE TEST: no troop sent"
        )
    }

    // =============================================================
    // UPDATE OVERLAY
    // =============================================================

    private fun updateInfo(
        message: String
    ) {

        handler.post {

            val container =
                overlayView ?: return@post

            if (container.childCount < 4) {
                return@post
            }

            val info =
                container.getChildAt(3)
                    as? TextView
                    ?: return@post

            info.text = message
        }
    }

    // =============================================================
    // SERVICE DESTROY
    // =============================================================

    override fun onDestroy() {

        running = false

        handler.removeCallbacks(
            scanRunnable
        )

        scanInProgress.set(false)

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
