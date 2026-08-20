package mv.muraka.core.common

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Translates device time into server time.
 *
 * A sighting whose `capturedAt` is in the future is rejected `422`, which is terminal —
 * so a phone with a wrong clock loses the sighting it just captured. The server tolerates
 * 24 hours of skew, but a device left in the wrong time zone, or one whose clock was
 * never set after a flat battery, can be further out than that.
 *
 * The fix is not to guess. Every HTTP response carries a `Date` header, so the client
 * learns the offset for free on the first successful request.
 *
 * **The correction is a shift, not a clamp.** If the device clock reads 24 hours fast,
 * a photograph taken one real hour ago is stamped `deviceNow - 1h`, which is 23 hours in
 * the server's future. Clamping that to server-now would record it as having been taken
 * *right now* — an hour late, and wrong. Shifting it by the learned offset recovers the
 * moment it was actually taken. The clamp survives only as a final guard against a
 * timestamp that is somehow in the device's own future.
 *
 * The translation happens at **upload** time, not capture time: a sighting captured
 * offline is stored with the raw device instant, and by the time it can be sent the
 * offset is known, because knowing it requires a response. That is why the outbox stores
 * device time and this is called by the uploader.
 *
 * The offset is deliberately **not** persisted. A stale offset from last week is worse
 * than none: the device clock may have been corrected since.
 */
@Singleton
class ServerClock @Inject constructor() {

    /** `serverNow - deviceNow`, in milliseconds. [NO_OFFSET] until a response arrives. */
    private val offsetMillis = AtomicLong(NO_OFFSET)

    /** True once a response has been seen and the offset is real. */
    val isCalibrated: Boolean get() = offsetMillis.get() != NO_OFFSET

    /** Feed in the `Date` header of any response. Cheap, so call it on every one. */
    fun observeServerDate(serverDate: Instant, deviceNow: Instant = Instant.now()) {
        offsetMillis.set(serverDate.toEpochMilli() - deviceNow.toEpochMilli())
    }

    /** The server's idea of now, as best the client can tell. */
    fun now(deviceNow: Instant = Instant.now()): Instant = deviceNow.plusMillis(offsetOrZero())

    /**
     * A device timestamp, expressed in the server's time and guaranteed not to be in its
     * future.
     *
     * Before the first response the offset is unknown and the instant passes through
     * unchanged — which is correct, because the server's own 24-hour tolerance covers
     * ordinary skew and there is nothing better to go on.
     */
    fun toServerTime(deviceInstant: Instant, deviceNow: Instant = Instant.now()): Instant {
        val shifted = deviceInstant.plusMillis(offsetOrZero())
        val serverNow = now(deviceNow)
        return if (shifted.isAfter(serverNow)) serverNow else shifted
    }

    private fun offsetOrZero(): Long = offsetMillis.get().let { if (it == NO_OFFSET) 0L else it }

    private companion object {
        const val NO_OFFSET = Long.MIN_VALUE
    }
}
