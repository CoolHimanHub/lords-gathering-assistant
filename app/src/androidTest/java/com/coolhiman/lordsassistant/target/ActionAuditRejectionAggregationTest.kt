package com.coolhiman.lordsassistant.target

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import kotlin.test.assertEquals

class ActionAuditRejectionAggregationTest {
    @Test
    fun rejectionCountsAggregateOnlyCandidateRejections() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = ActionAuditLogStore(context)
        store.clear()
        store.append(ActionAuditEvent(1L, ActionAuditEventType.CANDIDATE_REJECTED, detail = "CAMERA_CONTINUITY_INVALID"))
        store.append(ActionAuditEvent(2L, ActionAuditEventType.CANDIDATE_REJECTED, detail = "CAMERA_CONTINUITY_INVALID"))
        store.append(ActionAuditEvent(3L, ActionAuditEventType.CANDIDATE_REJECTED, detail = "VALIDATION_UNSAFE"))
        store.append(ActionAuditEvent(4L, ActionAuditEventType.CANDIDATE_QUEUED, detail = "CAMERA_CONTINUITY_INVALID"))
        val counts = store.rejectionCounts()
        assertEquals(2, counts["CAMERA_CONTINUITY_INVALID"])
        assertEquals(1, counts["VALIDATION_UNSAFE"])
        assertEquals(null, counts["CANDIDATE_QUEUED"])
        store.clear()
    }
}
