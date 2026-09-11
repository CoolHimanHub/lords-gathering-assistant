package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V33: deterministic map coverage planner.
 *
 * It generates bounded swipes inside the playable map and advances only after
 * the next screenshot proves that the viewport changed. This prevents the old
 * "scan the same screen forever" behaviour.
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
        private const val LEFT = 450f
        private const val RIGHT = 1280f
        private const val TOP = 125f
        private const val BOTTOM = 545f
        private const val MID_X = 865f
        private const val MID_Y = 335f
        private const val HORIZONTAL_DRAG = 500f
        private const val VERTICAL_DRAG = 260f
        private const val DURATION = 650L
    }

    /** Call when a screenshot is captured and its viewport OCR has been parsed. */
    fun onViewportObserved(changed: Boolean) {
        if (changed) {
            waitingForViewportChange = false
            failedAttempts = 0
            // The first observation establishes the baseline; it is not a sweep.
            // Subsequent changed observations mean the previous sweep succeeded.
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
        // Serpentine coverage: horizontal sweeps followed by a vertical shift.
        val row = step / 2
        val evenRow = row % 2 == 0
        val direction = when (step % 4) {
            0 -> if (evenRow) Direction.RIGHT else Direction.LEFT
            1 -> Direction.DOWN
            2 -> if (evenRow) Direction.LEFT else Direction.RIGHT
            else -> Direction.DOWN
        }

        return when (direction) {
            Direction.RIGHT -> SwipePlan(direction, PointF(LEFT, MID_Y), PointF(LEFT + HORIZONTAL_DRAG, MID_Y), DURATION)
            Direction.LEFT -> SwipePlan(direction, PointF(RIGHT, MID_Y), PointF(RIGHT - HORIZONTAL_DRAG, MID_Y), DURATION)
            Direction.DOWN -> SwipePlan(direction, PointF(MID_X, TOP), PointF(MID_X, TOP + VERTICAL_DRAG), DURATION)
            Direction.UP -> SwipePlan(direction, PointF(MID_X, BOTTOM), PointF(MID_X, BOTTOM - VERTICAL_DRAG), DURATION)
        }
    }

    /** Retry the opposite direction exactly once after an unchanged viewport. */
    fun recoverySwipe(): SwipePlan? {
        if (!waitingForViewportChange || failedAttempts != 1) return null
        return when (lastDirection) {
            Direction.RIGHT -> SwipePlan(Direction.LEFT, PointF(RIGHT, MID_Y), PointF(RIGHT - HORIZONTAL_DRAG, MID_Y), DURATION)
            Direction.LEFT -> SwipePlan(Direction.RIGHT, PointF(LEFT, MID_Y), PointF(LEFT + HORIZONTAL_DRAG, MID_Y), DURATION)
            Direction.DOWN -> SwipePlan(Direction.UP, PointF(MID_X, BOTTOM), PointF(MID_X, BOTTOM - VERTICAL_DRAG), DURATION)
            Direction.UP -> SwipePlan(Direction.DOWN, PointF(MID_X, TOP), PointF(MID_X, TOP + VERTICAL_DRAG), DURATION)
            null -> null
        }
    }

    fun reset() {
        step = 0
        waitingForViewportChange = false
        lastDirection = null
        failedAttempts = 0
    }
}
