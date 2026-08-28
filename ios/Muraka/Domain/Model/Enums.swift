import Foundation

/// The wire vocabulary, exactly as the Go API speaks it.
///
/// Raw values are spelled out rather than derived from the case name, because two of them
/// would not survive that (`manualPin` -> `manual_pin`, `pendingPhotos` -> `pending_photos`)
/// and a silent mismatch is a `422` the outbox cannot retry.
///
/// Every one of these must match `android/core/model/.../Enums.kt` exactly - same strings,
/// same set - because they are the same contract with the same server.

/// What the reef looks like. Binary by design (D3).
enum Condition: String, Codable, CaseIterable, Sendable {
    case healthy
    case bleached
}

/// How the position was obtained. Researchers filter on this, so the distinction is stored.
enum LocationSource: String, Codable, CaseIterable, Sendable {
    case gps
    case manualPin = "manual_pin"
}

/// Where a sighting has reached, server-side. The client only ever displays this.
enum SightingStatus: String, Codable, CaseIterable, Sendable {
    case pendingPhotos = "pending_photos"
    case processing
    case awaitingVerification = "awaiting_verification"
    case verified
    case rejected
}

/// A researcher's decision. Contributors cannot make these; the app shows them.
enum VerificationDecision: String, Codable, CaseIterable, Sendable {
    case confirmed
    case corrected
    case rejected
}

/// Why a photograph was rejected. Shown to the contributor - it is their own record.
enum RejectReason: String, Codable, CaseIterable, Sendable {
    case blurry
    case notCoral = "not_coral"
    case duplicate
    case spam
    case other

    /// "not coral", for display.
    var readable: String { rawValue.replacingOccurrences(of: "_", with: " ") }
}

/// Account role. The app only ever signs in as `contributor`.
enum Role: String, Codable, CaseIterable, Sendable {
    case contributor
    case researcher
    case admin
}

extension Condition {
    /// Tolerant decoding: a server that grows a third condition must not crash an
    /// installed app.
    init?(wire: String?) {
        guard let wire, let value = Condition(rawValue: wire) else { return nil }
        self = value
    }
}
