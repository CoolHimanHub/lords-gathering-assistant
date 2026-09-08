package com.coolhimanhub.lordsgatheringassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {

    private val rssTypes = arrayOf(
        "Gems",
        "Emerging",
        "Gold",
        "Ore",
        "Stone",
        "Wood",
        "Food",
        "Other"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        buildScreen()
    }

    private fun buildScreen() {

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(30, 40, 30, 30)
        }

        val title = TextView(this).apply {
            text = "Lords Gathering Assistant"
            textSize = 24f
        }

        layout.addView(title)

        val serviceButton = Button(this).apply {
            text = "Enable Accessibility Service"

            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                )
            }
        }

        layout.addView(serviceButton)

        val preferenceTitle = TextView(this).apply {
            text = "\nRSS Preference Order"
            textSize = 20f
        }

        layout.addView(preferenceTitle)

        val list = ListView(this)

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            rssTypes
        )

        list.adapter = adapter

        layout.addView(
            list,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                500
            )
        )

        val instruction = TextView(this).apply {
            text =
                "\nTap an RSS type to select it as your preferred resource.\n" +
                "The scanner will later use this preference when choosing tiles."
            textSize = 16f
        }

        layout.addView(instruction)

        setContentView(layout)
    }
}
