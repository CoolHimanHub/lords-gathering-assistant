package com.coolhiman.lordsassistant

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.*
import org.json.JSONObject

class LineupActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 20)
            setBackgroundColor(Color.rgb(16, 18, 22))
        }
        root.addView(TextView(this).apply {
            text = "Monster Lineups"; textSize = 20f; setTextColor(Color.WHITE)
        })
        val names = arrayOf("Blackwing", "Frostwing", "Gryphon", "Hell Drider", "Noceros", "Mecha Trojan")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        root.addView(spinner)
        val result = TextView(this).apply {
            textSize = 14f; setTextColor(Color.WHITE); setPadding(0, 18, 0, 0)
        }
        root.addView(result)
        fun render(name: String) {
            val json = assets.open("lineups.json").bufferedReader().use { JSONObject(it.readText()) }
            val monster = json.getJSONObject("monsters").getJSONObject(name)
            val level1 = monster.getJSONObject("1")
            result.text = "Lv 1\n\nF2P\n${level1.getJSONArray("f2p").joinToString().replace("\"", "")}\n\nP2P\n${level1.getJSONArray("p2p").joinToString().replace("\"", "")}"
        }
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                render(names[position])
            }
        }
        render(names[0])
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
