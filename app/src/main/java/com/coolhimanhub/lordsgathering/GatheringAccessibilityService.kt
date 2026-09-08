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

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var overlayView: View? = null
    private var scanCount = 0

    private var windowManager: WindowManager? = null
    private var overlayParams: WindowManager.LayoutParams? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility events can be used later for UI detection.
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    private fun showFloatingControl() {

        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 8, 10, 8)
            setBackgroundColor(Color.rgb(65, 65, 65))
        }

        // ---------------------------------------------------------
        // TITLE / DRAG HANDLE
        // Drag this title to move the whole overlay.
        // ---------------------------------------------------------

        val title = TextView(this).apply {
            text = "Lords Assistant"
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(8, 4, 8, 8)

            setOnTouchListener(object : View.OnTouchListener {

                private var startX = 0f
                private var startY = 0f
                private var startParamX = 0
                private var startParamY = 0

                override fun onTouch(
                    view: View?,
                    event: MotionEvent
                ): Boolean {

                    val params = overlayParams
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

                            val dx = event.rawX - startX
                            val dy = event.rawY - startY

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
            })
        }

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
        // STATUS TEXT
        // ---------------------------------------------------------

        val info = TextView(this).apply {
            text = "Scanner ready"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(4, 5, 4, 2)
        }

        container.addView(title)
        container.addView(startStop)
        container.addView(scanButton)
        container.addView(info)

        // ---------------------------------------------------------
        // OVERLAY WINDOW
        // ---------------------------------------------------------

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START

        // Initial position
        params.x = 100
        params.y = 150

        windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

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

        if (running) return

        running = true

        updateInfo("Automation started")

        handler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false

        handler.removeCallbacks(scanRunnable)

        updateInfo("Automation stopped")
    }

    private val scanRunnable = object : Runnable {

        override fun run() {

            if (!running) return

            scanScreen()

            // Scan every 3 seconds
            handler.postDelayed(
                this,
                3000
            )
        }
    }

    // =============================================================
    // SCREEN SCANNER
    // =============================================================

    private fun scanScreen() {

        scanCount++

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {

            updateInfo(
                "Scan #$scanCount - screen capture unavailable"
            )

            return
        }

        updateInfo(
            "Scan #$scanCount - capturing..."
        )

        // IMPORTANT:
        // takeScreenshot() is a Java API.
        // Do NOT use named arguments here.
        // The second parameter must be an Executor.

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {

                override fun onSuccess(
                    screenshot: ScreenshotResult
                ) {

                    val bitmap: Bitmap? =
                        try {

                            Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )

                        } catch (_: Exception) {
                            null
                        }

                    try {
                        screenshot.hardwareBuffer.close()
                    } catch (_: Exception) {
                    }

                    if (bitmap == null) {

                        updateInfo(
                            "Scan #$scanCount - capture failed"
                        )

                        return
                    }

                    val width = bitmap.width
                    val height = bitmap.height

                    updateInfo(
                        "Scan #$scanCount - screen captured ${width}x$height"
                    )

                    // -------------------------------------------------
                    // NEXT STAGE:
                    // image analysis / RSS detection will go here.
                    // -------------------------------------------------

                    try {
                        bitmap.recycle()
                    } catch (_: Exception) {
                    }
                }

                override fun onFailure(
                    errorCode: Int
                ) {

                    updateInfo(
                        "Scan #$scanCount - capture failed ($errorCode)"
                    )
                }
            }
        )
    }

    // =============================================================
    // UPDATE OVERLAY STATUS
    // =============================================================

    private fun updateInfo(message: String) {

        val container =
            overlayView as? LinearLayout
                ?: return

        if (container.childCount < 4) {
            return
        }

        val info =
            container.getChildAt(3) as? TextView
                ?: return

        info.text = message
    }

    // =============================================================
    // ACCESSIBILITY TAP
    // =============================================================

    fun tap(
        x: Float,
        y: Float
    ) {

        val path = Path()

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
    // SERVICE DESTROY
    // =============================================================

    override fun onDestroy() {

        stopAutomation()

        overlayView?.let {

            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlayView = null
        overlayParams = null
        windowManager = null

        super.onDestroy()
    }
}
