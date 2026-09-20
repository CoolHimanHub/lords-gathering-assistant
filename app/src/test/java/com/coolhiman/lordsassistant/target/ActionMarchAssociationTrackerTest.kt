package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.vision.MarchSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionMarchAssociationTrackerTest {
    @Test
    fun nearbyPreExistingMarchIsNotAssociated() {
        val tracker = ActionMarchAssociationTracker()
        val baseline = listOf(MarchSignal(500f, 500f, 80.0, 0.9f))
        val session = tracker.begin(ScreenPoint(500f, 500f), baseline)

        val update = tracker.update(
            session,
            listOf(MarchSignal(505f, 504f, 82.0, 0.92f)),
            1_000L
        )

        assertFalse(update.ownMarchConfirmed)
        assertTrue(update.session.lastSignal == null)
    }

    @Test
    fun newSignalNeedsTemporalMotionBeforeConfirmation() {
        val tracker = ActionMarchAssociationTracker(
            confirmationFrames = 2,
            minDisplacementPx = 5f
        )
        val session = tracker.begin(
            ScreenPoint(500f, 500f),
            emptyList()
        )

        val first = tracker.update(
            session,
            listOf(MarchSignal(510f, 505f, 80.0, 0.9f)),
            1_000L
        )
        assertFalse(first.ownMarchConfirmed)

        val second = tracker.update(
            first.session,
            listOf(MarchSignal(522f, 511f, 82.0, 0.92f)),
            1_200L
        )
        assertTrue(second.ownMarchConfirmed)
    }

    @Test
    fun stationaryNewSignalDoesNotConfirm() {
        val tracker = ActionMarchAssociationTracker(
            confirmationFrames = 2,
            minDisplacementPx = 5f
        )
        val session = tracker.begin(ScreenPoint(500f, 500f), emptyList())

        val first = tracker.update(
            session,
            listOf(MarchSignal(510f, 505f, 80.0, 0.9f)),
            1_000L
        )
        val second = tracker.update(
            first.session,
            listOf(MarchSignal(511f, 506f, 80.0, 0.9f)),
            1_200L
        )

        assertFalse(second.ownMarchConfirmed)
    }

    @Test
    fun inconsistentTrajectoryDoesNotConfirm() {
        val tracker = ActionMarchAssociationTracker(
            confirmationFrames = 3,
            minDisplacementPx = 5f,
            minTrajectoryCosine = 0.8f
        )
        val session = tracker.begin(ScreenPoint(500f, 500f), emptyList())

        val first = tracker.update(
            session,
            listOf(MarchSignal(510f, 505f, 80.0, 0.9f)),
            1_000L
        )
        val second = tracker.update(
            first.session,
            listOf(MarchSignal(522f, 511f, 82.0, 0.92f)),
            1_200L
        )
        val third = tracker.update(
            second.session,
            listOf(MarchSignal(512f, 525f, 84.0, 0.91f)),
            1_400L
        )

        assertFalse(third.ownMarchConfirmed)
    }
}

    @Test
    fun tangentialMovementDoesNotConfirmOwnMarch() {
        val tracker = ActionMarchAssociationTracker(
            confirmationFrames = 2,
            minDisplacementPx = 5f,
            minRadialDeparturePx = 3f
        )
        val session = tracker.begin(ScreenPoint(500f, 500f), emptyList())

        val first = tracker.update(
            session,
            listOf(MarchSignal(530f, 500f, 80.0, 0.9f)),
            1_000L
        )
        val second = tracker.update(
            first.session,
            listOf(MarchSignal(530f, 510f, 82.0, 0.92f)),
            1_200L
        )

        assertFalse(second.ownMarchConfirmed)
    }

    @Test
    fun marchStartingTooFarFromActionPointIsIgnored() {
        val tracker = ActionMarchAssociationTracker(startRadiusPx = 120f)
        val session = tracker.begin(ScreenPoint(500f, 500f), emptyList())

        val update = tracker.update(
            session,
            listOf(MarchSignal(650f, 500f, 80.0, 0.9f)),
            1_000L
        )

        assertFalse(update.ownMarchConfirmed)
        assertTrue(update.session.lastSignal == null)
    }

    @Test
    fun firstMovementTowardActionPointDoesNotConfirm() {
        val tracker = ActionMarchAssociationTracker(
            confirmationFrames = 2,
            minDisplacementPx = 5f,
            minDepartureCosine = 0.35f
        )
        val session = tracker.begin(ScreenPoint(500f, 500f), emptyList())

        val first = tracker.update(
            session,
            listOf(MarchSignal(530f, 500f, 80.0, 0.9f)),
            1_000L
        )
        val second = tracker.update(
            first.session,
            listOf(MarchSignal(520f, 500f, 82.0, 0.92f)),
            1_200L
        )

        assertFalse(second.ownMarchConfirmed)
    }
