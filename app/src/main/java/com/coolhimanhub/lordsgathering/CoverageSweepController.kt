package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V48: resilient serpentine coverage controller.
 *
 * The route advances only after movement is confirmed by a fresh viewport
 * observation. If a gesture stalls, recovery always preserves the intended
 * direction. A stalled step is never silently converted into the next route
 * step.
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
        private const val LEFT = 520f
        private const val RIGHT = 1250f
        private const val TOP = 135f
        private const val BOTTOM = 505f
        private const val MID_X = 885f
        private const val MID_Y = 320f
        private const val HORIZONTAL_DRAG = 650f
        private const val VERTICAL_DRAG = 315f
        private const val NORMAL_DURATION = 620L
        private const val RECOVERY_LEFT = 700f
        private const val RECOVERY_RIGHT = 1110f
        private const val RECOVERY_TOP = 175f
        private const val RECOVERY_BOTTOM = 465f
        private const val RECOVERY_DRAG = 270f
        private const val MICRO_DRAG = 190f
        private const val RECOVERY_DURATION = 500L
        private const val MAX_RECOVERY_ATTEMPTS = 6
    }

    fun onViewportObserved(changed: Boolean, authoritative: Boolean = true) {
        if (!authoritative) return
        if (changed) {
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
        waitingForViewportChange = true
        lastDirection = direction
        failedAttempts++
    }

    fun needsViewportChange(): Boolean = waitingForViewportChange
    fun failureCount(): Int = failedAttempts

    fun nextSwipe(): SwipePlan {
        // Never advance the serpentine route while the previous step is still
        // awaiting movement confirmation.
        if (waitingForViewportChange && lastDirection != null) {
            return normalSwipe(lastDirection!!)
        }
        val row = step / 2
        val evenRow = row % 2 == 0
        val direction = when (step % 4) {
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

    /** Retry the SAME intended direction when the viewport did not move. */
    fun recoverySwipe(): SwipePlan? {
        if (!waitingForViewportChange) return null
        val direction = lastDirection ?: return null
        if (failedAttempts !in 1..MAX_RECOVERY_ATTEMPTS) return null
        val attempt = failedAttempts
        val (sx, sy) = when (attempt) {
            1 -> RECOVERY_RIGHT to RECOVERY_BOTTOM
            2 -> RECOVERY_LEFT to RECOVERY_TOP
            3 -> RECOVERY_RIGHT to RECOVERY_TOP
            4 -> RECOVERY_LEFT to RECOVERY_BOTTOM
            5 -> MID_X to RECOVERY_TOP
            else -> MID_X to RECOVERY_BOTTOM
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
        waitingForViewportChange = false
        failedAttempts = 0
    }

    fun reset() {
        step = 0
        waitingForViewportChange = false
        lastDirection = null
        failedAttempts = 0
    }
}
