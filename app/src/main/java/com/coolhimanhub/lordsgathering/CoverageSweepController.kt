package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V54 coverage controller.
 *
 * A swipe is only considered successful after a later authoritative viewport
 * observation proves that the map actually changed. This prevents the old
 * SAME VIEWPORT -> keep swiping loop from advancing the logical route while
 * the game camera is stationary.
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
    private var failedAttempts = 0
    private var movementPending = false
    private var unchangedObservations = 0

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
        private const val MAX_RECOVERY_ATTEMPTS = 4
        private const val UNCHANGED_BEFORE_RECOVERY = 2
    }

    fun onViewportObserved(changed: Boolean, authoritative: Boolean = true) {
        if (!authoritative) return
        if (changed) {
            failedAttempts = 0
            movementPending = false
            unchangedObservations = 0
        } else if (movementPending) {
            unchangedObservations++
        }
    }

    fun markSwipeIssued(direction: Direction) {
        lastDirection = direction
        failedAttempts = 0
        movementPending = true
        unchangedObservations = 0
        step++
    }

    /** Mark a recovery gesture without resetting the recovery-attempt counter. */
    fun markRecoverySwipeIssued(direction: Direction) {
        lastDirection = direction
        movementPending = true
        unchangedObservations = 0
    }

    fun markDispatchFailure(direction: Direction) {
        lastDirection = direction
        failedAttempts++
        movementPending = false
    }

    fun needsViewportChange(): Boolean = movementPending

    fun canIssueNextSwipe(): Boolean = !movementPending

    fun hasRecoveryReady(): Boolean =
        movementPending && unchangedObservations >= UNCHANGED_BEFORE_RECOVERY && failedAttempts < MAX_RECOVERY_ATTEMPTS

    fun unchangedCount(): Int = unchangedObservations

    fun failureCount(): Int = failedAttempts

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

    /** Retry only after repeated authoritative SAME VIEWPORT observations. */
    fun recoverySwipe(): SwipePlan? {
        val direction = lastDirection ?: return null
        if (!hasRecoveryReady()) return null
        failedAttempts++
        val attempt = failedAttempts
        val sx: Float
        val sy: Float
        when (attempt) {
            1 -> { sx = RECOVERY_RIGHT; sy = RECOVERY_BOTTOM }
            2 -> { sx = RECOVERY_LEFT; sy = RECOVERY_TOP }
            3 -> { sx = RECOVERY_RIGHT; sy = RECOVERY_TOP }
            else -> { sx = RECOVERY_LEFT; sy = RECOVERY_BOTTOM }
        }
        val drag = if (attempt >= 3) MICRO_DRAG else RECOVERY_DRAG
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
        movementPending = false
        unchangedObservations = 0
        failedAttempts = 0
    }

    fun reset() {
        step = 0
        lastDirection = null
        failedAttempts = 0
        movementPending = false
        unchangedObservations = 0
    }
}
