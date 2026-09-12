package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V45: broader deterministic map coverage planner.
 *
 * Uses long, bounded serpentine moves so each accepted viewport covers a
 * materially larger area than V44. A move is only counted after the following
 * screenshot confirms that the game X/Y viewport changed.
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
        // Keep gestures inside the playable map area while using most of the
        // visible width/height. V44 used 500x260; V45 expands this to 760x420.
        private const val LEFT = 450f
        private const val RIGHT = 1280f
        private const val TOP = 125f
        private const val BOTTOM = 545f
        private const val MID_X = 865f
        private const val MID_Y = 335f
        private const val HORIZONTAL_DRAG = 760f
        private const val VERTICAL_DRAG = 420f
        private const val DURATION = 900L
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
        // Serpentine route: sweep a row, shift one row, reverse direction.
        // The route repeats indefinitely so long-running scans keep expanding
        // coverage instead of oscillating inside a tiny local area.
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

    /** Retry once in the opposite direction if the requested move produced no viewport change. */
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
