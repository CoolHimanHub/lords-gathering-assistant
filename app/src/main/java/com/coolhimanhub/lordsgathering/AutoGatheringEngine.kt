package com.coolhimanhub.lordsgathering

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
 * 3. Prefer higher RSS levels and confidence.
 * 4. Expose the selected target to GatheringAccessibilityService.
 *
 * IMPORTANT:
 * This version deliberately does NOT perform blind coordinate taps.
 * The service/analyzer must first validate the target and the current
 * game screen before any future UI-action phase is enabled.
 */
class AutoGatheringEngine(
    private val service: AccessibilityService,
    private val handler: Handler = Handler(Looper.getMainLooper())
) {

    // ============================================================
    // STATE
    // ============================================================

    @Volatile
    private var isGathering = false

    @Volatile
    private var currentTarget: RssTarget? = null

    private val gatheringCycleRunnable = object : Runnable {
        override fun run() {
            if (!isGathering) return

            // Target selection is intentionally driven by the service's
            // latest validated screen analysis.
            //
            // Do not invent coordinates or tap a stale target here.
            handler.postDelayed(this, GATHER_CYCLE_INTERVAL)
        }
    }

    // ============================================================
    // CONFIG
    // ============================================================

    companion object {
        private const val GATHER_CYCLE_INTERVAL = 8000L

        // Conservative thresholds.
        private const val MIN_CONFIDENCE = 75
        private const val MIN_TARGET_SCORE = 50

        // Known resource types that may be gathered.
        private val ALLOWED_TYPES = setOf(
            "Gold",
            "Ore",
            "Wood",
            "Food",
            "Stone"
        )
    }

    // ============================================================
    // TARGET DATA
    // ============================================================

    /**
     * Kept compatible with the current GatheringAccessibilityService.
     *
     * occupied:
     *   true  = triangular/occupation indication detected
     *   false = no occupation indication detected
     *
     * flagScore:
     *   confidence/strength of occupation-flag detection.
     *
     * targetScore:
     *   overall candidate score calculated by ScreenAnalyzer.
     */
    data class RssTarget(
        val type: String,
        val level: Int,
        val x: Int,
        val y: Int,
        val confidence: Int,
        val occupied: Boolean,
        val flagScore: Int,
        val targetScore: Int
    ) : Comparable<RssTarget> {

        override fun compareTo(other: RssTarget): Int {

            // 1. Never prefer an occupied target.
            if (occupied != other.occupied) {
                return if (occupied) 1 else -1
            }

            // 2. Higher resource level first.
            if (level != other.level) {
                return other.level.compareTo(level)
            }

            // 3. Higher overall target score next.
            if (targetScore != other.targetScore) {
                return other.targetScore.compareTo(targetScore)
            }

            // 4. Higher detector confidence next.
            return other.confidence.compareTo(confidence)
        }
    }

    // ============================================================
    // PUBLIC API
    // ============================================================

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
     *
     * Hard rejection rules:
     * - occupied target
     * - confidence below threshold
     * - level outside 1..5
     * - unknown/non-RSS type
     * - weak target score
     *
     * NOTE:
     * Moving-player/troop detection must be represented by ScreenAnalyzer
     * before this method can reject it. The current RssTarget API does not
     * contain a separate "moving" field, so this engine does not pretend
     * that it can detect movement by itself.
     */
    fun processDetectedTargets(
        targets: List<RssTarget>
    ): RssTarget? {

        val safeTargets = targets
            .asSequence()
            .filter { target ->
                target.type in ALLOWED_TYPES
            }
            .filter { target ->
                target.level in 1..5
            }
            .filter { target ->
                target.confidence >= MIN_CONFIDENCE
            }
            .filter { target ->
                target.targetScore >= MIN_TARGET_SCORE
            }
            .filter { target ->
                !target.occupied
            }
            .filter { target ->
                target.x >= 0 && target.y >= 0
            }
            .sorted()
            .toList()

        val best = safeTargets.firstOrNull()

        currentTarget = best

        return best
    }

    /**
     * Returns the current selected target, if any.
     */
    fun getCurrentTarget(): RssTarget? = currentTarget

    /**
     * Clears the selected target.
     *
     * Call this after:
     * - the target disappears,
     * - another player starts gathering it,
     * - a movement indicator appears,
     * - the game screen changes,
     * - or the gather attempt is cancelled.
     */
    fun clearCurrentTarget() {
        currentTarget = null
    }

    /**
     * Final validation immediately before any future action phase.
     *
     * This does not perform a tap.
     */
    fun isTargetStillSafe(target: RssTarget): Boolean {
        return target.type in ALLOWED_TYPES &&
                target.level in 1..5 &&
                target.confidence >= MIN_CONFIDENCE &&
                target.targetScore >= MIN_TARGET_SCORE &&
                !target.occupied &&
                target.x >= 0 &&
                target.y >= 0
    }

    /**
     * Placeholder for the future validated action phase.
     *
     * Deliberately no coordinate taps are performed here.
     * A future implementation must re-scan/validate the game UI before
     * opening a tile and before pressing Gather/Send.
     */
    fun performGatheringAction(target: RssTarget) {
        if (!isGathering) return

        if (!isTargetStillSafe(target)) {
            clearCurrentTarget()
            return
        }

        currentTarget = target

        // Intentionally no blind gesture execution.
        //
        // The next integration step will make the service:
        // 1. revalidate this exact target,
        // 2. detect occupation/movement again,
        // 3. open the tile,
        // 4. verify the Gather UI,
        // 5. then perform the action.
    }

    fun isGatheringActive(): Boolean = isGathering

    /**
     * Kept for compatibility with the previous engine API.
     *
     * These are planning values only; they are NOT used to schedule
     * an actual troop return or to make blind timing assumptions.
     */
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
