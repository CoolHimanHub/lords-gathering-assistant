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
import com.coolhiman.lordsassistant.target.ActionAuditLogStore
import com.coolhiman.lordsassistant.capture.CaptureSessionDiagnosticsStore
import com.coolhiman.lordsassistant.capture.RealDeviceValidationSummary

class EvidenceDiagnosticsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var text: TextView
    private lateinit var auditText: TextView
    private lateinit var auditLog: ActionAuditLogStore
    private lateinit var captureDiagnostics: CaptureSessionDiagnosticsStore
    private val refresh = object : Runnable {
        override fun run() {
            if (::text.isInitialized) text.text = ActionDiagnosticsFormatter.format(ActionDiagnosticsStore.latest) + "\n\n" +
                RealDeviceValidationSummary(
                    capture = captureDiagnostics.read(),
                    rejectionCounts = auditLog.rejectionCounts(),
                    action = ActionDiagnosticsStore.latest,
                    history = captureDiagnostics.readHistory()
                ).format() + "\n\n" + captureDiagnostics.formatLatest() +
                    "\n\n" + captureDiagnostics.formatHistory()
            if (::auditText.isInitialized) auditText.text =
                "CANDIDATE REJECTIONS — aggregate\n" + auditLog.formatRejectionSummary() +
                    "\n\nACTION AUDIT — latest events\n\n" +
                    auditLog.formatLatest(20).ifBlank { "No audited action events yet." }
            handler.postDelayed(this, 500L)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auditLog = ActionAuditLogStore(this)
        captureDiagnostics = CaptureSessionDiagnosticsStore(this)
        text = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(28, 28, 28, 28)
            setBackgroundColor(Color.rgb(16, 18, 22))
            typeface = android.graphics.Typeface.MONOSPACE
        }
        auditText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(28, 12, 28, 12)
            setBackgroundColor(Color.rgb(12, 14, 18))
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
            addView(ScrollView(this@EvidenceDiagnosticsActivity).apply {
                val content = LinearLayout(this@EvidenceDiagnosticsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text)
                    addView(auditText)
                }
                addView(content)
            }, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(root)
    }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
