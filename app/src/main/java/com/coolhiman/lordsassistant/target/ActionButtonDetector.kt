package com.coolhiman.lordsassistant.target

import android.graphics.RectF
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.vision.TextRegion

enum class ActionKind { GATHER, HUNT, ATTACK }

data class ActionButton(
    val kind: ActionKind,
    val bounds: RectF,
    val point: ScreenPoint,
    val confidence: Float
) {
    constructor(kind: ActionKind, point: ScreenPoint, confidence: Float) :
        this(kind, RectF(point.x - 12f, point.y - 12f, point.x + 12f, point.y + 12f), point, confidence)
}

object ActionButtonDetector {
    private val labels = mapOf(
        ActionKind.GATHER to listOf("gather", "gath"),
        ActionKind.HUNT to listOf("hunt", "hunting"),
        ActionKind.ATTACK to listOf("attack", "attak")
    )

    private val popupAnchorWords = listOf(
        "lv", "level", "occupier", "unoccupied", "occupied",
        "gathering", "available", "timber", "food", "stone",
        "ore", "gold", "gems", "energon", "monster", "blackwing",
        "frostwing", "gryphon", "hell drider", "noceros",
        "mecha trojan", "trojan horse", "cottageroar"
    )

    fun detect(
        textRegions: List<TextRegion>,
        popupPresent: Boolean,
        targetKind: TargetKind?,
        popupAnchorMaxDistancePx: Float = 260f
    ): List<ActionButton> {
        if (!popupPresent) return emptyList()

        val actionCandidates = textRegions.mapNotNull { region ->
            val normalized = normalize(region.text)
            val kind = labels.entries.firstOrNull { (_, words) ->
                words.any { normalized == it || normalized.contains(it) }
            }?.key ?: return@mapNotNull null

            val expected = when (kind) {
                ActionKind.GATHER -> TargetKind.RESOURCE
                ActionKind.HUNT, ActionKind.ATTACK -> TargetKind.MONSTER
            }
            if (targetKind != null && expected != targetKind) return@mapNotNull null

            val expanded = RectF(region.bounds).apply { inset(-18f, -12f) }
            ActionCandidate(kind, expanded)
        }

        val anchors = textRegions
            .filter { region ->
                val normalized = normalize(region.text)
                normalized.isNotEmpty() &&
                    popupAnchorWords.any { normalized.contains(it) } &&
                    labels.values.flatten().none { normalized == it }
            }
            .map { it.bounds }

        return actionCandidates.mapNotNull { candidate ->
            if (anchors.isEmpty()) return@mapNotNull null

            val nearest = anchors.minOf { distance(candidate.bounds, it) }
            if (nearest > popupAnchorMaxDistancePx) return@mapNotNull null

            ActionButton(
                kind = candidate.kind,
                bounds = candidate.bounds,
                point = ScreenPoint(candidate.bounds.centerX(), candidate.bounds.centerY()),
                confidence = (0.90f - (nearest / popupAnchorMaxDistancePx) * 0.10f)
                    .coerceIn(0.70f, 0.90f)
            )
        }
    }

    private fun normalize(text: String): String =
        text.lowercase().replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun distance(a: RectF, b: RectF): Float {
        val dx = when {
            a.right < b.left -> b.left - a.right
            b.right < a.left -> a.left - b.right
            else -> 0f
        }
        val dy = when {
            a.bottom < b.top -> b.top - a.bottom
            b.bottom < a.top -> a.top - b.bottom
            else -> 0f
        }
        return kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
    }

    private data class ActionCandidate(
        val kind: ActionKind,
        val bounds: RectF
    )
}
