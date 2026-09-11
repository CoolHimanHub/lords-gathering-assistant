package com.coolhimanhub.lordsgatheringassistant

/**
 * Verifies the text visible after a candidate RSS tile is opened.
 *
 * The supplied gameplay video shows that the opened panel is the authoritative
 * source for resource type, level and occupancy. Map-pixel analysis may only
 * nominate a candidate; this verifier decides whether it is eligible for a
 * future Gather action.
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

    // OCR wording observed in the supplied panel/video frames.
    private val resourceAliases = linkedMapOf(
        "gold" to "Gold",
        "ore" to "Ore",
        "wood" to "Wood",
        "timber" to "Wood",
        "food" to "Food",
        "stone" to "Stone",
        "rock" to "Stone",
        "rocks" to "Stone"
    )

    private val monsterLike = listOf(
        "Hoctclaw", "Hootclaw", "Monster", "Familiar", "Castle", "Player"
    )

    fun verify(rawText: String): Verification {
        val text = rawText
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isEmpty()) {
            return Verification(false, null, null, null, false, "No panel text")
        }

        // Explicit negative cases from the supplied video. These must never be
        // treated as RSS even if a nearby object contains a level-like badge.
        if (monsterLike.any { text.contains(it, ignoreCase = true) }) {
            return Verification(false, null, null, null, false, "Opened object is not an RSS tile")
        }

        val type = resourceAliases.entries
            .firstOrNull { text.contains(it.key, ignoreCase = true) }
            ?.value

        if (type == null) {
            return Verification(false, null, null, null, false, "No supported RSS type in panel")
        }

        // Accept both normal Android text and common OCR variants such as
        // "Woods Lv.4" / "Wood L4" / "Level 4".
        val level = listOf(
            Regex("(?:Lv|L)\\.?\\s*([1-5])", RegexOption.IGNORE_CASE),
            Regex("\\bLevel\\s*([1-5])\\b", RegexOption.IGNORE_CASE),
            Regex("\\b([1-5])\\b")
        ).asSequence()
            .mapNotNull { it.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() }
            .firstOrNull()

        val explicitlyUnoccupied = Regex("\\bunoccupied\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
        val explicitlyOccupied = Regex("\\boccupied\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text) && !explicitlyUnoccupied

        val occupied = when {
            explicitlyUnoccupied -> false
            explicitlyOccupied -> true
            else -> null
        }

        val gather = Regex("\\bgather\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)

        val reason = when {
            level == null -> "RSS type found but level was not verified"
            occupied == true -> "RSS tile is occupied"
            occupied == null -> "Occupancy was not explicitly verified"
            !gather -> "RSS tile is unoccupied but Gather control was not detected"
            else -> "RSS tile verified: $type Lv$level, Unoccupied, Gather available"
        }

        return Verification(
            isRss = true,
            type = type,
            level = level,
            occupied = occupied,
            gatherAvailable = gather,
            reason = reason
        )
    }
}
