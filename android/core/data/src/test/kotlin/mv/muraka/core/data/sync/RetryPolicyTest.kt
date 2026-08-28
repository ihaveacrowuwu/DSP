package mv.muraka.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * `min(2^attempts, 300) seconds +/- 20%`, from `mobile-shared/sync-protocol.md`.
 *
 * The jitter is tested against a seeded [Random] rather than by sampling, so a failure
 * names a number instead of being flaky one run in fifty.
 */
class RetryPolicyTest {

    /** Jitter of exactly 1.0, so the base curve can be asserted on its own. */
    private val noJitter = object : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(from: Double, until: Double): Double = 0.0
    }

    @Test
    fun `the delay doubles with each attempt`() {
        assertEquals(1_000, RetryPolicy.delayMillis(0, noJitter))
        assertEquals(2_000, RetryPolicy.delayMillis(1, noJitter))
        assertEquals(4_000, RetryPolicy.delayMillis(2, noJitter))
        assertEquals(64_000, RetryPolicy.delayMillis(6, noJitter))
    }

    @Test
    fun `the delay is capped at five minutes`() {
        // Without the cap, attempt 8 would be over four minutes and attempt 20 would be
        // twelve days - a queue that never drains and a contributor who never finds out.
        assertEquals(300_000, RetryPolicy.delayMillis(20, noJitter))
        assertEquals(300_000, RetryPolicy.delayMillis(Int.MAX_VALUE, noJitter))
    }

    @Test
    fun `jitter stays within twenty percent, in both directions`() {
        // A boat full of divers who all lost signal at the same jetty must not reconnect
        // and retry in lockstep. Sampled across a fixed seed so this cannot flake.
        val random = Random(seed = 20_260_821)
        repeat(500) {
            val delay = RetryPolicy.delayMillis(4, random)
            assertTrue("$delay below the jitter floor", delay >= 12_800)
            assertTrue("$delay above the jitter ceiling", delay <= 19_200)
        }
    }

    @Test
    fun `a negative attempt count cannot produce a negative delay`() {
        assertTrue(RetryPolicy.delayMillis(-3, noJitter) >= 0)
    }

    @Test
    fun `eight attempts is the give-up threshold`() {
        assertFalse(RetryPolicy.isExhausted(7))
        assertTrue(RetryPolicy.isExhausted(RetryPolicy.MAX_ATTEMPTS))
        assertTrue(RetryPolicy.isExhausted(99))
    }

    @Test
    fun `the next attempt is scheduled forward from now`() {
        val now = 1_760_000_000_000
        assertEquals(now + 4_000, RetryPolicy.nextAttemptAt(now, attempts = 2, random = noJitter))
    }
}
