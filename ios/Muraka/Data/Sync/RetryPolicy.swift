import Foundation

/// Exponential backoff with jitter, capped - `min(2^attempts, 300) seconds +/- 20%`, from
/// `mobile-shared/sync-protocol.md`.
///
/// The jitter matters more than it looks. Without it, a boat full of divers whose phones all
/// lost signal at the same jetty reconnect and retry in lockstep, and the server sees every
/// client's backoff curve aligned. With it they spread out.
///
/// After ``maxAttempts`` the row is marked failed and surfaced with a Retry action rather
/// than retried forever: a contributor deserves to know something is stuck, and an invisible
/// infinite retry is how a sighting is quietly never delivered.
enum RetryPolicy {
    /// Eight attempts spans roughly nine minutes of real backoff before giving up.
    static let maxAttempts = 8

    private static let capSeconds: Double = 300
    private static let jitterFraction: Double = 0.2

    /// Seconds to wait before attempt number `attempts + 1`.
    static func delay(attempts: Int, jitter: Double = Double.random(in: -0.2 ... 0.2)) -> TimeInterval {
        let base = min(pow(2.0, Double(max(attempts, 0))), capSeconds)
        return max(0, base * (1 + jitter.clamped(to: -jitterFraction ... jitterFraction)))
    }

    /// When the next attempt is permitted.
    static func nextAttempt(
        after now: Date = Date(),
        attempts: Int,
        jitter: Double = Double.random(in: -0.2 ... 0.2)
    ) -> Date {
        now.addingTimeInterval(delay(attempts: attempts, jitter: jitter))
    }

    /// Whether the row has run out of attempts and needs the contributor.
    static func isExhausted(attempts: Int) -> Bool { attempts >= maxAttempts }
}

private extension Comparable {
    func clamped(to range: ClosedRange<Self>) -> Self {
        min(max(self, range.lowerBound), range.upperBound)
    }
}
