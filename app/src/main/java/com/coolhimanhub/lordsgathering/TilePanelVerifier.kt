package com.coolhimanhub.lordsgatheringassistant

/**
 * Parses the text visible after an RSS candidate is opened.
 *
 * The supplied gameplay videos show that the opened panel is the authoritative
 * source for resource type, level and occupancy. Map-pixel analysis must only
 * nominate a candidate; this verifier decides whether that candidate is an
 * eligible RSS tile before a future Gather action is permitted.
 */
object TilePanelVerifier {
    data class Verification(
        val isRss: Boolean,
        val type: String?,
        val level: Int?,
        val occupied: Boolean?,
        val gatherAvailable: Boolean,
        val reason: String
    ) {
        val safeToGather: Boolean
            get() = isRss && occupied == false && type != null && level in 1..5 && gatherAvailable
    }

    private val resourceNames = listOf("Gold", "Ore", "Wood", "Food", "Stone", "Rocks")

    fun verify(rawText: String): Verification {
        val text = rawText.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return Verification(false, null, null, null, false, "No panel text")

        // Monsters/other map objects are explicitly negative cases from the video.
        val monsterLike = listOf("Hoctclaw", "Monster", "Familiar", "Castle", "Player")
            .any { text.contains(it, ignoreCase = true) }
        if (monsterLike) return Verification(false, null, null, null, false, "Opened object is not an RSS tile")

        val type = resourceNames.firstOrNull { text.contains(it, ignoreCase = true) }
            ?.let { if (it.equals("Rocks", true)) "Stone" else it }
        if (type == null) return Verification(false, null, null, null, false, "No supported RSS type in panel")

        val levelMatch = Regex("(?:Lv\\.?|Level\\s*)\\s*([1-5])", RegexOption.IGNORE_CASE).find(text)
            ?: Regex("\\b([1-5])\\b").find(text)
        val level = levelMatch?.groupValues?.getOrNull(1)?.toIntOrNull()

        val explicitlyUnoccupied = Regex("\\bunoccupied\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val explicitlyOccupied = Regex("\\boccupied\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) && !explicitlyUnoccupied
        val occupied = when {
            explicitlyUnoccupied -> false
            explicitlyOccupied -> true
            else -> null
        }

        val gather = Regex("\\bgather\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        val reason = when {
            level == null -> "RSS type found but level was not verified"
            occupied == true -> "RSS tile is occupied"
            occupied == null -> "Occupancy was not explicitly verified"
            !gather -> "RSS tile is unoccupied but Gather control was not detected"
            else -> "RSS tile verified: $type Lv$level, Unoccupied, Gather available"
        }
        return Verification(true, type, level, occupied, gather, reason)
    }
}
