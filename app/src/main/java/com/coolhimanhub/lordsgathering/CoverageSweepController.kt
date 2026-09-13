package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V47: movement-first deterministic map coverage planner.
 *
 * A sweep step is accepted ONLY after a fresh HUD X/Y observation proves that
 * the viewport changed. A stalled gesture never advances the route and never
 * silently switches to the opposite direction.
 */
class CoverageSweepController {
    enum class Direction { RIGHT, DOWN, LEFT, UP }

    data class SwipePlan(
        val direction: Direction,
        val start: PointF,
        val end: PointF,
        val durationMs: Long
    )

    private var step = 0
    private var waitingForViewportChange = false
    private var lastDirection: Direction? = null
    private var failedAttempts = 0

    companion object {
        // 1536x707 reference viewport from the supplied phone screenshots.
        // Keep gestures inside the actual map area and away from the
        // bottom action bar / right-side controls.
        private const val LEFT = 520f
        private const val RIGHT = 1250f
        private const val TOP = 135f
        private const val BOTTOM = 505f
        private const val MID_X = 885f
        private const val MID_Y = 320f

        private const val HORIZONTAL_DRAG = 650f
        private const val VERTICAL_DRAG = 315f
        private const val NORMAL_DURATION = 620L

        // Recovery anchors deliberately stay away from the normal gesture
        // anchors. All recovery gestures preserve the intended direction.
        private const val RECOVERY_LEFT = 700f
        private const val RECOVERY_RIGHT = 1110f
        private const val RECOVERY_TOP = 175f
        private const val RECOVERY_BOTTOM = 465f
        private const val RECOVERY_DRAG = 270f
        private const val RECOVERY_DURATION = 500L

        private const val MAX_RECOVERY_ATTEMPTS = 4
    }

    /**
     * Consume a viewport observation.
     *
     * Only a fresh/authoritative coordinate can confirm movement. A fallback
     * coordinate is intentionally ignored by the sweep state machine.
     */
    fun onViewportObserved(changed: Boolean, authoritative: Boolean = true) {
        if (!authoritative) return

        if (changed) {
            // A route step advances only when we were actually waiting for the
            // gesture to be validated. User movement before the first sweep
            // must not consume a route step.
            if (waitingForViewportChange && lastDirection != null) step++
            waitingForViewportChange = false
            failedAttempts = 0
        } else if (waitingForViewportChange) {
            failedAttempts++
        }
    }

    fun markSwipeIssued(direction: Direction) {
        waitingForViewportChange = true
        lastDirection = direction
    }

    fun markDispatchFailure(direction: Direction) {
        // The intended direction remains active so the next scan retries that
        // direction instead of advancing the serpentine route.
        waitingForViewportChange = true
        lastDirection = direction
        failedAttempts++
    }

    fun needsViewportChange(): Boolean = waitingForViewportChange

    /** Number of fresh same-viewport observations since the last swipe. */
    fun failureCount(): Int = failedAttempts

    fun nextSwipe(): SwipePlan {
        val row = step / 2
        val evenRow = row % 2 == 0
        val direction = when (step % 4) {
            0 -> if (evenRow) Direction.RIGHT else Direction.LEFT
            1 -> Direction.DOWN
            2 -> if (evenRow) Direction.LEFT else Direction.RIGHT
            else -> Direction.DOWN
        }

        return when (direction) {
            Direction.RIGHT -> SwipePlan(
                direction,
                PointF(LEFT, MID_Y),
                PointF(LEFT + HORIZONTAL_DRAG, MID_Y),
                NORMAL_DURATION
            )
            Direction.LEFT -> SwipePlan(
                direction,
                PointF(RIGHT, MID_Y),
                PointF(RIGHT - HORIZONTAL_DRAG, MID_Y),
                NORMAL_DURATION
            )
            Direction.DOWN -> SwipePlan(
                direction,
                PointF(MID_X, TOP),
                PointF(MID_X, TOP + VERTICAL_DRAG),
                NORMAL_DURATION
            )
            Direction.UP -> SwipePlan(
                direction,
                PointF(MID_X, BOTTOM),
                PointF(MID_X, BOTTOM - VERTICAL_DRAG),
                NORMAL_DURATION
            )
        }
    }

    /**
     * Retry the SAME intended movement when the previous gesture did not move
     * the viewport. No recovery ever reverses direction.
     */
    fun recoverySwipe(): SwipePlan? {
        if (!waitingForViewportChange) return null
        val direction = lastDirection ?: return null
        if (failedAttempts !in 1..MAX_RECOVERY_ATTEMPTS) return null

        val attempt = failedAttempts
        val (sx, sy) = when (attempt) {
            1 -> RECOVERY_RIGHT to RECOVERY_BOTTOM
            2 -> RECOVERY_LEFT to RECOVERY_TOP
            3 -> RECOVERY_RIGHT to RECOVERY_TOP
            else -> RECOVERY_LEFT to RECOVERY_BOTTOM
        }

        val ex = when (direction) {
            Direction.RIGHT -> sx + RECOVERY_DRAG
            Direction.LEFT -> sx - RECOVERY_DRAG
            Direction.DOWN, Direction.UP -> sx
        }
        val ey = when (direction) {
            Direction.DOWN -> sy + RECOVERY_DRAG
            Direction.UP -> sy - RECOVERY_DRAG
            Direction.RIGHT, Direction.LEFT -> sy
        }

        return SwipePlan(direction, PointF(sx, sy), PointF(ex, ey), RECOVERY_DURATION)
    }

    /**
     * Release a stalled movement without advancing the route. The next normal
     * swipe therefore retries the same intended direction.
     */
    fun releaseStall() {
        waitingForViewportChange = false
        failedAttempts = 0
        // Keep step and lastDirection unchanged deliberately.
    }

    fun reset() {
        step = 0
        waitingForViewportChange = false
        lastDirection = null
        failedAttempts = 0
    }
}
