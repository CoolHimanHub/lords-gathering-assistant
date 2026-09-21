package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.vision.MarchSignal
import kotlin.math.hypot

/**
 * Associates a newly appearing march trajectory with the action interaction
 * point without treating proximity alone as proof of ownership.
 *
 * The caller supplies the march signals observed immediately before dispatch
 * and then feeds subsequent frames into update(). A candidate must:
 * 1) be new relative to the pre-action signal set,
 * 2) appear close to the selected interaction point,
 * 3) persist across the required number of post-action frames, and
 * 4) move with a consistent trajectory.
 *
 * The session retains the observed trajectory so post-action evidence can be
 * tied back to the exact action session rather than a standalone boolean.
 */
class ActionMarchAssociationTracker(
    private val startRadiusPx: Float = 120f,
    private val baselineMatchRadiusPx: Float = 8f,
    private val continuationRadiusPx: Float = 70f,
    private val minDisplacementPx: Float = 5f,
    private val minTrajectoryCosine: Float = 0.55f,
    private val minDepartureCosine: Float = 0.35f,
    private val minRadialDeparturePx: Float = 3f,
    private val confirmationFrames: Int = 2,
    private val maxGapMs: Long = 1_500L,
    private val trajectoryMatchRadiusPx: Float = 45f
) {
    data class Session(
        val actionPoint: ScreenPoint,
        val baseline: List<MarchSignal>,
        val lastSignal: MarchSignal? = null,
        val lastDisplacementX: Float? = null,
        val lastDisplacementY: Float? = null,
        val confirmedFrames: Int = 0,
        val lastSeenMs: Long = 0L,
        val confirmed: Boolean = false,
        val trajectory: List<MarchSignal> = emptyList()
    )

    data class Update(
        val session: Session,
        val ownMarchConfirmed: Boolean
    )

    fun begin(actionPoint: ScreenPoint, baseline: List<MarchSignal>): Session =
        Session(actionPoint = actionPoint, baseline = baseline)

    fun update(session: Session, signals: List<MarchSignal>, nowMs: Long): Update {
        if (session.confirmed) return Update(session, true)
        if (session.lastSeenMs != 0L && nowMs - session.lastSeenMs > maxGapMs) {
            return Update(
                session.copy(
                    lastSignal = null,
                    lastDisplacementX = null,
                    lastDisplacementY = null,
                    confirmedFrames = 0,
                    lastSeenMs = 0L,
                    trajectory = emptyList()
                ),
                false
            )
        }

        val candidate = if (session.lastSignal == null) {
            signals
                .asSequence()
                .filter { distance(it, session.actionPoint) <= startRadiusPx }
                .filter { current -> session.baseline.none { distance(it = current, signal = it) <= baselineMatchRadiusPx } }
                .maxByOrNull { it.confidence }
        } else {
            val previous = session.lastSignal
            val predictedX = previous.x + (session.lastDisplacementX ?: 0f)
            val predictedY = previous.y + (session.lastDisplacementY ?: 0f)
            val predicted = ScreenPoint(predictedX, predictedY)
            signals
                .asSequence()
                .filter { distance(it, previous) <= continuationRadiusPx }
                .filter { distance(it, predicted) <= trajectoryMatchRadiusPx }
                .minWithOrNull(
                    compareBy<MarchSignal> { distance(it, predicted) }
                        .thenByDescending { it.confidence }
                )
        }

        if (candidate == null) return Update(session, false)

        val previous = session.lastSignal
        if (previous == null) {
            val next = session.copy(
                lastSignal = candidate,
                confirmedFrames = 1,
                lastSeenMs = nowMs,
                trajectory = listOf(candidate)
            )
            return Update(next, false)
        }

        val dx = candidate.x - previous.x
        val dy = candidate.y - previous.y
        val displacement = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (displacement < minDisplacementPx) {
            return Update(
                session.copy(
                    lastSignal = candidate,
                    lastSeenMs = nowMs,
                    confirmedFrames = 0
                ),
                false
            )
        }

        if (session.lastDisplacementX == null && session.lastDisplacementY == null) {
            val fromActionX = previous.x - session.actionPoint.x
            val fromActionY = previous.y - session.actionPoint.y
            val fromActionLength = hypot(fromActionX.toDouble(), fromActionY.toDouble()).toFloat()
            if (fromActionLength > 0.001f) {
                val departureCosine = (fromActionX * dx + fromActionY * dy) /
                    (fromActionLength * displacement).coerceAtLeast(0.001f)
                val previousRadius = fromActionLength
                val nextRadius = hypot(
                    (candidate.x - session.actionPoint.x).toDouble(),
                    (candidate.y - session.actionPoint.y).toDouble()
                ).toFloat()
                val radialDeparture = nextRadius - previousRadius
                if (departureCosine < minDepartureCosine || radialDeparture < minRadialDeparturePx) {
                    return Update(
                        session.copy(
                            lastSignal = candidate,
                            lastDisplacementX = null,
                            lastDisplacementY = null,
                            confirmedFrames = 0,
                            lastSeenMs = nowMs
                        ),
                        false
                    )
                }
            }
        }

        val priorDx = session.lastDisplacementX
        val priorDy = session.lastDisplacementY
        val consistent = if (priorDx == null || priorDy == null) {
            true
        } else {
            val priorLength = hypot(priorDx.toDouble(), priorDy.toDouble()).toFloat()
            val currentLength = displacement
            val cosine = (priorDx * dx + priorDy * dy) /
                (priorLength * currentLength).coerceAtLeast(0.001f)
            cosine >= minTrajectoryCosine
        }

        val nextFrames = if (consistent) session.confirmedFrames + 1 else 1
        val confirmed = nextFrames >= confirmationFrames

        val next = session.copy(
            lastSignal = candidate,
            lastDisplacementX = dx,
            lastDisplacementY = dy,
            confirmedFrames = nextFrames,
            lastSeenMs = nowMs,
            confirmed = confirmed,
            trajectory = (session.trajectory + candidate).takeLast(16)
        )
        return Update(next, confirmed)
    }

    private fun distance(signal: MarchSignal, point: ScreenPoint): Float =
        hypot(
            (signal.x - point.x).toDouble(),
            (signal.y - point.y).toDouble()
        ).toFloat()

    private fun distance(it: MarchSignal, signal: MarchSignal): Float =
        hypot(
            (it.x - signal.x).toDouble(),
            (it.y - signal.y).toDouble()
        ).toFloat()
}
