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
            text = "Monster Lineups"
            textSize = 20f
            setTextColor(Color.WHITE)
        })

        root.addView(TextView(this).apply {
            text = "Community reference • verify against the current game version"
            textSize = 12f
            setTextColor(0xFFB8BBC4.toInt())
            setPadding(0, 6, 0, 12)
        })

        val names = arrayOf("Blackwing", "Frostwing", "Gryphon", "Hell Drider", "Noceros", "Mecha Trojan")
        val spinner = Spinner(this)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        root.addView(spinner)

        val result = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(0, 18, 0, 0)
        }
        root.addView(result)

        val json = assets.open("lineups.json").bufferedReader().use { JSONObject(it.readText()) }
        val monsters = json.getJSONObject("monsters")

        fun arrayText(obj: JSONObject, key: String): String? {
            if (!obj.has(key)) return null
            val array = obj.getJSONArray(key)
            val values = mutableListOf<String>()
            for (i in 0 until array.length()) values += array.getString(i)
            return values.joinToString(", ")
        }

        fun render(name: String) {
            val monster = monsters.getJSONObject(name)
            val sections = listOf(
                "standard" to "Standard",
                "f2p" to "F2P",
                "p2p" to "P2P",
                "alternative" to "Alternative",
                "alternativeF2p" to "Alternative F2P"
            )

            val lines = mutableListOf("TARGET: $name", "")
            sections.forEach { (key, title) ->
                arrayText(monster, key)?.let {
                    lines += "$title"
                    lines += it
                    lines += ""
                }
            }
            result.text = lines.joinToString("\n").trim()
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) = render(names[position])
        }

        render(names[0])
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
