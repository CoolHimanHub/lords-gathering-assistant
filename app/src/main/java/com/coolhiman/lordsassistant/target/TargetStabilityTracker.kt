package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.model.MapObservation

data class TargetStability(
    val consecutiveFrames: Int = 0,
    val sameTarget: Boolean = false,
    val stable: Boolean = false
)

class TargetStabilityTracker(
    private val requiredFrames: Int = 2
) {
    private var previous: MapObservation? = null
    private var frames = 0

    fun update(observation: MapObservation?, cameraState: CameraState): TargetStability {
        if (observation == null || cameraState != CameraState.STABLE) {
            previous = null
            frames = 0
            return TargetStability()
        }

        val same = previous?.let { sameTarget(it, observation) } == true
        frames = if (same) frames + 1 else 1
        previous = observation

        return TargetStability(
            consecutiveFrames = frames,
            sameTarget = same,
            stable = frames >= requiredFrames
        )
    }

    fun reset() {
        previous = null
        frames = 0
    }

    private fun sameTarget(a: MapObservation, b: MapObservation): Boolean {
        if (a.coordinate == null || a.coordinate != b.coordinate) return false
        if (a.kind != b.kind || a.level == null || a.level != b.level) return false

        // A known semantic identity is part of target continuity. Without this,
        // two different monsters/resources occupying the same world tile and
        // level could inherit the previous frame's stability.
        val first = semanticIdentity(a)
        val second = semanticIdentity(b)
        return first == null || second == null || first.equals(second, ignoreCase = true)
    }

    private fun semanticIdentity(observation: MapObservation): String? =
        observation.label
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.takeUnless {
                observation.kind == com.coolhiman.lordsassistant.model.TargetKind.MONSTER &&
                    it.equals("MONSTER", ignoreCase = true)
            }
}
