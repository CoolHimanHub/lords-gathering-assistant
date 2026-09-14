package com.coolhimanhub.lordsgathering

/**
 * V4.2 target-ranking layer for the existing V50/V53 scanner.
 *
 * This class deliberately consumes ScreenAnalyzer.RssDetection rather than
 * duplicating screenshot/OCR logic. It ranks only unoccupied detections and
 * returns the screen point that is safe to probe.
 *
 * It does NOT press Gather. The existing TilePanelVerifier remains the final
 * authority before any gather action.
 */
class ResourceTargetPlanner {
    data class Target(
        val detection: ScreenAnalyzer.RssDetection,
        val score: Double,
        val key: String
    )

    companion object {
        private const val MIN_CONFIDENCE = 68
        private const val MAX_TARGETS = 20

        fun rank(
            detections: List<ScreenAnalyzer.RssDetection>,
            originX: Int? = null,
            originY: Int? = null
        ): List<Target> {
            return detections
                .asSequence()
                .filter { !it.occupied }
                .filter { it.confidence >= MIN_CONFIDENCE }
                .map { d ->
                    val distance = if (originX != null && originY != null) {
                        val dx = (d.centerX - originX).toDouble()
                        val dy = (d.centerY - originY).toDouble()
                        kotlin.math.sqrt(dx * dx + dy * dy)
                    } else 0.0

                    // Explainable priority: confidence + level + modest
                    // distance preference. Type/amount are intentionally not
                    // guessed here because ScreenAnalyzer currently reports
                    // RSS? until the selected tile panel is verified.
                    val score =
                        d.confidence * 1.0 +
                        d.level * 8.0 -
                        distance * 0.08

                    Target(
                        detection = d,
                        score = score,
                        key = "${d.centerX}:${d.centerY}:${d.level}"
                    )
                }
                .sortedByDescending { it.score }
                .take(MAX_TARGETS)
                .toList()
        }
    }
}
