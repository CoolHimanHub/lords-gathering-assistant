package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V46: movement-first deterministic map coverage planner.
 *
 * The V45 screenshots showed that the planner could keep issuing coverage
 * commands while the game stayed on the same X/Y viewport. V46 therefore
 * treats movement as a state machine: a step is never accepted until a later
 * screenshot proves that the world X/Y changed.
 *
 * The first attempt uses a normal long drag. If the viewport does not change,
 * recovery attempts keep the SAME intended direction but use a different
 * touch anchor and shorter stroke. This avoids immediately undoing a partially
 * successful pan with an opposite-direction recovery gesture.
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
        // Keep every gesture inside the actual map area and away from the
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

        private const val RECOVERY_LEFT = 720f
        private const val RECOVERY_RIGHT = 1080f
        private const val RECOVERY_TOP = 185f
        private const val RECOVERY_BOTTOM = 455f
        private const val RECOVERY_DRAG = 300f
        private const val RECOVERY_DURATION = 480L
    }

    fun onViewportObserved(changed: Boolean) {
        if (changed) {
            waitingForViewportChange = false
            failedAttempts = 0
            if (lastDirection != null) step++
        } else if (waitingForViewportChange) {
            failedAttempts++
        }
    }

    fun markSwipeIssued(direction: Direction) {
        waitingForViewportChange = true
        lastDirection = direction
    }

    fun needsViewportChange(): Boolean = waitingForViewportChange

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
     * Up to two recovery attempts for the same intended movement.
     * Recovery is deliberately not the opposite direction.
     */
    fun recoverySwipe(): SwipePlan? {
        if (!waitingForViewportChange) return null
        val direction = lastDirection ?: return null
        if (failedAttempts !in 1..2) return null

        val alternate = failedAttempts == 2
        val sx = if (alternate) RECOVERY_LEFT else RECOVERY_RIGHT
        val sy = if (alternate) RECOVERY_TOP else RECOVERY_BOTTOM
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

    fun reset() {
        step = 0
        waitingForViewportChange = false
        lastDirection = null
        failedAttempts = 0
    }
}
