package com.coolhiman.lordsassistant.target

import com.coolhiman.lordsassistant.model.ScreenPoint
import com.coolhiman.lordsassistant.model.TargetKind
import com.coolhiman.lordsassistant.model.WorldCoordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class ActionAuditLogStoreTest {

    @Test
    fun eventRoundTripPreservesDurableProvenance() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        val event = ActionAuditEvent(
            timestampMs = 1234L,
            type = ActionAuditEventType.DISPATCH_BARRIER_OPENED,
            attemptId = 17L,
            recoveryEpoch = 9L,
            target = ActionTargetSnapshot(
                coordinate = WorldCoordinate(1, 167, 511),
                kind = TargetKind.RESOURCE,
                level = 3,
                actionKind = ActionKind.GATHER,
                point = ScreenPoint(900f, 600f)
            ),
            detail = "journal committed"
        )

        assertEquals(true, store.append(event))
        val restored = store.readAll().single()

        assertEquals(event.timestampMs, restored.timestampMs)
        assertEquals(event.type, restored.type)
        assertEquals(event.attemptId, restored.attemptId)
        assertEquals(event.recoveryEpoch, restored.recoveryEpoch)
        assertEquals(event.target, restored.target)
        assertEquals(event.detail, restored.detail)

        store.clear()
    }

    @Test
    @Test
    @Test
    fun lifecycleFailureTypesArePersistable() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        listOf(
            ActionAuditEventType.REVALIDATION_FAILED,
            ActionAuditEventType.ATTEMPT_ID_PERSISTENCE_FAILED,
            ActionAuditEventType.UNKNOWN_ENTERED,
            ActionAuditEventType.VERIFICATION_TIMEOUT
        ).forEachIndexed { index, type ->
            assertEquals(
                true,
                store.append(
                    ActionAuditEvent(
                        timestampMs = index.toLong(),
                        type = type,
                        detail = type.name
                    )
                )
            )
        }

        assertEquals(
            listOf(
                ActionAuditEventType.REVALIDATION_FAILED,
                ActionAuditEventType.ATTEMPT_ID_PERSISTENCE_FAILED,
                ActionAuditEventType.UNKNOWN_ENTERED,
                ActionAuditEventType.VERIFICATION_TIMEOUT
            ),
            store.readAll().map { it.type }
        )

        store.clear()
    }

    @Test
    fun appendIfChangedDoesNotSpamIdenticalEvents() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        val event = ActionAuditEvent(
            timestampMs = 1L,
            type = ActionAuditEventType.SCHEDULER_BLOCKED,
            recoveryEpoch = 4L,
            detail = "ACTION_RECOVERY_BLOCKED"
        )

        assertEquals(true, store.appendIfChanged(event))
        assertEquals(true, store.appendIfChanged(event.copy(timestampMs = 2L)))
        assertEquals(1, store.readAll().size)

        store.clear()
    }

    @Test
    fun logIsBoundedToLatestEvents() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val store = ActionAuditLogStore(context)
        store.clear()

        repeat(520) { index ->
            store.append(
                ActionAuditEvent(
                    timestampMs = index.toLong(),
                    type = ActionAuditEventType.CANDIDATE_QUEUED
                )
            )
        }

        val events = store.readAll()
        assertEquals(500, events.size)
        assertEquals(20L, events.first().timestampMs)
        assertEquals(519L, events.last().timestampMs)

        store.clear()
    }
}
