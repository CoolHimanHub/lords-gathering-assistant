package com.coolhiman.lordsassistant.vision

import android.graphics.RectF

/**
 * Associates an OCR level label with a visual level badge.
 *
 * The association is intentionally fail-closed: when two plausible level
 * labels are similarly close, no level is assigned. A wrong level can change
 * planner ranking and action eligibility, so ambiguity is safer than guessing.
 */
class LevelBadgeSemanticAssociator(
    private val maxDistancePx: Float = 55f,
    private val maxNearestToSecondRatio: Float = 0.72f
) {
    fun associate(
        badge: RectF,
        levelRegions: List<TextRegion>
    ): Int? {
        val candidates = levelRegions
            .asSequence()
            .mapNotNull { region ->
                val level = region.classification.level?.takeIf { it in 1..5 } ?: return@mapNotNull null
                level to distance(badge, region.bounds)
            }
            .filter { it.second <= maxDistancePx }
            .sortedBy { it.second }
            .toList()

        val nearest = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1) ?: return nearest.first

        // Equal/similar-distance OCR labels are ambiguous even when they
        // contain the same level. Require a clear geometric winner.
        if (second.second <= 0f) return null
        if (nearest.second / second.second > maxNearestToSecondRatio) return null

        return nearest.first
    }

    private fun distance(a: RectF, b: RectF): Float =
        kotlin.math.hypot(
            (a.centerX() - b.centerX()).toDouble(),
            (a.centerY() - b.centerY()).toDouble()
        ).toFloat()
}
