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
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.view.accessibility.AccessibilityEvent

class GatheringAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private var running = false
    private var overlayView: View? = null

    private var scanCount = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        showFloatingControl()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
        stopAutomation()
    }

    private fun showFloatingControl() {

        if (overlayView != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(Color.DKGRAY)
        }

        val title = TextView(this).apply {
            text = "Lords Assistant"
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

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

        val scanButton = Button(this).apply {
            text = "🔍 SCAN"

            setOnClickListener {
                scanScreen()
            }
        }

        val info = TextView(this).apply {
            text = "Scanner ready"
            textSize = 11f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        container.addView(title)
        container.addView(startStop)
        container.addView(scanButton)
        container.addView(info)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 150

        val windowManager =
            getSystemService(WINDOW_SERVICE) as WindowManager

        windowManager.addView(container, params)

        overlayView = container
    }

    private fun startAutomation() {

        if (running) return

        running = true
        handler.post(scanRunnable)
    }

    private fun stopAutomation() {

        running = false
        handler.removeCallbacks(scanRunnable)
    }

    private val scanRunnable = object : Runnable {

        override fun run() {

            if (!running) return

            scanScreen()

            handler.postDelayed(this, 3000)
        }
    }

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

        takeScreenshot(
            android.view.Display.DEFAULT_DISPLAY,
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

                        screenshot.hardwareBuffer.close()

                        if (hardwareBitmap == null) {
                            updateInfo(
                                "Scan #$scanCount - bitmap failed"
                            )
                            return
                        }

                        val bitmap =
                            hardwareBitmap.copy(
                                Bitmap.Config.ARGB_8888,
                                false
                            )

                        hardwareBitmap.recycle()

                        if (bitmap == null) {
                            updateInfo(
                                "Scan #$scanCount - bitmap conversion failed"
                            )
                            return
                        }

                        val width = bitmap.width
                        val height = bitmap.height

                        val candidates =
                            detectRssCandidates(bitmap)

                        updateInfo(
                            "Scan #$scanCount - $candidates candidates found"
                        )

                        bitmap.recycle()

                    } catch (e: Exception) {

                        try {
                            screenshot.hardwareBuffer.close()
                        } catch (_: Exception) {
                        }

                        updateInfo(
                            "Scan #$scanCount - analysis error"
                        )
                    }
                }

                override fun onFailure(errorCode: Int) {

                    updateInfo(
                        "Scan #$scanCount - capture failed ($errorCode)"
                    )
                }
            }
        )
    }

    /*
     * FIRST RSS VISUAL DETECTOR
     *
     * This stage does NOT tap anything.
     *
     * It looks for compact areas containing sufficiently
     * saturated/bright pixels. Lords Mobile resource tiles
     * generally contain visually distinct coloured objects.
     *
     * This is deliberately conservative.
     */
    private fun detectRssCandidates(bitmap: Bitmap): Int {

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        /*
         * Reduce the image so analysis is fast enough to run
         * repeatedly on the phone.
         */
        val targetWidth = 320

        val scale =
            targetWidth.toFloat() / originalWidth.toFloat()

        val targetHeight =
            (originalHeight * scale).toInt()

        val smallBitmap =
            Bitmap.createScaledBitmap(
                bitmap,
                targetWidth,
                targetHeight,
                true
            )

        val pixels =
            IntArray(targetWidth * targetHeight)

        smallBitmap.getPixels(
            pixels,
            0,
            targetWidth,
            0,
            0,
            targetWidth,
            targetHeight
        )

        smallBitmap.recycle()

        /*
         * Divide the screen into small blocks.
         *
         * We count blocks containing enough colourful pixels.
         */
        val blockSize = 8

        var candidateBlocks = 0

        var y = 0

        while (y < targetHeight) {

            var x = 0

            while (x < targetWidth) {

                var colourfulPixels = 0
                var totalPixels = 0

                val maxX =
                    minOf(x + blockSize, targetWidth)

                val maxY =
                    minOf(y + blockSize, targetHeight)

                var py = y

                while (py < maxY) {

                    var px = x

                    while (px < maxX) {

                        val color =
                            pixels[
                                py * targetWidth + px
                            ]

                        val red =
                            Color.red(color)

                        val green =
                            Color.green(color)

                        val blue =
                            Color.blue(color)

                        val maxValue =
                            maxOf(red, green, blue)

                        val minValue =
                            minOf(red, green, blue)

                        val saturation =
                            maxValue - minValue

                        /*
                         * Ignore very dark pixels.
                         *
                         * Look for pixels with visible colour.
                         */
                        if (
                            maxValue > 90 &&
                            saturation > 45
                        ) {
                            colourfulPixels++
                        }

                        totalPixels++

                        px++
                    }

                    py++
                }

                /*
                 * A block becomes a candidate when a reasonable
                 * portion of it contains colourful pixels.
                 */
                if (
                    totalPixels > 0 &&
                    colourfulPixels.toFloat() /
                    totalPixels.toFloat() > 0.35f
                ) {
                    candidateBlocks++
                }

                x += blockSize
            }

            y += blockSize
        }

        /*
         * Several neighbouring blocks may belong to the same
         * object. For this first stage we report the block count.
         *
         * Later we will merge neighbouring blocks into actual
         * RSS tile objects.
         */
        return candidateBlocks
    }

    private fun updateInfo(message: String) {

        val container =
            overlayView as? LinearLayout
                ?: return

        if (container.childCount < 4) return

        val info =
            container.getChildAt(3) as? TextView
                ?: return

        info.text = message
    }

    fun tap(x: Float, y: Float) {

        val path = Path()

        path.moveTo(x, y)

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

    override fun onDestroy() {

        stopAutomation()

        overlayView?.let {

            val windowManager =
                getSystemService(WINDOW_SERVICE) as WindowManager

            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
            }
        }

        overlayView = null

        super.onDestroy()
    }
}
