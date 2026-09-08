package com.coolhimanhub.lordsgatheringassistant

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {

    private val defaultRss = mutableListOf(
        "Gems",
        "Emerging",
        "Gold",
        "Ore",
        "Stone",
        "Wood",
        "Food",
        "Other"
    )

    private lateinit var rssList: MutableList<String>
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var listView: ListView

    private val prefs by lazy {
        getSharedPreferences("gathering_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadPreferences()
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (::listView.isInitialized) {
            updateServiceButton()
        }
    }

    private fun loadPreferences() {

        val saved = prefs.getString("rss_order", null)

        rssList = if (saved == null) {
            defaultRss.toMutableList()
        } else {
            saved.split("|").toMutableList()
        }
    }

    private fun savePreferences() {

        prefs.edit()
            .putString("rss_order", rssList.joinToString("|"))
            .apply()
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

        val serviceButton = Button(this)

        serviceButton.setOnClickListener {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            )
        }

        layout.addView(serviceButton)

        val heading = TextView(this).apply {
            text = "\nRSS Gathering Preference"
            textSize = 20f
        }

        layout.addView(heading)

        val instruction = TextView(this).apply {
            text = "Select an RSS and move it UP or DOWN."
            textSize = 16f
        }

        layout.addView(instruction)

        listView = ListView(this)

        adapter = ArrayAdapter(
    this,
    android.R.layout.simple_list_item_activated_1,
    ArrayList(rssList)
)

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_SINGLE

        layout.addView(
    listView,
    LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(320)
    )
)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val upButton = Button(this).apply {
            text = "▲ UP"

            setOnClickListener {
                moveSelected(-1)
            }
        }

        val downButton = Button(this).apply {
            text = "▼ DOWN"

            setOnClickListener {
                moveSelected(1)
            }
        }

        buttons.addView(
    upButton,
    LinearLayout.LayoutParams(0, dp(55), 1f)
)

buttons.addView(
    downButton,
    LinearLayout.LayoutParams(0, dp(55), 1f)
)
        layout.addView(buttons)

        val resetButton = Button(this).apply {
            text = "RESET DEFAULT ORDER"

            setOnClickListener {
                rssList.clear()
                rssList.addAll(defaultRss)
                savePreferences()
                refreshList()
            }
        }

        layout.addView(resetButton)

        val current = TextView(this).apply {
            text = "\nHighest priority = item #1\n\n" +
                    "The gathering engine will later use this order " +
                    "when choosing between nearby RSS tiles."
            textSize = 16f
        }

        layout.addView(current)

        updateServiceButtonText(serviceButton)

        setContentView(layout)
    }

    private fun moveSelected(direction: Int) {

        val position = listView.checkedItemPosition

        if (position == ListView.INVALID_POSITION) {
            Toast.makeText(
                this,
                "Select an RSS first",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val newPosition = position + direction

        if (newPosition < 0 || newPosition >= rssList.size) {
            return
        }

        val item = rssList.removeAt(position)

        rssList.add(newPosition, item)

        savePreferences()
        refreshList()

        listView.setItemChecked(newPosition, true)
        listView.setSelection(newPosition)
    }

    private fun refreshList() {

        adapter.clear()
        adapter.addAll(rssList)
        adapter.notifyDataSetChanged()
    }

    private fun updateServiceButton() {

        // Screen is rebuilt only when necessary.
    }

        private fun updateServiceButtonText(button: Button) {
        button.text = "ENABLE / MANAGE ACCESSIBILITY SERVICE"
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }
}
