package mv.muraka.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wire strings are the contract with the Go API. A typo here is a 422 the outbox
 * cannot retry, so they are asserted literally rather than derived.
 */
class EnumsTest {

    @Test
    fun `wire values match the API contract`() {
        assertEquals(listOf("healthy", "bleached"), Condition.entries.map { it.wire })
        assertEquals(listOf("gps", "manual_pin"), LocationSource.entries.map { it.wire })
        assertEquals(
            listOf("pending_photos", "processing", "awaiting_verification", "verified", "rejected"),
            SightingStatus.entries.map { it.wire },
        )
        assertEquals(
            listOf("confirmed", "corrected", "rejected"),
            VerificationDecision.entries.map { it.wire },
        )
        assertEquals(
            listOf("blurry", "not_coral", "duplicate", "spam", "other"),
            RejectReason.entries.map { it.wire },
        )
        assertEquals(listOf("contributor", "researcher", "admin"), Role.entries.map { it.wire })
    }

    @Test
    fun `an unknown value is null rather than an exception`() {
        // A server that grows a sixth status must not crash an installed app.
        assertNull(SightingStatus.fromWire("something_new"))
        assertNull(Condition.fromWire(null))
    }

    @Test
    fun `snake_case values survive the round trip`() {
        // The two that `name.lowercase()` would silently get wrong.
        assertEquals(LocationSource.MANUAL_PIN, LocationSource.fromWire("manual_pin"))
        assertEquals(SightingStatus.PENDING_PHOTOS, SightingStatus.fromWire("pending_photos"))
    }
}
