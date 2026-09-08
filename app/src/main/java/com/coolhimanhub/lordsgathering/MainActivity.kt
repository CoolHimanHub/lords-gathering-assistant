package com.coolhimanhub.lordsgatheringassistant

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var button: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Lords Gathering Assistant"
            textSize = 24f
        }

        statusText = TextView(this).apply {
            textSize = 18f
        }

        button = Button(this).apply {
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        val instructions = TextView(this).apply {
            text = """
                
Accessibility controls the floating assistant.

After enabling the service, return to the app.
A floating "Lords Assistant" control should appear.

Use START to test the tap engine.
Use STOP to stop it.
            """.trimIndent()

            textSize = 16f
        }

        layout.addView(title)
        layout.addView(statusText)
        layout.addView(button)
        layout.addView(instructions)

        setContentView(layout)

        updateAccessibilityStatus()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {

        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        return enabledServices.split(":").any { service ->
            ComponentName.unflattenFromString(service)
                ?.packageName
                ?.equals(packageName, ignoreCase = true) == true
        }
    }

    private fun updateAccessibilityStatus() {

        if (isAccessibilityServiceEnabled()) {

            statusText.text = "✅ Accessibility Service is ENABLED"

            button.text = "Accessibility Service Enabled"
            button.isEnabled = false

        } else {

            statusText.text = "⚠️ Accessibility Service is NOT enabled"

            button.text = "Enable Accessibility Service"
            button.isEnabled = true
        }
    }
}
