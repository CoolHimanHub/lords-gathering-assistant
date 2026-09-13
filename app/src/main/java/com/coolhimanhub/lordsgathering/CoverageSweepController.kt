package com.coolhimanhub.lordsgatheringassistant

import android.graphics.PointF

/**
 * V54: OCR-independent serpentine coverage controller.
 *
 * X/Y OCR is calibration/reporting data, not a hard navigation dependency.
 * A dispatched swipe advances the route; later OCR or visual analysis can
 * validate the resulting viewport without freezing the sweep.
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

    /** Kept for compatibility with the service; movement OCR is advisory only. */
    fun onViewportObserved(changed: Boolean, authoritative: Boolean = true) {
        if (changed && authoritative) failedAttempts = 0
    }

    fun markSwipeIssued(direction: Direction) {
        lastDirection = direction
        failedAttempts = 0
        // Advance immediately. The next screenshot is responsible for sensing
        // what actually became visible; OCR failure must not stall navigation.
        step++
    }

    fun markDispatchFailure(direction: Direction) {
        lastDirection = direction
        failedAttempts++
    }

    fun needsViewportChange(): Boolean = false
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

    /** Retry the last intended direction only when gesture dispatch itself failed. */
    fun recoverySwipe(): SwipePlan? {
        val direction = lastDirection ?: return null
        if (failedAttempts !in 1..MAX_RECOVERY_ATTEMPTS) return null
        val attempt = failedAttempts
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
        failedAttempts = 0
    }

    fun reset() {
        step = 0
        lastDirection = null
        failedAttempts = 0
    }
}
