package com.coolhiman.lordsassistant

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.coolhiman.lordsassistant.target.ActionLifecycleState
import com.coolhiman.lordsassistant.target.ActionManualRecoveryStore
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
        val recoveryButton = Button(this).apply {
            text = "Reset UNKNOWN recovery state"
            setOnClickListener {
                val current = ActionDiagnosticsStore.latest?.lifecycle?.state
                if (current != ActionLifecycleState.UNKNOWN) {
                    Toast.makeText(this@EvidenceDiagnosticsActivity, "No UNKNOWN action state to reset.", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                ActionManualRecoveryStore.requestReset()
                Toast.makeText(
                    this@EvidenceDiagnosticsActivity,
                    "Recovery requested. Keep Automatic actions OFF until the next scan confirms IDLE.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(recoveryButton)
            addView(ScrollView(this@EvidenceDiagnosticsActivity).apply { addView(text) }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
