package mv.muraka.core.data.sync

import kotlin.math.pow
import kotlin.random.Random

/**
 * Exponential backoff with jitter, capped - `min(2^attempts, 300) seconds +/- 20%`, from
 * `mobile-shared/sync-protocol.md`.
 *
 * The jitter matters more than it looks. Without it, a boat full of divers whose phones
 * all lost signal at the same jetty reconnect and retry in lockstep, and the server sees
 * every client's backoff curve aligned. With it they spread out.
 *
 * After [MAX_ATTEMPTS] the row is marked failed and surfaced with a Retry action rather
 * than being retried forever: a contributor deserves to know something is stuck, and an
 * invisible infinite retry is how a sighting is quietly never delivered.
 */
object RetryPolicy {

    /** Eight attempts spans roughly nine minutes of real backoff before giving up. */
    const val MAX_ATTEMPTS = 8

    private const val CAP_SECONDS = 300.0
    private const val JITTER_FRACTION = 0.2

    /** Milliseconds to wait before attempt number [attempts] + 1. */
    fun delayMillis(attempts: Int, random: Random = Random.Default): Long {
        val base = minOf(2.0.pow(attempts.coerceAtLeast(0)), CAP_SECONDS)
        val jitter = 1.0 + random.nextDouble(-JITTER_FRACTION, JITTER_FRACTION)
        return (base * jitter * MILLIS_PER_SECOND).toLong().coerceAtLeast(0)
    }

    /** Epoch millis at which the next attempt is permitted. */
    fun nextAttemptAt(now: Long, attempts: Int, random: Random = Random.Default): Long =
        now + delayMillis(attempts, random)

    /** Whether the row has run out of attempts and needs the contributor. */
    fun isExhausted(attempts: Int): Boolean = attempts >= MAX_ATTEMPTS

    private const val MILLIS_PER_SECOND = 1000
}
