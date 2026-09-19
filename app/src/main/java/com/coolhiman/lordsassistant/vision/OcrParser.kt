package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.WorldCoordinate

object OcrParser {
    private val simple = Regex("""X\s*[:=]\s*(\d{1,4}).*?Y\s*[:=]\s*(\d{1,4})""", RegexOption.IGNORE_CASE)
    private val kingdom = Regex("""K\s*[:=]\s*(\d{1,4}).*?X\s*[:=]\s*(\d{1,4}).*?Y\s*[:=]\s*(\d{1,4})""", RegexOption.IGNORE_CASE)

    fun parseCoordinate(text: String, defaultKingdom: Int = 0): WorldCoordinate? {
        val normalized = text.replace("\n", " ").replace("|", "I")
        kingdom.find(normalized)?.let {
            return WorldCoordinate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        simple.find(normalized)?.let {
            return WorldCoordinate(defaultKingdom, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return null
    }

    fun normalize(text: String): String =
        text.replace("Lv.", "LV", ignoreCase = true).replace(Regex("""\s+"""), " ").trim()
}
