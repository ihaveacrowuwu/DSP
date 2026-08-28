package mv.muraka.core.common

import java.security.SecureRandom
import java.util.UUID

/**
 * UUIDv7 - a time-ordered UUID, generated on the device at capture time.
 *
 * This is the single rule that makes the whole offline protocol simple: because the
 * client owns the id, the server can upsert on it, so a retry after a timeout, after a
 * killed process, or after a lost response creates nothing new. The client never has to
 * ask "did that one get through?" - it can just send again.
 *
 * v7 rather than v4 because it is time-ordered, which keeps PostgreSQL's index locality
 * good and makes the outbox naturally chronological - the queue sorts by id.
 *
 * Layout (RFC 9562 section 5.7), 128 bits:
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 * |                       unix_ts_ms (48 bits)                    |
 * +-------------------------------+-------+-----------------------+
 * |        unix_ts_ms (cont.)     |  ver  |    rand_a (12 bits)   |
 * +-------------------------------+-------+-----------------------+
 * |var|                    rand_b (62 bits)                       |
 * +---------------------------------------------------------------+
 * ```
 *
 * `rand_a` is used as a **monotonic counter** within a millisecond rather than as random
 * bits (RFC 9562 "method 1"). Without that, five photographs captured in the same
 * millisecond get ids in random order, and the researcher's queue then shows them in an
 * order that is not the order they were taken - which `sync-protocol.md` requires it to
 * reflect.
 */
object Uuid7 {
    private val random = SecureRandom()

    private const val MILLIS_BITS = 48
    private const val COUNTER_BITS = 12
    private const val COUNTER_MAX = (1 shl COUNTER_BITS) - 1
    private const val VERSION = 7L

    private var lastMillis = -1L
    private var counter = 0

    /**
     * A new id. Thread-safe, and monotonically increasing even when called repeatedly
     * inside one millisecond.
     */
    @Synchronized
    fun generate(millis: Long = System.currentTimeMillis()): UUID {
        // A clock that jumps backwards must not produce ids that sort before ones already
        // in the outbox: hold the previous millisecond instead. The drift is bounded by
        // how far back the clock went and disappears as soon as real time catches up.
        val stamp = if (millis > lastMillis) millis else lastMillis

        counter = when {
            stamp != lastMillis -> random.nextInt(COUNTER_MAX / 2)
            counter < COUNTER_MAX -> counter + 1
            // Exhausting 4096 ids inside one millisecond is not reachable from a capture
            // flow, but rolling over silently would break ordering, so borrow from the
            // next millisecond instead.
            else -> {
                lastMillis = stamp + 1
                return generate(stamp + 1)
            }
        }
        lastMillis = stamp

        val high = (stamp and ((1L shl MILLIS_BITS) - 1) shl 16) or
            (VERSION shl 12) or
            counter.toLong()

        // Variant bits `10` in the two most significant bits of rand_b.
        val low = (random.nextLong() and 0x3FFF_FFFF_FFFF_FFFFL) or (1L shl 63)

        return UUID(high, low)
    }

    /** A new id as the lowercase hyphenated string the API expects. */
    fun generateString(millis: Long = System.currentTimeMillis()): String = generate(millis).toString()

    /** The millisecond a v7 id was generated in. Used by tests and by queue ordering. */
    fun timestampMillis(uuid: UUID): Long = uuid.mostSignificantBits ushr 16

    /** True when [value] parses as a UUID of any version. The server requires no more. */
    fun isValid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
}
