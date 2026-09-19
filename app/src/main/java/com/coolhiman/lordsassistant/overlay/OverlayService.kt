package com.coolhiman.lordsassistant.overlay

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

class OverlayService : Service() {
    companion object { @Volatile var instance: OverlayService? = null }

    private lateinit var wm: WindowManager
    private var card: TextView? = null

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
    }

    fun showStatus(text: String) { card?.post { card?.text = text } }

    override fun onDestroy() {
        instance = null
        card?.let { wm.removeView(it) }
        card = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
