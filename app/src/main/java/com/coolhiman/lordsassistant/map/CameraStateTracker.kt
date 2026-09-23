package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import kotlin.math.abs
import kotlin.math.hypot

enum class CameraState { STABLE, PANNING, UNSTABLE }

data class CameraAssessment(
    val state: CameraState,
    val sharedTargets: Int,
    val medianShiftPx: Float,
    val spreadPx: Float,
    val scaleChangePercent: Float = 0f,
    /** True only after an established frame-to-frame continuity comparison. */
    val continuityForActions: Boolean = false
)

/**
 * Estimates camera motion from screen-space observations.
 *
 * World-coordinate association is useful when it is available, but it is not
 * a safe continuity key during early calibration: small coordinate changes can
 * make every visible node look "new" even when the camera is stationary.
 * Matching by semantic identity + nearest screen point gives us a robust
 * screen-space continuity signal without granting any action authority.
 */
class CameraStateTracker(
    private val minSharedTargets: Int = 3,
    private val panShiftPx: Float = 70f,
    private val unstableSpreadPx: Float = 90f,
    private val unstableScaleChangePercent: Float = 8f,
    private val maxSemanticMatchDistancePx: Float = 220f
) {
    private var previous = emptyList<MapObservation>()
    private var established = false

    fun update(observations: List<MapObservation>): CameraAssessment {
        val matches = matchObservations(previous, observations)
        val shifts = matches.map { (old, current) ->
            hypot(
                (current.screenPoint!!.x - old.screenPoint!!.x).toDouble(),
                (current.screenPoint!!.y - old.screenPoint!!.y).toDouble()
            ).toFloat()
        }
        val scaleChangePercent = estimateScaleChange(matches)
        val hadPreviousFrame = established
        previous = observations
        established = true

        if (shifts.size < minSharedTargets) {
            return CameraAssessment(
                state = if (hadPreviousFrame) CameraState.UNSTABLE else CameraState.STABLE,
                sharedTargets = shifts.size,
                medianShiftPx = 0f,
                spreadPx = 0f,
                continuityForActions = false
            )
        }

        val sorted = shifts.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val spread = deviations[deviations.size / 2]

        val state = when {
            spread >= unstableSpreadPx -> CameraState.UNSTABLE
            scaleChangePercent >= unstableScaleChangePercent -> CameraState.UNSTABLE
            median >= panShiftPx -> CameraState.PANNING
            else -> CameraState.STABLE
        }

        return CameraAssessment(
            state = state,
            sharedTargets = shifts.size,
            medianShiftPx = median,
            spreadPx = spread,
            scaleChangePercent = scaleChangePercent,
            continuityForActions = state == CameraState.STABLE && shifts.size >= minSharedTargets
        )
    }

    private fun matchObservations(
        old: List<MapObservation>,
        current: List<MapObservation>
    ): List<Pair<MapObservation, MapObservation>> {
        if (old.isEmpty() || current.isEmpty()) return emptyList()

        val unmatched = old.indices.toMutableSet()
        val matches = mutableListOf<Pair<MapObservation, MapObservation>>()

        // Prefer exact world identity when it exists.
        for (now in current) {
            val point = now.screenPoint ?: continue
            val exact = unmatched.firstOrNull { index ->
                val before = old[index]
                before.coordinate != null &&
                    before.coordinate == now.coordinate &&
                    before.kind == now.kind &&
                    before.level == now.level &&
                    before.screenPoint != null
            }
            if (exact != null) {
                matches += old[exact] to now
                unmatched.remove(exact)
            }
        }

        // Fall back to semantic + nearest screen-space matching. This keeps
        // stationary cameras stable while calibration coordinates settle.
        for (now in current) {
            if (matches.any { it.second === now }) continue
            val point = now.screenPoint ?: continue
            val best = unmatched
                .mapNotNull { index ->
                    val before = old[index]
                    val beforePoint = before.screenPoint ?: return@mapNotNull null
                    if (!sameSemanticTarget(before, now)) return@mapNotNull null
                    val distance = hypot(
                        (point.x - beforePoint.x).toDouble(),
                        (point.y - beforePoint.y).toDouble()
                    ).toFloat()
                    if (distance > maxSemanticMatchDistancePx) null else index to distance
                }
                .minByOrNull { it.second }
                ?.first

            if (best != null) {
                matches += old[best] to now
                unmatched.remove(best)
            }
        }

        return matches
    }

    private fun sameSemanticTarget(a: MapObservation, b: MapObservation): Boolean {
        if (a.kind != b.kind || a.level != b.level) return false
        if (a.label != null && b.label != null && a.label != b.label) return false
        return true
    }

    private fun estimateScaleChange(
        matches: List<Pair<MapObservation, MapObservation>>
    ): Float {
        if (matches.size < minSharedTargets) return 0f

        val ratios = mutableListOf<Float>()
        for (i in 0 until matches.size) {
            for (j in i + 1 until matches.size) {
                val oldA = matches[i].first.screenPoint ?: continue
                val oldB = matches[j].first.screenPoint ?: continue
                val newA = matches[i].second.screenPoint ?: continue
                val newB = matches[j].second.screenPoint ?: continue

                val oldDistance = hypot(
                    (oldA.x - oldB.x).toDouble(),
                    (oldA.y - oldB.y).toDouble()
                )
                if (oldDistance < 5.0) continue

                val newDistance = hypot(
                    (newA.x - newB.x).toDouble(),
                    (newA.y - newB.y).toDouble()
                )
                ratios += ((newDistance / oldDistance) - 1.0).toFloat() * 100f
            }
        }
        if (ratios.isEmpty()) return 0f
        return abs(ratios.sorted()[ratios.size / 2])
    }

    fun reset() {
        previous = emptyList()
        established = false
    }
}
