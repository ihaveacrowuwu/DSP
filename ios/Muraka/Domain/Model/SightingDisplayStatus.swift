import Foundation

/// The life of a row in the outbox.
///
/// A string state machine and **not** a `synced` boolean, because a boolean cannot express
/// *"we sent it and do not know what happened"* - and that is exactly the state a lost
/// response leaves you in. Collapsing it to `false` re-sends work that already succeeded;
/// collapsing it to `true` tells the contributor their sighting is safe when nobody has
/// confirmed it, which is the worst failure this system can have because nobody goes
/// looking for it.
enum OutboxState: String, Codable, CaseIterable, Sendable {
    /// Bytes on disk, nothing sent.
    case queued
    /// A request is in flight right now.
    case sending
    /// Sent, outcome not durably recorded. Reconciliation asks rather than guessing.
    case inDoubt = "in_doubt"
    /// The server acknowledged it. The row's job is over.
    case confirmed
    /// Terminally rejected. Needs the contributor.
    case failed
}

/// What the contributor is actually told.
///
/// **Only the first two may be stated on the client's own authority.** Everything below
/// that line is the server's answer or nothing at all. There is deliberately no "Synced":
/// a local flag saying the upload worked is a claim, not a fact (D21).
///
/// The `label` strings are a **cross-platform contract**, not chrome. The same sighting
/// must not read "Analysing" on iOS and "Processing" on Android, so these exact strings
/// also appear in `android/core/model/.../SyncState.kt`, and
/// `scripts/check_status_vocabulary.py` fails if the two ever drift.
enum SightingDisplayStatus: CaseIterable, Sendable {
    case waitingToUpload
    case uploading
    /// Accepted, not yet read back. The honest name for "we do not know yet".
    case checking
    case photosPending
    case analysing
    case awaitingReview
    case verifiedByExpert
    case notUsable
    case failed

    var label: String {
        switch self {
        case .waitingToUpload: "Waiting to upload"
        case .uploading: "Uploading"
        case .checking: "Checking…"
        case .photosPending: "Photos pending"
        case .analysing: "Analysing"
        case .awaitingReview: "Awaiting expert review"
        // Note the wording: "Verified by an expert", not "Verified". The longer form
        // carries the provenance distinction NFR13 asks for IN THE WORD ITSELF, so the
        // status survives a greyscale screenshot with no chip beside it.
        case .verifiedByExpert: "Verified by an expert"
        case .notUsable: "Not usable"
        case .failed: "Could not upload"
        }
    }

    /// True for the states the client may assert without asking the server.
    var isClientAsserted: Bool {
        switch self {
        case .waitingToUpload, .uploading, .failed: true
        case .checking, .photosPending, .analysing, .awaitingReview, .verifiedByExpert, .notUsable: false
        }
    }

    /// The single place outbox state and server status are combined.
    ///
    /// `serverStatus` is nil when the server has never answered for this sighting - which
    /// is normal offline, and exactly when the client must NOT invent a status.
    static func of(outboxState: OutboxState?, serverStatus: SightingStatus?) -> SightingDisplayStatus {
        switch outboxState {
        case .queued: .waitingToUpload
        case .sending: .uploading
        case .failed: .failed
        // Sent or acknowledged but with no server answer yet: the one case where "we do
        // not know" is the truthful thing to display.
        case .inDoubt, .confirmed, .none: fromServer(serverStatus) ?? .checking
        }
    }

    private static func fromServer(_ status: SightingStatus?) -> SightingDisplayStatus? {
        switch status {
        case .pendingPhotos: .photosPending
        case .processing: .analysing
        case .awaitingVerification: .awaitingReview
        case .verified: .verifiedByExpert
        case .rejected: .notUsable
        case .none: nil
        }
    }
}
