package com.coolhiman.lordsassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.Gravity
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.coolhiman.lordsassistant.capture.ScreenCaptureService
import com.coolhiman.lordsassistant.accessibility.LmAccessibilityService
import com.coolhiman.lordsassistant.data.PreferencesStore
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.overlay.OverlayService

class MainActivity : Activity() {
    companion object {
        const val ACTION_START_CAPTURE = "com.coolhiman.lordsassistant.action.START_CAPTURE"
    }
    private lateinit var store: PreferencesStore
    private lateinit var readinessStatus: TextView
    private var projectionResultCode: Int? = null
    private var projectionData: Intent? = null
    private var captureButton: Button? = null
    @Volatile private var captureStartInProgress = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreferencesStore(this)
        val current = store.load()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28,24,28,24)
            setBackgroundColor(Color.rgb(16,18,22))
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(28, 24 + bars.top, 28, 24 + bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        root.addView(TextView(this).apply { text = "LM Companion  •  V2.2.4"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0,0,0,12) })
        root.addView(TextView(this).apply { text = "Resource + monster companion • live map intelligence • compact overlay"; setTextColor(0xFFB8BBC4.toInt()); setPadding(0,0,0,14) })
        root.addView(Switch(this).apply { text = "Always-on-top overlay"; setTextColor(Color.WHITE); isChecked=current.overlayEnabled; setOnCheckedChangeListener { _,checked -> if (checked && !Settings.canDrawOverlays(this@MainActivity)) { isChecked = false; store.setOverlayEnabled(false); Toast.makeText(this@MainActivity, "Grant overlay permission first", Toast.LENGTH_SHORT).show(); startOverlayPermission() } else { store.setOverlayEnabled(checked); if (checked) startOverlayServiceSafely() else stopService(Intent(this@MainActivity,OverlayService::class.java)) } } })
        root.addView(TextView(this).apply {
            text = "DEVICE READINESS"
            setTextColor(0xFF8D91A0.toInt())
            setPadding(0, 10, 0, 4)
        })
        readinessStatus = TextView(this).apply {
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 8)
            text = readinessText()
        }
        root.addView(readinessStatus)
        root.addView(Switch(this).apply { text = "Developer diagnostics overlay"; setTextColor(Color.WHITE); isChecked = current.diagnosticsOverlay; setOnCheckedChangeListener { _, checked -> store.setDiagnosticsOverlay(checked); if (checked) Toast.makeText(this@MainActivity, "Restart overlay to apply diagnostics mode", Toast.LENGTH_SHORT).show() } })
        root.addView(TextView(this).apply { text="RESOURCE PREFERENCES"; setTextColor(0xFF8D91A0.toInt()); setPadding(0,18,0,4) })
        ResourceType.values().forEach { type -> root.addView(CheckBox(this).apply { text=type.name; setTextColor(Color.WHITE); isChecked=current.resourceTypes.contains(type); setOnCheckedChangeListener { _,checked -> store.setResourceEnabled(type,checked) } }) }
        val levelRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
        (1..5).forEach { level -> levelRow.addView(CheckBox(this).apply { text="L$level"; setTextColor(Color.WHITE); isChecked=current.resourceLevels.contains(level); setOnCheckedChangeListener { _,checked -> store.setResourceLevelEnabled(level,checked) } }) }
        root.addView(levelRow)
        root.addView(Button(this).apply { text="Visual calibration / training console"; setOnClickListener { startActivity(Intent(this@MainActivity,CalibrationActivity::class.java)) } })
        root.addView(Button(this).apply { text="Evidence & safety diagnostics"; setOnClickListener { startActivity(Intent(this@MainActivity,EvidenceDiagnosticsActivity::class.java)) } })
        root.addView(Button(this).apply { text="Monster lineup quick reference"; setOnClickListener { startActivity(Intent(this@MainActivity,LineupActivity::class.java)) } })
        root.addView(Switch(this).apply { text="Automatic actions (advanced)"; setTextColor(Color.WHITE); isChecked=current.automaticActions; setOnCheckedChangeListener { _,checked -> store.setAutomaticActions(checked) } })
        root.addView(Button(this).apply { text="Grant overlay permission"; setOnClickListener { startOverlayPermission() } })
        root.addView(Button(this).apply { text="Enable gesture service"; setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } })
        captureButton = Button(this).apply {
            text = "Prepare screen capture"
            setOnClickListener { requestCapture() }
        }
        root.addView(captureButton)
        root.addView(TextView(this).apply { text="Workflow: capture → CV/OCR → K/X/Y → discovery → ranking → validation → guarded action"; setTextColor(0xFFB8BBC4.toInt()); gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,20,0,0) })
        setContentView(ScrollView(this).apply { addView(root) })

        if (intent?.action == ACTION_START_CAPTURE) {
            root.post { requestCapture() }
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.action == ACTION_START_CAPTURE) {
            window.decorView.post { requestCapture() }
        }
    }

    override fun onResume() {
        super.onResume()
        readinessStatus.text = readinessText()
        refreshCaptureButtonState()
    }

    private fun refreshCaptureButtonState() {
        val active = ScreenCaptureService.instance?.isCaptureSessionActive() == true
        captureButton?.post {
            if (active) {
                captureButton?.text = "Scanner running"
                captureButton?.isEnabled = false
            } else if (!captureStartInProgress) {
                projectionResultCode = null
                projectionData = null
                captureButton?.text = "Prepare screen capture"
                captureButton?.isEnabled = true
            }
        }
    }

    private fun readinessText(): String {
        val overlay = Settings.canDrawOverlays(this)
        val automatic = store.load().automaticActions
        return buildString {
            append("Overlay: ").append(if (overlay) "READY" else "NEEDS PERMISSION")
            append("\nGesture service: ").append(if (LmAccessibilityService.instance != null) "CONNECTED" else "NOT CONNECTED")
            append("\nScreen capture: requested when scanner starts")
            append("\nAutomatic actions: ").append(if (automatic) "ENABLED (advanced)" else "OFF — safe default")
            append("\nRuntime: V2.2.4 — discovery, grid learning, calibration and guarded actions")
        }
    }

    private fun startOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    private fun startOverlayServiceSafely() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required", Toast.LENGTH_SHORT).show()
            startOverlayPermission()
            return
        }
        runCatching {
            startService(Intent(this, OverlayService::class.java))
        }.onFailure {
            Toast.makeText(this, "Unable to start overlay", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestCapture() {
        if (ScreenCaptureService.instance?.isCaptureSessionActive() == true) {
            captureButton?.text = "Scanner running"
            captureButton?.isEnabled = false
            Toast.makeText(this, "Scanner is already running", Toast.LENGTH_SHORT).show()
            return
        }
        captureStartInProgress = true
        // "Prepare screen capture" is the explicit user intent to start a
        // capture session. App Cast supplies the required MediaProjection
        // consent; once consent returns successfully, startPendingCapture()
        // completes that same user-requested scanner start.
        if (LmAccessibilityService.instance == null) {
            Toast.makeText(
                this,
                "Enable LM Companion gesture service before starting scanner",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Grant overlay permission before starting scanner", Toast.LENGTH_LONG).show()
            startOverlayPermission()
            return
        }

        if (projectionData == null) {
            store.setOverlayEnabled(true)
            startOverlayServiceSafely()
            val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(manager.createScreenCaptureIntent(), 9001)
            return
        }

        startPendingCapture()
    }

    private fun startPendingCapture() {
        val data = projectionData ?: return
        val resultCode = projectionResultCode ?: return
        store.setOverlayEnabled(true)
        startOverlayServiceSafely()
        startForegroundService(
            Intent(this, ScreenCaptureService::class.java).apply {
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_DATA, data)
            }
        )
        captureButton?.post {
            captureButton?.text = "Scanner starting…"
            captureButton?.isEnabled = false
        }
        window.decorView.postDelayed({ refreshCaptureButtonState() }, 1200L)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9001) return

        if (resultCode != RESULT_OK || data == null) {
            captureStartInProgress = false
            projectionResultCode = null
            projectionData = null
            captureButton?.text = "Prepare screen capture"
            Toast.makeText(this, "Screen-capture permission not granted", Toast.LENGTH_SHORT).show()
            return
        }

        projectionResultCode = resultCode
        projectionData = data
        captureButton?.post {
            captureButton?.text = "Starting scanner…"
            captureButton?.isEnabled = false
        }
        Toast.makeText(
            this,
            "App cast approved. Starting scanner…",
            Toast.LENGTH_SHORT
        ).show()
        startPendingCapture()
        window.decorView.postDelayed({ refreshCaptureButtonState() }, 1500L)
    }

    override fun onDestroy() {
        captureStartInProgress = false
        super.onDestroy()
    }

}
