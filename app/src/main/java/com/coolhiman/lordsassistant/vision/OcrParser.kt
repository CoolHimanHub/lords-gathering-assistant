package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.WorldCoordinate

object OcrParser {
    private val simple = Regex("""\bX\s*[:=.\-]?\s*(\d{1,4})\b.*?\bY\s*[:=.\-]?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val kingdom = Regex("""\bK\s*[:=.\-]?\s*(\d{1,4})\b.*?\bX\s*[:=.\-]?\s*(\d{1,4})\b.*?\bY\s*[:=.\-]?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private val axis = Regex("""\b([XY])\s*[:=.\-]?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)
    private const val MAX_WORLD_AXIS = 999

    fun parseCoordinate(text: String, defaultKingdom: Int = 0): WorldCoordinate? {
        val normalized = text.replace("\n", " ").replace("|", "I")
        kingdom.find(normalized)?.let {
            val k = it.groupValues[1].toInt()
            val x = it.groupValues[2].toInt()
            val y = it.groupValues[3].toInt()
            if (x <= MAX_WORLD_AXIS && y <= MAX_WORLD_AXIS) {
                return WorldCoordinate(k, x, y)
            }
        }
        simple.find(normalized)?.let {
            val x = it.groupValues[1].toInt()
            val y = it.groupValues[2].toInt()
            if (x <= MAX_WORLD_AXIS && y <= MAX_WORLD_AXIS) {
                return WorldCoordinate(defaultKingdom, x, y)
            }
        }
        return null
    }

    /**
     * ML Kit can return the map HUD's X and Y labels as separate text regions,
     * or attach a nearby level badge digit to one of them. Pairing axis tokens
     * from the coordinate HUD regions is therefore more reliable than relying
     * only on result.text. The bounds are used only to reject obviously distant
     * X/Y pairs; they never create an action target by themselves.
     */
    fun parseCoordinate(
        text: String,
        regions: List<TextRegion>,
        defaultKingdom: Int = 0
    ): WorldCoordinate? {
        parseCoordinate(text, defaultKingdom)?.let { return it }

        data class AxisCandidate(val axis: Char, val value: Int, val x: Float, val y: Float)
        val candidates = regions.flatMap { region ->
            axis.findAll(region.text).mapNotNull { match ->
                val value = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (value > MAX_WORLD_AXIS) return@mapNotNull null
                AxisCandidate(
                    axis = match.groupValues[1].uppercase()[0],
                    value = value,
                    x = region.bounds.centerX(),
                    y = region.bounds.centerY()
                )
            }.toList()
        }
        val xs = candidates.filter { it.axis == 'X' }
        val ys = candidates.filter { it.axis == 'Y' }
        val pair = xs.flatMap { x ->
            ys.map { y ->
                Triple(x, y, kotlin.math.abs(x.y - y.y) + kotlin.math.abs(x.x - y.x) * 0.02f)
            }
        }.minByOrNull { it.third }
        if (pair != null && pair.third <= 90f) {
            return WorldCoordinate(defaultKingdom, pair.first.value, pair.second.value)
        }
        return null
    }

    fun normalize(text: String): String =
        text.replace("Lv.", "LV", ignoreCase = true).replace(Regex("""\s+"""), " ").trim()
}
