package mv.muraka.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The id is the idempotency key for the entire offline protocol, so these assert the
 * properties the protocol actually relies on — not merely that a UUID comes back.
 */
class Uuid7Test {

    @Test
    fun `carries version 7 and the RFC 9562 variant`() {
        val id = Uuid7.generate()
        assertEquals(7, id.version())
        // Variant 2 is Java's name for the RFC 4122/9562 `10x` variant.
        assertEquals(2, id.variant())
    }

    @Test
    fun `encodes the millisecond it was generated in`() {
        val millis = 1_760_000_000_000L
        assertEquals(millis, Uuid7.timestampMillis(Uuid7.generate(millis)))
    }

    @Test
    fun `ids sort by creation time, which is what makes the queue chronological`() {
        val early = Uuid7.generateString(1_700_000_000_000L)
        val later = Uuid7.generateString(1_700_000_001_000L)
        assertTrue("$early should sort before $later", early < later)
    }

    @Test
    fun `ids from the SAME millisecond still sort by creation order`() {
        // The property that matters: five photographs captured in one millisecond must
        // reach the researcher in the order they were taken. Random rand_a would fail
        // this roughly half the time, which is why the counter exists.
        val millis = 1_700_000_000_000L
        val ids = (1..64).map { Uuid7.generateString(millis) }
        assertEquals(ids, ids.sorted())
    }

    @Test
    fun `a clock that jumps backwards does not produce ids that sort before earlier ones`() {
        val first = Uuid7.generateString(1_700_000_010_000L)
        val afterJumpBack = Uuid7.generateString(1_700_000_000_000L)
        assertTrue("an id minted after a backwards clock jump must not sort earlier", afterJumpBack > first)
    }

    @Test
    fun `ids are unique across a burst`() {
        val ids = (1..5_000).map { Uuid7.generateString() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `generated ids round-trip through the string form the API expects`() {
        val id = Uuid7.generateString()
        assertEquals(id, UUID.fromString(id).toString())
        assertTrue(Uuid7.isValid(id))
    }

    @Test
    fun `rejects a string that is not a UUID`() {
        assertNotEquals(true, Uuid7.isValid("not-a-uuid"))
    }
}
