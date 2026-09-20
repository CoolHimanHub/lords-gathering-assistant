package com.coolhiman.lordsassistant.map

import com.coolhiman.lordsassistant.model.MapObservation
import kotlin.math.abs
import kotlin.math.sqrt

enum class CameraState { STABLE, PANNING, UNSTABLE }

data class CameraAssessment(
    val state: CameraState,
    val sharedTargets: Int,
    val medianShiftPx: Float,
    val spreadPx: Float,
    val scaleChangePercent: Float = 0f
)

class CameraStateTracker(
    private val minSharedTargets: Int = 3,
    private val panShiftPx: Float = 70f,
    private val unstableSpreadPx: Float = 90f,
    private val unstableScaleChangePercent: Float = 8f
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
        val scaleChangePercent = estimateScaleChange(current)
        previous = current

        if (shifts.size < minSharedTargets) return CameraAssessment(CameraState.STABLE, shifts.size, 0f, 0f)

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
        return CameraAssessment(state, shifts.size, median, spread, scaleChangePercent)
    }


    private fun estimateScaleChange(current: Map<String, Pair<Float, Float>>): Float {
        val shared = current.keys.intersect(previous.keys).toList()
        if (shared.size < minSharedTargets) return 0f

        val ratios = mutableListOf<Float>()
        for (i in 0 until shared.size) {
            for (j in i + 1 until shared.size) {
                val a = shared[i]
                val b = shared[j]
                val oldA = previous[a]!!
                val oldB = previous[b]!!
                val newA = current[a]!!
                val newB = current[b]!!
                val oldDistance = sqrt(
                    (oldA.first - oldB.first) * (oldA.first - oldB.first) +
                    (oldA.second - oldB.second) * (oldA.second - oldB.second)
                )
                if (oldDistance < 5f) continue
                val newDistance = sqrt(
                    (newA.first - newB.first) * (newA.first - newB.first) +
                    (newA.second - newB.second) * (newA.second - newB.second)
                )
                ratios += (newDistance / oldDistance - 1f) * 100f
            }
        }
        if (ratios.isEmpty()) return 0f
        return kotlin.math.abs(ratios.sorted()[ratios.size / 2])
    }

    fun reset() { previous = emptyMap() }
}
