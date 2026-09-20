package com.coolhiman.lordsassistant

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import com.coolhiman.lordsassistant.target.ActionDiagnosticsFormatter
import com.coolhiman.lordsassistant.target.ActionDiagnosticsStore

class EvidenceDiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private val refresh = object : Runnable {
        override fun run() {
            if (::text.isInitialized) text.text = ActionDiagnosticsFormatter.format(ActionDiagnosticsStore.latest)
            handler.postDelayed(this, 500L)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(16, 18, 22))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        setContentView(ScrollView(this).apply { addView(text) })
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
