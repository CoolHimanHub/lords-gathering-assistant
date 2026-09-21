package com.coolhiman.lordsassistant.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

/**
 * V0.10 durable journal regression coverage.
 *
 * The execution journal is a safety barrier: a clear must be durably visible
 * before a later process restart can be treated as having no in-flight action.
 */
class ActionExecutionJournalTest {

    @Test
    fun inFlightProvenanceSurvivesReopenAndClearIsDurable() {
        val context = RuntimeEnvironment.getApplication()
        val first = ActionExecutionJournal(context)
        first.clear()

        val provenance = ActionDispatchProvenance(
            attemptId = 41L,
            recoveryEpoch = 12L,
            startedAtMs = 55_000L,
            captureSessionId = 701L
        )
        assertTrue(first.markInFlight(provenance))

        val reopened = ActionExecutionJournal(context)
        val entry = reopened.readInFlight()
        assertEquals(41L, entry?.attemptId)
        assertEquals(12L, entry?.recoveryEpoch)
        assertTrue(entry?.recoveryEpochPersisted == true)
        assertEquals(55_000L, entry?.startedAtMs)
        assertEquals(701L, entry?.captureSessionId)

        reopened.clear()

        val afterClear = ActionExecutionJournal(context)
        assertNull(afterClear.readInFlight())
        afterClear.clear()
    }

    @Test
    fun legacyEntryWithoutCaptureSessionRemainsRecoverable() {
        val context = RuntimeEnvironment.getApplication()
        val journal = ActionExecutionJournal(context)
        journal.clear()

        assertTrue(
            journal.markInFlight(
                ActionDispatchProvenance(
                    attemptId = 42L,
                    recoveryEpoch = 13L,
                    startedAtMs = 56_000L
                )
            )
        )

        val entry = ActionExecutionJournal(context).readInFlight()
        assertEquals(42L, entry?.attemptId)
        assertEquals(13L, entry?.recoveryEpoch)
        assertNull(entry?.captureSessionId)

        journal.clear()
    }
}
