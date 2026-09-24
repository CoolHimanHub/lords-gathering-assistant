package com.coolhiman.lordsassistant.vision

import com.coolhiman.lordsassistant.model.ResourceType
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate

data class PopupState(
    val kind: TargetKind? = null,
    val resource: ResourceType? = null,
    val monsterName: String? = null,
    val level: Int? = null,
    val quantity: Long? = null,
    val occupied: Boolean? = null,
    val incomingTroops: Boolean? = null,
    val coordinate: WorldCoordinate? = null,
    val isPopup: Boolean = false
)

object PopupStateParser {
    private val popupLevel = Regex("""(?:LV|LEVEL)\s*\.?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val coordinate = Regex("""K\s*[:.]?\s*(\d+)\s*X\s*[:.]?\s*(\d+)\s*Y\s*[:.]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val xy = Regex("""X\s*[:.]?\s*(\d+)\s*Y\s*[:.]?\s*(\d+)""", RegexOption.IGNORE_CASE)
    private val quantity = Regex("""(?:timber|food|stone|ore|gold|gems?|energon)\s+([\d,]+)""", RegexOption.IGNORE_CASE)

    fun parse(raw: String, defaultKingdom: Int): PopupState {
        val text = OcrParser.normalize(raw)
        val lower = text.lowercase()
        val classification = GameTextClassifier.classify(text)
        val kind = when {
            classification.kind != null -> classification.kind
            listOf("monster", "blackwing", "frostwing", "gryphon", "hell drider", "noceros", "mecha trojan", "trojan horse", "cottageroar").any { it in lower } -> TargetKind.MONSTER
            else -> null
        }
        val coord = coordinate.find(text)?.let {
            WorldCoordinate(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        } ?: xy.find(text)?.let {
            WorldCoordinate(defaultKingdom, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        // Tile-selection popups are not limited to action targets. Empty terrain
        // can expose bookmark/relocate/share controls, and those popups are
        // valuable grid-learning evidence even though they are not actionable.
        val popupWords = listOf(
            "gather", "attack", "occupier", "unoccupied", "occupy", "hunt",
            "relocate", "migrate", "bookmark", "create bookmark", "pin", "share",
            "transfer", "terrain", "location"
        )
        // X/Y alone is the persistent map HUD, so it must not be treated as a popup.
        // A popup is established by its action/state vocabulary or a named target + level.
        val isPopup = popupWords.any { it in lower } ||
            (kind != null && popupLevel.containsMatchIn(text))

        val occupied = when {
            "unoccupied" in lower || "available" in lower -> false
            "occupier" in lower || "occupied" in lower || "gathering" in lower -> true
            else -> classification.occupied
        }

        val incoming = classification.incomingTroops == true ||
            listOf("marching", "incoming", "arriving", "troops have reached", "started gathering").any { it in lower }

        val parsedQuantity = quantity.find(text)?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()
            ?: classification.quantity

        return PopupState(
            kind = kind,
            resource = classification.resource,
            monsterName = classification.monsterName,
            level = popupLevel.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: classification.level,
            quantity = parsedQuantity,
            occupied = occupied,
            incomingTroops = incoming,
            coordinate = coord,
            isPopup = isPopup
        )
    }
}
