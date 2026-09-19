package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind

data class TextClassification(
    val kind: TargetKind? = null,
    val resource: ResourceType? = null,
    val level: Int? = null,
    val quantity: Long? = null,
    val occupied: Boolean? = null,
    val incomingTroops: Boolean? = null
)

object GameTextClassifier {
    private val levelRegex = Regex("""(?:LV|LEVEL)\s*\.?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)
    private val quantityRegex = Regex("""(?:^|\s)(\d{1,3}(?:,\d{3})+|\d{4,})(?:\s|$)""")

    fun classify(raw: String): TextClassification {
        val text = OcrParser.normalize(raw)
        val lower = text.lowercase()
        val resource = when {
            "food" in lower -> ResourceType.FOOD
            "stone" in lower || "rock" in lower -> ResourceType.STONE
            "wood" in lower || "woods" in lower || "timber" in lower -> ResourceType.WOOD
            "ore" in lower -> ResourceType.ORE
            "gold" in lower -> ResourceType.GOLD
            "gem" in lower -> ResourceType.GEM
            "energon" in lower -> ResourceType.ENERGON
            else -> null
        }
        val monster = listOf(
            "blackwing", "frostwing", "gryphon", "hell drider",
            "noceros", "mecha trojan", "trojan horse", "cottageroar"
        ).firstOrNull { it in lower }
        val level = levelRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val quantity = quantityRegex.findAll(text)
            .mapNotNull { it.groupValues[1].replace(",", "").toLongOrNull() }.maxOrNull()
        val occupied = when {
            "unoccupied" in lower || "available" in lower -> false
            "occupied" in lower || "gathering" in lower || "occupier" in lower -> true
            else -> null
        }
        val incoming = listOf(
            "marching", "incoming", "destination", "troops have reached",
            "started gathering", "started hunting", "arriving"
        ).any { it in lower }
        return TextClassification(
            kind = if (monster != null) TargetKind.MONSTER else if (resource != null) TargetKind.RESOURCE else null,
            resource = resource, level = level, quantity = quantity,
            occupied = occupied, incomingTroops = incoming
        )
    }
}
