package mv.muraka.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Scenario 13 of `mobile-shared/sync-protocol.md`: "set the device clock a day ahead,
 * capture → the sighting still submits successfully rather than failing 422".
 *
 * The stronger property tested alongside it is that the correction *preserves* when the
 * photograph was taken. Merely avoiding the 422 is easy — clamping everything to now does
 * that, and quietly falsifies the capture time of every queued sighting.
 */
class ServerClockTest {

    private val serverNow = Instant.parse("2026-08-21T10:00:00Z")

    /** A device clock reading exactly 24 hours fast. */
    private val fastDeviceNow = Instant.parse("2026-08-22T10:00:00Z")

    private fun calibrated(deviceNow: Instant = fastDeviceNow) =
        ServerClock().apply { observeServerDate(serverNow, deviceNow) }

    @Test
    fun `passes the device clock through before any response has been seen`() {
        val clock = ServerClock()
        assertFalse(clock.isCalibrated)
        assertEquals(fastDeviceNow, clock.toServerTime(fastDeviceNow, fastDeviceNow))
    }

    @Test
    fun `a capture on a device a day fast is accepted rather than rejected 422`() {
        val clock = calibrated()
        assertTrue(clock.isCalibrated)
        assertEquals(serverNow, clock.toServerTime(fastDeviceNow, fastDeviceNow))
    }

    @Test
    fun `an earlier capture keeps its real distance from now, rather than being clamped`() {
        val clock = calibrated()

        // Photographed one real hour ago. The fast device stamped it 2026-08-22T09:00Z,
        // which is still 23 hours in the server's future — so it cannot be sent as-is,
        // and clamping it to server-now would claim it was taken this minute.
        val stampedByFastDevice = fastDeviceNow.minusSeconds(3_600)

        assertEquals(serverNow.minusSeconds(3_600), clock.toServerTime(stampedByFastDevice, fastDeviceNow))
    }

    @Test
    fun `never moves a capture time forward when the device clock runs slow`() {
        val slowDeviceNow = Instant.parse("2026-08-21T09:00:00Z") // an hour behind
        val clock = calibrated(slowDeviceNow)

        // Corrected to the server's now, not left an hour in the past.
        assertEquals(serverNow, clock.toServerTime(slowDeviceNow, slowDeviceNow))
    }

    @Test
    fun `a timestamp already in the device's own future is clamped as a last guard`() {
        val clock = calibrated()
        val impossible = fastDeviceNow.plusSeconds(600)
        assertEquals(serverNow, clock.toServerTime(impossible, fastDeviceNow))
    }

    @Test
    fun `reports the server's now rather than the device's once calibrated`() {
        assertEquals(serverNow, calibrated().now(fastDeviceNow))
    }
}
