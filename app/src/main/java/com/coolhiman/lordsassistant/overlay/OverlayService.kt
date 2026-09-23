package com.coolhiman.lordsassistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
        @Volatile private var testTimerArmed: Boolean = false

        fun consumeArmedTestDuration(): Int? {
            if (!testTimerArmed) return null
            testTimerArmed = false
            return selectedTestDurationMinutes
        }
    }

    private lateinit var wm: WindowManager
    private var card: LinearLayout? = null
    private var statusView: TextView? = null
    private var timerView: TextView? = null
    private var markerView: TargetMarkerView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 10, 14, 10)
            setBackgroundColor(0xDD16181D.toInt())
        }

        val status = TextView(this).apply {
            text = "LM • SCANNER\nReady — open the game map"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(4, 2, 4, 6)
        }
        statusView = status

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

        fun timerButton(minutes: Int): Button = Button(this).apply {
            text = minutes.toString()
            textSize = 10f
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(2, 0, 2, 0)
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

        container.addView(status)
        container.addView(timerLabel)
        container.addView(timerStatus)
        container.addView(timerRow(1, 5))
        container.addView(timerRow(6, 10))

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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
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
        testTimerArmed = true
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
                timerView?.text = "TEST: ${selectedTestDurationMinutes} min • ARMED"
            }
            showStatus(
                "LM • TEST TIMER\n${selectedTestDurationMinutes} min armed • start scanner"
            )
        }
    }

    private fun timerStatusText(): String =
        if (testTimerArmed) {
            "TEST: ${selectedTestDurationMinutes} min • ARMED"
        } else {
            "TEST: none • select duration"
        }

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
        statusView?.post {
            statusView?.text = "LM • SCANNER  $state $stage\n" +
                "Session #$sessionId • $totalFrames frames\n" +
                "Accepted $acceptedFrames • Processed $processedFrames • Dropped $droppedFrames\n" +
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
