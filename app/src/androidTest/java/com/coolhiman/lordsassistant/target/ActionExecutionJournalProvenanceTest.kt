package com.coolhiman.lordsassistant.target

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionExecutionJournalProvenanceTest {

    private fun context(): Context =
        ApplicationProvider.getApplicationContext()

    @Test
    fun inFlightEntryRoundTripPreservesCaptureSession() {
        val journal = ActionExecutionJournal(context())
        journal.clear()

        val committed = journal.markInFlight(
            ActionDispatchProvenance(
                attemptId = 41L,
                recoveryEpoch = 12L,
                startedAtMs = 9000L,
                captureSessionId = 77L
            )
        )

        assertTrue(committed)
        val entry = journal.readInFlight()
        assertEquals(41L, entry?.attemptId)
        assertEquals(12L, entry?.recoveryEpoch)
        assertTrue(entry?.recoveryEpochPersisted == true)
        assertEquals(9000L, entry?.startedAtMs)
        assertEquals(77L, entry?.captureSessionId)

        journal.clear()
    }

    @Test
    fun legacyEntryWithoutCaptureSessionRemainsReadable() {
        val prefs = context().getSharedPreferences("lm_action_journal", Context.MODE_PRIVATE)
        prefs.edit()
            .clear()
            .putBoolean("in_flight", true)
            .putLong("attempt_id", 8L)
            .putLong("recovery_epoch", 3L)
            .putLong("started_at", 100L)
            .commit()

        val entry = ActionExecutionJournal(context()).readInFlight()
        assertEquals(8L, entry?.attemptId)
        assertEquals(3L, entry?.recoveryEpoch)
        assertNull(entry?.captureSessionId)

        prefs.edit().clear().commit()
    }
}
