package com.coolhiman.lordsassistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.coolhiman.lordsassistant.capture.ScreenCaptureService
import com.coolhiman.lordsassistant.target.RankedTarget

class OverlayService : Service() {
    companion object {
        @Volatile var instance: OverlayService? = null
        @Volatile private var selectedTestDurationMinutes: Int = 5
        @Volatile private var testRequested: Boolean = false

        fun consumeRequestedTestDuration(): Int? {
            if (!testRequested) return null
            testRequested = false
            return selectedTestDurationMinutes
        }
    }

    private lateinit var wm: WindowManager
    private var card: LinearLayout? = null
    private var statusView: TextView? = null
    private var healthView: TextView? = null
    private var timerView: TextView? = null
    private var markerView: TargetMarkerView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val density = resources.displayMetrics.density
        val cardWidth = (360f * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(0xDD16181D.toInt())
        }

        val status = TextView(this).apply {
            text = "LM • SCANNER\nReady — open the game map"
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 4)
            maxLines = 8
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        statusView = status

        val health = TextView(this).apply {
            text = "CAPTURE HEALTH\nWaiting for scanner…"
            textSize = 10f
            setTextColor(0xFFB8BBC4.toInt())
            setPadding(4, 2, 4, 5)
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(cardWidth, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        healthView = health

        val timerLabel = TextView(this).apply {
            text = "TEST TIMER • select 1–10 min"
            textSize = 10f
            setTextColor(0xFFB8BBC4.toInt())
            setPadding(4, 2, 4, 3)
        }

        val timerStatus = TextView(this).apply {
            text = timerStatusText()
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(4, 1, 4, 5)
        }
        timerView = timerStatus

        fun timerButton(minutes: Int): TextView = TextView(this).apply {
            text = minutes.toString()
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f
                setColor(0xFF30343B.toInt())
                setStroke(2, 0xFF707782.toInt())
            }
            isClickable = true
            isFocusable = false
            setOnClickListener { selectTestDuration(minutes) }
        }

        fun timerRow(start: Int, end: Int): LinearLayout =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (minute in start..end) {
                    addView(
                        timerButton(minute),
                        LinearLayout.LayoutParams(0, 38, 1f).apply {
                            setMargins(2, 1, 2, 1)
                        }
                    )
                }
            }

        // Keep the test controls at the top and keep the diagnostic status
        // bounded. The floating window must never expand into a full-screen
        // touch-blocking surface when a long diagnostic string is displayed.
        container.addView(timerLabel)
        container.addView(timerStatus)
        container.addView(timerRow(1, 5))
        container.addView(timerRow(6, 10))

