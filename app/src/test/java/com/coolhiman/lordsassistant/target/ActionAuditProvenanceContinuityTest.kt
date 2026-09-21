package com.coolhiman.lordsassistant.target

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V0.10 provenance continuity coverage.
 *
 * Audit events are diagnostic only, but every action lifecycle event must retain
 * the identity needed to reconstruct which attempt, recovery epoch, and live
 * capture session produced it.
 */
class ActionAuditProvenanceContinuityTest {

    private val target = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(500f, 400f)
    )

    @Test
    fun lifecycleEventsRetainAttemptEpochAndCaptureSessionAcrossPersistence() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        val events = listOf(
            ActionAuditEvent(
                timestampMs = 1_000L,
                type = ActionAuditEventType.CANDIDATE_SELECTED,
                attemptId = 21L,
                recoveryEpoch = 4L,
                captureSessionId = 101L,
                target = target
            ),
            ActionAuditEvent(
                timestampMs = 1_001L,
                type = ActionAuditEventType.ACTION_REQUESTED,
                attemptId = 21L,
                recoveryEpoch = 4L,
                captureSessionId = 101L,
                target = target
            ),
            ActionAuditEvent(
                timestampMs = 1_002L,
                type = ActionAuditEventType.ACTION_REVALIDATED,
                attemptId = 21L,
                recoveryEpoch = 4L,
                captureSessionId = 101L,
                target = target
            ),
            ActionAuditEvent(
                timestampMs = 1_003L,
                type = ActionAuditEventType.DISPATCH_SUCCEEDED,
                attemptId = 21L,
                recoveryEpoch = 4L,
                captureSessionId = 101L,
                target = target
            ),
            ActionAuditEvent(
                timestampMs = 1_004L,
                type = ActionAuditEventType.VERIFICATION_SUCCEEDED,
                attemptId = 21L,
                recoveryEpoch = 4L,
                captureSessionId = 101L,
                target = target
            )
        )

        events.forEach { assertTrue(store.append(it)) }

        val restored = store.readAll()
        assertEquals(events.size, restored.size)

        restored.forEachIndexed { index, event ->
            assertEquals(events[index].type, event.type)
            assertEquals(21L, event.attemptId)
            assertEquals(4L, event.recoveryEpoch)
            assertEquals(101L, event.captureSessionId)
            assertEquals(target, event.target)
        }

        store.clear()
    }

    @Test
    fun aNewRecoveryEpochIsDistinguishableEvenWhenCaptureSessionRemainsTheSame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 2_000L,
            type = ActionAuditEventType.UNKNOWN_ENTERED,
            attemptId = 21L,
            recoveryEpoch = 4L,
            captureSessionId = 101L,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 2_100L,
            type = ActionAuditEventType.RECOVERY_RESET,
            attemptId = 21L,
            recoveryEpoch = 5L,
            captureSessionId = 101L,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 2_101L,
            type = ActionAuditEventType.CANDIDATE_SELECTED,
            attemptId = 22L,
            recoveryEpoch = 5L,
            captureSessionId = 101L,
            target = target
        )))

        val restored = store.readAll()
        assertEquals(4L, restored[0].recoveryEpoch)
        assertEquals(5L, restored[1].recoveryEpoch)
        assertEquals(5L, restored[2].recoveryEpoch)
        assertEquals(21L, restored[0].attemptId)
        assertEquals(21L, restored[1].attemptId)
        assertEquals(22L, restored[2].attemptId)
        assertEquals(101L, restored[0].captureSessionId)
        assertEquals(101L, restored[1].captureSessionId)
        assertEquals(101L, restored[2].captureSessionId)

        store.clear()
    }
}
