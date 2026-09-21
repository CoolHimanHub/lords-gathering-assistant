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
import com.coolhiman.lordsassistant.capture.ScreenCaptureService
import com.coolhiman.lordsassistant.data.PreferencesStore
import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.overlay.OverlayService

class MainActivity : Activity() {
    private lateinit var store: PreferencesStore
    private lateinit var readinessStatus: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreferencesStore(this)
        val current = store.load()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,24,28,24); setBackgroundColor(Color.rgb(16,18,22)) }
        root.addView(TextView(this).apply { text = "LM Companion  •  V0.8.1"; textSize = 20f; setTextColor(Color.WHITE); setPadding(0,0,0,12) })
        root.addView(TextView(this).apply { text = "Resource + monster scanner / calibration console / compact overlay"; setTextColor(0xFFB8BBC4.toInt()); setPadding(0,0,0,14) })
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
        root.addView(Button(this).apply { text="Start screen scanner"; setOnClickListener { requestCapture() } })
        root.addView(TextView(this).apply { text="Workflow: screen → CV → OCR → coordinate → validation → overlay"; setTextColor(0xFFB8BBC4.toInt()); gravity=Gravity.CENTER_HORIZONTAL; setPadding(0,20,0,0) })
        setContentView(ScrollView(this).apply { addView(root) })
    }
    override fun onResume() {
        super.onResume()
        readinessStatus.text = readinessText()
    }

    private fun readinessText(): String {
        val overlay = Settings.canDrawOverlays(this)
        val automatic = store.load().automaticActions
        return buildString {
            append("Overlay: ").append(if (overlay) "READY" else "NEEDS PERMISSION")
            append("\nScreen capture: requested when scanner starts")
            append("\nAutomatic actions: ").append(if (automatic) "ENABLED (advanced)" else "OFF — safe default")
            append("\nRuntime: V0.8.1 capture hardening active")
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
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 9001)
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) { super.onActivityResult(requestCode,resultCode,data); if(requestCode!=9001 || resultCode!=RESULT_OK || data==null) return; startForegroundService(Intent(this,ScreenCaptureService::class.java).apply { putExtra(ScreenCaptureService.EXTRA_RESULT_CODE,resultCode); putExtra(ScreenCaptureService.EXTRA_DATA,data) }); if (store.load().overlayEnabled) startOverlayServiceSafely() }
}
