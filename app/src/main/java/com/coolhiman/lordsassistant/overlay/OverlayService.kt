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
import android.widget.TextView
import com.coolhiman.lordsassistant.target.RankedTarget

class OverlayService : Service() {
    companion object { @Volatile var instance: OverlayService? = null }

    private lateinit var wm: WindowManager
    private var card: TextView? = null
    private var markerView: TargetMarkerView? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = TextView(this).apply {
            text = "LM  •  SCANNER\nReady — open the game map"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xCC16181D.toInt())
            setPadding(18, 12, 18, 12)
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0

        view.setOnTouchListener { v, event ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = lp.x; startY = lp.y; true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (event.rawX - downX).toInt()
                    lp.y = startY + (event.rawY - downY).toInt()
                    wm.updateViewLayout(v, lp); true
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
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24; y = 80
        }

        card = view
        wm.addView(view, lp)

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

    fun showStatus(text: String) { card?.post { card?.text = text } }

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
        card?.post {
            card?.text = "LM • SCANNER  $state $stage\\n" +
                "Session #$sessionId • $totalFrames frames\\n" +
                "Accepted $acceptedFrames • Processed $processedFrames • Dropped $droppedFrames\\n" +
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
