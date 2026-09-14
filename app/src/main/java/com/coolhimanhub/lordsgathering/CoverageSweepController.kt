package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V55.1: verified serpentine coverage controller.
 *
 * A swipe is only an intent. Route progress is committed only after a fresh,
 * authoritative X/Y observation proves that the viewport changed.
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
    private var lastDirection: Direction? = null
    private var pendingDirection: Direction? = null
    private var unchangedAuthoritativeFrames = 0
    private var dispatchFailures = 0

    companion object {
        private const val LEFT = 700f
        private const val RIGHT = 1320f
        private const val TOP = 150f
        private const val BOTTOM = 500f
        private const val MID_X = 1050f
        private const val MID_Y = 520f
        private const val HORIZONTAL_DRAG = 560f
        private const val VERTICAL_DRAG = 300f
        private const val NORMAL_DURATION = 620L
        private const val RECOVERY_LEFT = 720f
        private const val RECOVERY_RIGHT = 1280f
        private const val RECOVERY_TOP = 180f
        private const val RECOVERY_BOTTOM = 490f
        private const val RECOVERY_DRAG = 230f
        private const val MICRO_DRAG = 170f
        private const val RECOVERY_DURATION = 500L
        private const val MAX_RECOVERY_ATTEMPTS = 6
    }

    /**
     * Accept viewport evidence. Only an authoritative changed viewport commits
     * the previously issued route step. Identical/fallback observations never
     * advance coverage.
     */
    fun onViewportObserved(changed: Boolean, authoritative: Boolean = true) {
        if (!authoritative) return
        if (changed) {
            if (pendingDirection != null) {
                step++
                lastDirection = pendingDirection
                pendingDirection = null
            }
            unchangedAuthoritativeFrames = 0
            dispatchFailures = 0
        } else {
            unchangedAuthoritativeFrames++
        }
    }

    /** Record an issued gesture without advancing the route. */
    fun markSwipeIssued(direction: Direction) {
        lastDirection = direction
        pendingDirection = direction
        dispatchFailures = 0
    }

    /** Record a dispatch failure; it never advances the route. */
    fun markDispatchFailure(direction: Direction) {
        lastDirection = direction
        pendingDirection = direction
        dispatchFailures++
    }

    fun needsViewportChange(): Boolean = pendingDirection != null
    fun failureCount(): Int = dispatchFailures
    fun unchangedCount(): Int = unchangedAuthoritativeFrames
    fun isAwaitingViewportChange(): Boolean = pendingDirection != null

    fun nextSwipe(): SwipePlan {
        val routeStep = step
        val row = routeStep / 2
        val evenRow = row % 2 == 0
        val direction = when (routeStep % 4) {
            0 -> if (evenRow) Direction.RIGHT else Direction.LEFT
            1 -> Direction.DOWN
            2 -> if (evenRow) Direction.LEFT else Direction.RIGHT
            else -> Direction.DOWN
        }
        return normalSwipe(direction)
    }

    private fun normalSwipe(direction: Direction): SwipePlan = when (direction) {
        Direction.RIGHT -> SwipePlan(direction, PointF(LEFT, MID_Y), PointF(LEFT + HORIZONTAL_DRAG, MID_Y), NORMAL_DURATION)
        Direction.LEFT -> SwipePlan(direction, PointF(RIGHT, MID_Y), PointF(RIGHT - HORIZONTAL_DRAG, MID_Y), NORMAL_DURATION)
        Direction.DOWN -> SwipePlan(direction, PointF(MID_X, TOP), PointF(MID_X, TOP + VERTICAL_DRAG), NORMAL_DURATION)
        Direction.UP -> SwipePlan(direction, PointF(MID_X, BOTTOM), PointF(MID_X, BOTTOM - VERTICAL_DRAG), NORMAL_DURATION)
    }

    /** Retry the last intended direction only after actual gesture dispatch failure. */
    fun recoverySwipe(): SwipePlan? {
        val direction = lastDirection ?: return null
        if (dispatchFailures !in 1..MAX_RECOVERY_ATTEMPTS) return null
        val attempt = dispatchFailures
        val sx: Float
        val sy: Float
        when (attempt) {
            1 -> { sx = RECOVERY_RIGHT; sy = RECOVERY_BOTTOM }
            2 -> { sx = RECOVERY_LEFT; sy = RECOVERY_TOP }
            3 -> { sx = RECOVERY_RIGHT; sy = RECOVERY_TOP }
            4 -> { sx = RECOVERY_LEFT; sy = RECOVERY_BOTTOM }
            5 -> { sx = MID_X; sy = RECOVERY_TOP }
            else -> { sx = MID_X; sy = RECOVERY_BOTTOM }
        }
        val drag = if (attempt >= 5) MICRO_DRAG else RECOVERY_DRAG
        val ex = when (direction) {
            Direction.RIGHT -> sx + drag
            Direction.LEFT -> sx - drag
            Direction.DOWN, Direction.UP -> sx
        }
        val ey = when (direction) {
            Direction.DOWN -> sy + drag
            Direction.UP -> sy - drag
            Direction.RIGHT, Direction.LEFT -> sy
        }
        return SwipePlan(direction, PointF(sx, sy), PointF(ex, ey), RECOVERY_DURATION)
    }

    fun releaseStall() {
        pendingDirection = null
        unchangedAuthoritativeFrames = 0
        dispatchFailures = 0
    }

    fun reset() {
        step = 0
        lastDirection = null
        pendingDirection = null
        unchangedAuthoritativeFrames = 0
        dispatchFailures = 0
    }
}
