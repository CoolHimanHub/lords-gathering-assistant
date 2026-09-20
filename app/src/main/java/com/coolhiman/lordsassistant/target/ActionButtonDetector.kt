package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.vision.TextRegion

enum class ActionKind { GATHER, HUNT, ATTACK }

data class ActionButton(
    val kind: ActionKind,
    val bounds: RectF,
    val point: ScreenPoint,
    val confidence: Float
)

object ActionButtonDetector {
    private val labels = mapOf(
        ActionKind.GATHER to listOf("gather", "gath"),
        ActionKind.HUNT to listOf("hunt", "hunting"),
        ActionKind.ATTACK to listOf("attack", "attak")
    )

    fun detect(
        textRegions: List<TextRegion>,
        popupPresent: Boolean,
        targetKind: com.coolhiman.lordsassistant.model.TargetKind?
    ): List<ActionButton> {
        if (!popupPresent) return emptyList()
        return textRegions.mapNotNull { region ->
            val normalized = region.text.lowercase().replace(Regex("[^a-z ]"), " ").trim()
            val kind = labels.entries.firstOrNull { (_, words) ->
                words.any { normalized == it || normalized.contains(it) }
            }?.key ?: return@mapNotNull null

            val expected = when (kind) {
                ActionKind.GATHER -> com.coolhiman.lordsassistant.model.TargetKind.RESOURCE
                ActionKind.HUNT, ActionKind.ATTACK -> com.coolhiman.lordsassistant.model.TargetKind.MONSTER
            }
            if (targetKind != null && expected != targetKind) return@mapNotNull null

            val expanded = RectF(region.bounds).apply { inset(-18f, -12f) }
            ActionButton(
                kind = kind,
                bounds = expanded,
                point = ScreenPoint(expanded.centerX(), expanded.centerY()),
                confidence = 0.90f
            )
        }
    }
}
