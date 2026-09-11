package com.coolhimanhub.lordsgatheringassistant

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper

/**
 * AutoGatheringEngine
 *
 * SAFE SELECTION BUILD
 *
 * Responsibilities:
 * 1. Receive targets detected by ScreenAnalyzer.
 * 2. Reject occupied/unsafe/weak targets.
 * 3. Reject targets with moving troop/player indicators.
 * 4. Reject candidates carrying a triangular/flag safety warning.
 * 5. Prefer higher RSS levels and confidence.
 * 6. Expose the selected target to GatheringAccessibilityService.
 *
 * IMPORTANT:
 * This version deliberately does NOT perform blind coordinate taps.
 * A candidate must pass panel verification before any future Gather action
 * is permitted.
 */
class AutoGatheringEngine(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    @Volatile
    private var isGathering = false

    @Volatile
    private var currentTarget: RssTarget? = null

    private val gatheringCycleRunnable = object : Runnable {
        override fun run() {
            if (!isGathering) return
            handler.postDelayed(this, GATHER_CYCLE_INTERVAL)
        }
    }

    companion object {
        private const val GATHER_CYCLE_INTERVAL = 8000L
        private const val MIN_CONFIDENCE = 75
        private const val MIN_TARGET_SCORE = 50

        private val ALLOWED_TYPES = setOf(
            "Gold", "Ore", "Wood", "Food", "Stone"
        )
    }

    /** Target data passed from ScreenAnalyzer. */
    data class RssTarget(
        val type: String,
        val level: Int,
        val x: Int,
        val y: Int,
        val confidence: Int,
        val occupied: Boolean,
        val flagScore: Int,
        val targetScore: Int,
        val moving: Boolean = false,
        val movingScore: Int = 0
    ) : Comparable<RssTarget> {

        override fun compareTo(other: RssTarget): Int {
            if (occupied != other.occupied) return if (occupied) 1 else -1
            if (moving != other.moving) return if (moving) 1 else -1
            if (flagScore != other.flagScore) return flagScore.compareTo(other.flagScore)
            if (level != other.level) return other.level.compareTo(level)
            if (targetScore != other.targetScore) return other.targetScore.compareTo(targetScore)
            return other.confidence.compareTo(confidence)
        }
    }

    fun startGathering() {
        if (isGathering) return
        isGathering = true
        currentTarget = null
        handler.removeCallbacks(gatheringCycleRunnable)
        handler.post(gatheringCycleRunnable)
    }

    fun stopGathering() {
        isGathering = false
        currentTarget = null
        handler.removeCallbacks(gatheringCycleRunnable)
    }

    /**
     * Selects the safest currently detected RSS target.
     * Hard rejection rules include occupied, moving and flagged targets.
     */
    fun processDetectedTargets(targets: List<RssTarget>): RssTarget? {
        val safeTargets = targets
            .asSequence()
            .filter { it.type in ALLOWED_TYPES }
            .filter { it.level in 1..5 }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .filter { it.targetScore >= MIN_TARGET_SCORE }
            .filter { !it.occupied }
            .filter { !it.moving }
            .filter { it.movingScore <= 0 }
            .filter { it.flagScore <= 0 }
            .filter { it.x >= 0 && it.y >= 0 }
            .sorted()
            .toList()

        val best = safeTargets.firstOrNull()
        currentTarget = best
        return best
    }

    fun getCurrentTarget(): RssTarget? = currentTarget

    fun clearCurrentTarget() {
        currentTarget = null
    }

    /** Final validation immediately before any future action phase. */
    fun isTargetStillSafe(target: RssTarget): Boolean {
        return target.type in ALLOWED_TYPES &&
                target.level in 1..5 &&
                target.confidence >= MIN_CONFIDENCE &&
                target.targetScore >= MIN_TARGET_SCORE &&
                !target.occupied &&
                !target.moving &&
                target.movingScore <= 0 &&
                target.flagScore <= 0 &&
                target.x >= 0 &&
                target.y >= 0
    }

    /** Placeholder for the future validated action phase. */
    fun performGatheringAction(target: RssTarget) {
        if (!isGathering) return
        if (!isTargetStillSafe(target)) {
            clearCurrentTarget()
            return
        }
        // Deliberately no tap/click is performed here.
        // The opened-panel verifier must approve the candidate first.
        currentTarget = target
    }

    fun isGatheringActive(): Boolean = isGathering

    /** Planning values only; not used for blind timing assumptions. */
    fun getTroopReturnTime(resourceLevel: Int): Long {
        return when (resourceLevel) {
            1 -> 30_000L
            2 -> 45_000L
            3 -> 60_000L
            4 -> 90_000L
            5 -> 120_000L
            else -> 30_000L
        }
    }
}
