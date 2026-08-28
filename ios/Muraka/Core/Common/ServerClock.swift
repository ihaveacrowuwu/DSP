import Foundation
import os

/// Translates device time into server time.
///
/// A sighting whose `capturedAt` is in the future is rejected `422`, which is terminal - so
/// a phone with a wrong clock loses the sighting it just captured. The server tolerates 24
/// hours of skew, but a device left in the wrong time zone, or one whose clock was never set
/// after a flat battery, can be further out than that.
///
/// The fix is not to guess. Every HTTP response carries a `Date` header, so the client learns
/// the offset for free on the first successful request.
///
/// **The correction is a shift, not a clamp.** If the device clock reads 24 hours fast, a
/// photograph taken one real hour ago is stamped `deviceNow - 1h`, which is 23 hours in the
/// server's future. Clamping that to server-now would record it as having been taken right
/// now - an hour late, and wrong. Shifting it by the learned offset recovers the moment it
/// was actually taken. The clamp survives only as a final guard.
///
/// The offset is deliberately **not** persisted: a stale offset from last week is worse than
/// none, because the device clock may have been corrected since.
final class ServerClock: Sendable {
    /// `serverNow - deviceNow`, in seconds. Nil until a response arrives.
    private let offset = OSAllocatedUnfairLock<TimeInterval?>(initialState: nil)

    /// True once a response has been seen and the offset is real.
    var isCalibrated: Bool { offset.withLock { $0 != nil } }

    /// Feed in the `Date` header of any response. Cheap, so call it on every one.
    func observe(serverDate: Date, deviceNow: Date = Date()) {
        offset.withLock { $0 = serverDate.timeIntervalSince(deviceNow) }
    }

    /// The server's idea of now, as best the client can tell.
    func now(deviceNow: Date = Date()) -> Date {
        deviceNow.addingTimeInterval(offset.withLock { $0 } ?? 0)
    }

    /// A device timestamp, expressed in the server's time and guaranteed not to be in its
    /// future.
    ///
    /// Before the first response the offset is unknown and the instant passes through
    /// unchanged - correct, because the server's own 24-hour tolerance covers ordinary skew
    /// and there is nothing better to go on.
    func toServerTime(_ deviceInstant: Date, deviceNow: Date = Date()) -> Date {
        let shift = offset.withLock { $0 } ?? 0
        let shifted = deviceInstant.addingTimeInterval(shift)
        let serverNow = now(deviceNow: deviceNow)
        return shifted > serverNow ? serverNow : shifted
    }
}
