package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.map.CameraState
import com.coolhiman.lordsassistant.model.MapObservation

/**
 * Owns per-target temporal stability so camera discontinuities can invalidate
 * every cached target tracker, including targets that were not visible during
 * the unstable interval.
 */
class TargetStabilityRegistry(
    private val requiredFrames: Int = 2
) {
    private val trackers = linkedMapOf<String, TargetStabilityTracker>()

    fun update(key: String, observation: MapObservation, cameraState: CameraState): TargetStability {
        val tracker = trackers.getOrPut(key) { TargetStabilityTracker(requiredFrames) }
        return tracker.update(observation, cameraState)
    }

    fun resetForCameraBoundary() {
        trackers.clear()
    }

    fun removeExcept(activeKeys: Set<String>, maxRetained: Int = 32) {
        if (trackers.size <= maxRetained) return
        trackers.keys
            .filterNot(activeKeys::contains)
            .take(trackers.size - maxRetained)
            .forEach(trackers::remove)
    }

    fun size(): Int = trackers.size
}
