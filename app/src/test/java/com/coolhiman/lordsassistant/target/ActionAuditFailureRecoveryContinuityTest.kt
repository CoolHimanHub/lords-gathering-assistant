package com.coolhiman.lordsassistant.target

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * V0.10 failure/recovery audit coverage.
 *
 * Verifies that terminal and recovery lifecycle outcomes remain reconstructable
 * from the persisted audit trail with attempt, epoch, session and target
 * provenance intact.
 */
@RunWith(RobolectricTestRunner::class)
class ActionAuditFailureRecoveryContinuityTest {

    private val target = ActionTargetSnapshot(
        coordinate = WorldCoordinate(355, 167, 511),
        kind = TargetKind.RESOURCE,
        level = 3,
        actionKind = ActionKind.GATHER,
        point = ScreenPoint(500f, 400f)
    )

    @Test
    fun failedDispatchAndVerificationTimeoutRetainDistinctProvenance() {
        val context = RuntimeEnvironment.getApplication()
        val store = ActionAuditLogStore(context)
        store.clear()

        val sessionId = 202L
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 10_000L,
            type = ActionAuditEventType.ACTION_REQUESTED,
            attemptId = 31L,
            recoveryEpoch = 7L,
            captureSessionId = sessionId,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 10_001L,
            type = ActionAuditEventType.DISPATCH_FAILED,
            attemptId = 31L,
            recoveryEpoch = 7L,
            captureSessionId = sessionId,
            target = target,
            detail = "dispatch callback rejected"
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 11_000L,
            type = ActionAuditEventType.ACTION_REQUESTED,
            attemptId = 32L,
            recoveryEpoch = 8L,
            captureSessionId = sessionId,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 11_001L,
            type = ActionAuditEventType.VERIFICATION_TIMEOUT,
            attemptId = 32L,
            recoveryEpoch = 8L,
            captureSessionId = sessionId,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 11_002L,
            type = ActionAuditEventType.UNKNOWN_ENTERED,
            attemptId = 32L,
            recoveryEpoch = 8L,
            captureSessionId = sessionId,
            target = target
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 11_003L,
            type = ActionAuditEventType.RECOVERY_RESET,
            attemptId = 32L,
            recoveryEpoch = 9L,
            captureSessionId = sessionId,
            target = target
        )))

        val restored = store.readAll()
        assertEquals(6, restored.size)
        assertEquals(ActionAuditEventType.DISPATCH_FAILED, restored[1].type)
        assertEquals(31L, restored[1].attemptId)
        assertEquals(7L, restored[1].recoveryEpoch)
        assertEquals(ActionAuditEventType.VERIFICATION_TIMEOUT, restored[3].type)
        assertEquals(32L, restored[3].attemptId)
        assertEquals(8L, restored[3].recoveryEpoch)
        assertEquals(ActionAuditEventType.UNKNOWN_ENTERED, restored[4].type)
        assertEquals(ActionAuditEventType.RECOVERY_RESET, restored[5].type)
        assertEquals(9L, restored[5].recoveryEpoch)

        val sessionEvents = restored.filter { it.captureSessionId == sessionId }
        assertEquals(6, sessionEvents.size)
        sessionEvents.forEach { assertEquals(target, it.target) }

        store.clear()
    }

    @Test
    fun rejectionCountsRemainScopedToCaptureSession() {        val context = RuntimeEnvironment.getApplication()
        val store = ActionAuditLogStore(context)
        store.clear()

        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 20_000L,
            type = ActionAuditEventType.CANDIDATE_REJECTED,
            captureSessionId = 301L,
            detail = "CAMERA_CONTINUITY_INVALID"
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 20_001L,
            type = ActionAuditEventType.CANDIDATE_REJECTED,
            captureSessionId = 301L,
            detail = "CAMERA_CONTINUITY_INVALID"
        )))
        assertTrue(store.append(ActionAuditEvent(
            timestampMs = 20_002L,
            type = ActionAuditEventType.CANDIDATE_REJECTED,
            captureSessionId = 302L,
            detail = "VALIDATION_UNSAFE"
        )))

        assertEquals(
            mapOf("CAMERA_CONTINUITY_INVALID" to 2),
            store.rejectionCountsForSession(301L)
        )
        assertEquals(
            mapOf("VALIDATION_UNSAFE" to 1),
            store.rejectionCountsForSession(302L)
        )

        store.clear()
    }
}
