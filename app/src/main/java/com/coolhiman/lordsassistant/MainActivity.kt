package com.coolhiman.lordsassistant

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.*
import com.coolhiman.lordsassistant.capture.ScreenCaptureService
import com.coolhiman.lordsassistant.data.PreferencesStore
import com.coolhiman.lordsassistant.overlay.OverlayService

class MainActivity : Activity() {
    private lateinit var store: PreferencesStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = PreferencesStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }

        root.addView(TextView(this).apply {
            text = "LM Companion  •  v0.1"
            textSize = 20f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        })

        root.addView(TextView(this).apply {
            text = "Resource + monster scanner / compact overlay"
            setTextColor(0xFFB8BBC4.toInt())
            setPadding(0, 0, 0, 18)
        })

        root.addView(Switch(this).apply {
            text = "Always-on-top overlay"
            setTextColor(Color.WHITE)
            isChecked = store.load().overlayEnabled
            setOnCheckedChangeListener { _, checked ->
                store.setOverlayEnabled(checked)
                if (checked) startService(Intent(this@MainActivity, OverlayService::class.java))
                else stopService(Intent(this@MainActivity, OverlayService::class.java))
            }
        })

        root.addView(Switch(this).apply {
            text = "Automatic actions (advanced)"
            setTextColor(Color.WHITE)
            isChecked = store.load().automaticActions
            setOnCheckedChangeListener { _, checked -> store.setAutomaticActions(checked) }
        })

        root.addView(Button(this).apply {
            text = "Grant overlay permission"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })

        root.addView(Button(this).apply {
            text = "Enable gesture service"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        root.addView(Button(this).apply {
            text = "Start screen scanner"
            setOnClickListener { requestCapture() }
        })

        root.addView(TextView(this).apply {
            text = "Workflow: screen → CV → OCR → coordinate → validation → overlay"
            setTextColor(0xFFB8BBC4.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 24, 0, 0)
        })

        setContentView(root)
    }

    private fun requestCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(manager.createScreenCaptureIntent(), 9001)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 9001 || resultCode != RESULT_OK || data == null) return

        val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenCaptureService.EXTRA_DATA, data)
        }
        startForegroundService(serviceIntent)

        if (store.load().overlayEnabled) {
            startService(Intent(this, OverlayService::class.java))
        }
    }
}
