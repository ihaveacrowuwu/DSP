import Foundation

/// Something that happened to the session which the interface has to react to.
enum SessionEvent: Sendable {
    /// The refresh token was rejected. The session is over.
    ///
    /// The contributor is returned to sign-in and **the queue is kept** — scenario 6 of
    /// `mobile-shared/sync-protocol.md`. Clearing it here would throw away reef data because
    /// a token expired, which is not a reason to lose anything.
    case refreshFailed
    /// An admin suspended the account. Sign out with an explanation, not a silent bounce.
    case accountDisabled
}

/// A bus for the two events that can end a session from underneath the interface.
///
/// It exists because the place that discovers them — a token refresh deep inside a
/// background upload — has no way to reach a navigation controller, and passing one down
/// there would be far worse than a stream.
///
/// `AsyncStream` with a buffering policy rather than an unbounded one: emission must never
/// block the uploader on an interface that may not be listening.
final class SessionEvents: Sendable {
    private let continuation: AsyncStream<SessionEvent>.Continuation
    let events: AsyncStream<SessionEvent>

    init() {
        var capturedContinuation: AsyncStream<SessionEvent>.Continuation?
        events = AsyncStream(bufferingPolicy: .bufferingNewest(1)) { capturedContinuation = $0 }
        // The stream's initialiser runs its closure synchronously, so this is always set.
        continuation = capturedContinuation ?? AsyncStream<SessionEvent>.makeStream().continuation
    }

    func send(_ event: SessionEvent) {
        continuation.yield(event)
    }
}
