package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import kotlin.math.abs
import kotlin.math.sqrt

enum class CameraState { STABLE, PANNING, UNSTABLE }

data class CameraAssessment(
    val state: CameraState,
    val sharedTargets: Int,
    val medianShiftPx: Float,
    val spreadPx: Float
)

class CameraStateTracker(
    private val minSharedTargets: Int = 3,
    private val panShiftPx: Float = 70f,
    private val unstableSpreadPx: Float = 90f
) {
    private var previous = emptyMap<String, Pair<Float, Float>>()

    fun update(observations: List<MapObservation>): CameraAssessment {
        val current = observations.mapNotNull { o ->
            val c = o.coordinate ?: return@mapNotNull null
            val p = o.screenPoint ?: return@mapNotNull null
            val key = "${c}:${o.kind}:${o.level}"
            key to (p.x to p.y)
        }.toMap()

        val shifts = current.mapNotNull { (key, p) ->
            val old = previous[key] ?: return@mapNotNull null
            val dx = p.first - old.first
            val dy = p.second - old.second
            sqrt(dx * dx + dy * dy)
        }
        previous = current

        if (shifts.size < minSharedTargets) return CameraAssessment(CameraState.STABLE, shifts.size, 0f, 0f)

        val sorted = shifts.sorted()
        val median = sorted[sorted.size / 2]
        val deviations = sorted.map { abs(it - median) }.sorted()
        val spread = deviations[deviations.size / 2]

        val state = when {
            spread >= unstableSpreadPx -> CameraState.UNSTABLE
            median >= panShiftPx -> CameraState.PANNING
            else -> CameraState.STABLE
        }
        return CameraAssessment(state, shifts.size, median, spread)
    }

    fun reset() { previous = emptyMap() }
}
