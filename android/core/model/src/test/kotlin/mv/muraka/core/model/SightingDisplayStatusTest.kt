package mv.muraka.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D21 made executable.
 *
 * The rule these guard is the one the whole sync design exists for: the client may say
 * "waiting to upload" and "uploading" on its own authority, and nothing else. Everything
 * else is the server's answer or an admission that we do not know yet.
 */
class SightingDisplayStatusTest {

    @Test
    fun `there is no client-assertable success state`() {
        // If someone adds one, this fails — which is the point. A local flag saying the
        // upload worked is a claim, not a fact.
        val clientAsserted = SightingDisplayStatus.entries.filter { it.isClientAsserted }
        assertEquals(
            listOf(
                SightingDisplayStatus.WAITING_TO_UPLOAD,
                SightingDisplayStatus.UPLOADING,
                SightingDisplayStatus.FAILED,
            ),
            clientAsserted,
        )
        assertTrue(
            "no client-assertable status may claim success",
            clientAsserted.none { it.label.contains("synced", ignoreCase = true) },
        )
    }

    @Test
    fun `the status vocabulary is the exact contract shared with iOS`() {
        // Changing any string here means changing ios/Muraka/Core/Model/SightingDisplayStatus.swift
        // in the same commit. scripts/check_status_vocabulary.py enforces that; this test
        // is what tells you which string moved.
        assertEquals(
            listOf(
                "Waiting to upload",
                "Uploading",
                "Checking…",
                "Photos pending",
                "Analysing",
                "Awaiting expert review",
                "Verified by an expert",
                "Not usable",
                "Could not upload",
            ),
            SightingDisplayStatus.entries.map { it.label },
        )
    }

    @Test
    fun `a queued row reads as waiting, whatever the server last said`() {
        assertEquals(
            SightingDisplayStatus.WAITING_TO_UPLOAD,
            SightingDisplayStatus.of(OutboxState.QUEUED, SightingStatus.VERIFIED),
        )
    }

    @Test
    fun `a sent row with no server answer reads as Checking, never as success`() {
        val status = SightingDisplayStatus.of(OutboxState.IN_DOUBT, serverStatus = null)
        assertEquals(SightingDisplayStatus.CHECKING, status)
        assertFalse(
            "an unconfirmed row must not display anything the server has not said",
            status.isClientAsserted,
        )
    }

    @Test
    fun `a confirmed row with no server answer still reads as Checking`() {
        // Scenario 9: killed between a successful upload and the read-back. The row is
        // confirmed locally and the app still must not claim anything.
        assertEquals(
            SightingDisplayStatus.CHECKING,
            SightingDisplayStatus.of(OutboxState.CONFIRMED, serverStatus = null),
        )
    }

    @Test
    fun `every server status maps to a status the client did not invent`() {
        SightingStatus.entries.forEach { server ->
            val display = SightingDisplayStatus.of(outboxState = null, serverStatus = server)
            assertFalse("$server must not map to a client-asserted status", display.isClientAsserted)
        }
    }

    @Test
    fun `server statuses map to the agreed words`() {
        assertEquals(
            SightingDisplayStatus.PHOTOS_PENDING,
            SightingDisplayStatus.of(null, SightingStatus.PENDING_PHOTOS),
        )
        assertEquals(SightingDisplayStatus.ANALYSING, SightingDisplayStatus.of(null, SightingStatus.PROCESSING))
        assertEquals(
            SightingDisplayStatus.AWAITING_REVIEW,
            SightingDisplayStatus.of(null, SightingStatus.AWAITING_VERIFICATION),
        )
        assertEquals(
            SightingDisplayStatus.VERIFIED_BY_EXPERT,
            SightingDisplayStatus.of(null, SightingStatus.VERIFIED),
        )
        assertEquals(SightingDisplayStatus.NOT_USABLE, SightingDisplayStatus.of(null, SightingStatus.REJECTED))
    }
}