        fun actionButton(label: String, fill: Int): TextView = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f
                setColor(fill)
                setStroke(2, Color.WHITE)
            }
            isClickable = true
            isFocusable = false
        }

        val startTestButton = actionButton("▶  START TEST", 0xFF176B3A.toInt()).apply {
            setOnClickListener { startTest() }
        }
        val stopTestButton = actionButton("■  STOP TEST", 0xFF7A2424.toInt()).apply {
            setOnClickListener { stopTest() }
        }
        val testActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(startTestButton, LinearLayout.LayoutParams(0, 44, 1f).apply {
                setMargins(2, 2, 2, 2)
            })
            addView(stopTestButton, LinearLayout.LayoutParams(0, 44, 1f).apply {
                setMargins(2, 2, 2, 2)
            })
        }
        container.addView(testActions)
        container.addView(status)
        container.addView(health)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        // Drag only the status/header area so the timer buttons remain tappable.
        status.setOnTouchListener { _, event ->
            val lp = container.layoutParams as? WindowManager.LayoutParams
                ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(container, lp) }
                    true
                }
                else -> false
            }
        }

        val type = if (android.os.Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            cardWidth,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 80
        }

        card = container
        wm.addView(container, lp)

        markerView = TargetMarkerView().also { marker ->
            val markerLp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            )
            wm.addView(marker, markerLp)
        }
    }

    private fun selectTestDuration(minutes: Int) {
        selectedTestDurationMinutes = minutes.coerceIn(1, 10)
        val active = ScreenCaptureService.instance?.isCaptureSessionActive() == true
        if (active) {
            ScreenCaptureService.instance?.configureTestTimer(selectedTestDurationMinutes)
            timerView?.post {
                timerView?.text = "TEST: ${selectedTestDurationMinutes} min • RUNNING"
            }
            showStatus(
                "LM • TEST TIMER\n${selectedTestDurationMinutes} min selected • capture running"
            )
        } else {
            timerView?.post {
                timerView?.text = "TEST: ${selectedTestDurationMinutes} min • READY"
            }
            showStatus(
                "LM • TEST TIMER\n${selectedTestDurationMinutes} min selected • press START TEST"
            )
        }
    }

    private fun startTest() {
        val active = ScreenCaptureService.instance?.isCaptureSessionActive() == true
        if (active) {
            testRequested = false
            ScreenCaptureService.instance?.configureTestTimer(selectedTestDurationMinutes)
            timerView?.post {
                timerView?.text = "TEST: ${selectedTestDurationMinutes} min • RUNNING"
            }
            showStatus("LM • TEST TIMER\n${selectedTestDurationMinutes} min test started")
            return
        }

        testRequested = true
        timerView?.post {
            timerView?.text = "TEST: ${selectedTestDurationMinutes} min • WAITING FOR CAPTURE"
        }
        val intent = Intent(this, com.coolhiman.lordsassistant.MainActivity::class.java).apply {
            action = com.coolhiman.lordsassistant.MainActivity.ACTION_START_CAPTURE
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching { startActivity(intent) }.onFailure {
            testRequested = false
            showStatus("LM • TEST TIMER\nUnable to open capture permission")
        }
    }

    private fun stopTest() {
        testRequested = false
        ScreenCaptureService.instance?.stopTestCapture()
        timerView?.post {
            timerView?.text = "TEST: ${selectedTestDurationMinutes} min • READY"
        }
    }

    private fun timerStatusText(): String =
        "TEST: ${selectedTestDurationMinutes} min • READY"

    fun showStatus(text: String) {
        statusView?.post { statusView?.text = text }
    }

    fun showTestTimer(remainingMs: Long, selectedMinutes: Int, running: Boolean) {
        val seconds = ((remainingMs + 999L) / 1000L).coerceAtLeast(0L)
        val minutes = seconds / 60L
        val remainder = seconds % 60L
        val countdown = "%02d:%02d".format(minutes, remainder)
        timerView?.post {
            timerView?.text = if (running) {
                "TEST: ${selectedMinutes} min • ${countdown} REMAINING"
            } else {
                "TEST: ${selectedMinutes} min • COMPLETE"
            }
        }
    }

    fun showCaptureHealth(
        sessionId: Long,
        totalFrames: Long,
        acceptedFrames: Long,
        droppedFrames: Long,
        processedFrames: Long,
        stage: String,
        quality: String
    ) {
        val state = if (stage.contains("ERROR") || stage.contains("STOPPED")) "■" else "●"
        healthView?.post {
            healthView?.text = "CAPTURE  $state $stage • Session #$sessionId\n" +
                "Frames $totalFrames • Accepted $acceptedFrames • Processed $processedFrames • Dropped $droppedFrames\n" +
                "Quality: $quality"
        }
    }

    fun showTargets(targets: List<RankedTarget>) {
        markerView?.post { markerView?.setTargets(targets.take(12)) }
    }

    override fun onDestroy() {
        instance = null
        card?.let { runCatching { wm.removeView(it) } }
        markerView?.let { runCatching { wm.removeView(it) } }
        card = null
        statusView = null
        healthView = null
        timerView = null
        markerView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class TargetMarkerView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var targets: List<RankedTarget> = emptyList()

        init { setLayerType(View.LAYER_TYPE_SOFTWARE, null) }

        fun setTargets(value: List<RankedTarget>) {
            targets = value
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.CYAN
            targets.forEachIndexed { index, target ->
                val x = target.tile.pixelX
                val y = target.tile.pixelY
                canvas.drawCircle(x, y, 28f, paint)
                paint.style = Paint.Style.FILL
                paint.textSize = 22f
                canvas.drawText((index + 1).toString(), x + 32f, y + 8f, paint)
                paint.style = Paint.Style.STROKE
            }
        }
    }
}
