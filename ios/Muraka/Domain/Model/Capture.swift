import Foundation

/// Limits the **client** is solely responsible for.
///
/// The API enforces none of these: it accepts any number of photographs and a note of any
/// length. FR2 says one to five photographs, so if the app does not cap it, nothing does.
enum CaptureLimits {
    static let minPhotos = 1
    /// FR2. The API would happily take a sixth.
    static let maxPhotos = 5
    /// Unbounded server-side. Long enough for a real observation, short enough to read.
    static let maxNoteLength = 500
    /// The server *does* enforce this one; matching it locally turns a 422 into a hint.
    static let depthRangeM = 0.0 ... 200.0

    /// Longest edge, in points, that uploads are downscaled to.
    ///
    /// The server analyses at 224 px per grid cell, so a 5x5 grid gains nothing above
    /// roughly 1600 px - well under the 12 MiB cap and far kinder to resort Wi-Fi.
    static let uploadMaxEdge: CGFloat = 1600
    static let uploadJPEGQuality: CGFloat = 0.85
}

/// A position fix, however it was obtained.
struct LocationFix: Equatable, Sendable {
    let position: Position
    let source: LocationSource
    var accuracyM: Double?
}

/// One photograph, already copied into app-private storage.
struct PhotoDraft: Equatable, Sendable {
    /// Client-generated UUIDv7. The idempotency key for the upload.
    let id: String
    let fileURL: URL
}

/// Everything needed to queue a sighting.
///
/// ``capturedAt`` is **device** time. `ServerClock` translates it at upload, once an
/// offset is known; correcting it here would be guessing, because a sighting captured
/// offline has no offset to correct against yet.
struct SightingDraft: Sendable {
    let id: String
    let fix: LocationFix
    let capturedAt: Date
    var depthM: Double?
    var note: String?
    var selfAssessedCondition: Condition?
    var photos: [PhotoDraft]

    /// Problems the contributor must fix before this can be queued, keyed by field.
    func validate() -> [String: String] {
        var problems: [String: String] = [:]
        if !fix.position.isValid { problems["position"] = "must be a valid coordinate" }
        if photos.count < CaptureLimits.minPhotos { problems["photos"] = "add at least one photograph" }
        if photos.count > CaptureLimits.maxPhotos {
            problems["photos"] = "at most \(CaptureLimits.maxPhotos) photographs"
        }
        if let depthM, !CaptureLimits.depthRangeM.contains(depthM) {
            problems["depthM"] = "must be between 0 and 200 metres"
        }
        if let note, note.count > CaptureLimits.maxNoteLength {
            problems["note"] = "at most \(CaptureLimits.maxNoteLength) characters"
        }
        return problems
    }
}

/// A sighting as the contributor's own history shows it.
///
/// ``server`` is nil until the server has answered about this id even once - the normal
/// state of a sighting captured on a boat. ``serverReadAt`` is what lets the interface say
/// "checked 20 minutes ago" instead of presenting a stale truth as a current one.
struct ContributorSighting: Identifiable, Equatable, Sendable {
    let id: String
    /// Device capture time - what the contributor recognises.
    let capturedAt: Date
    let position: Position
    let locationSource: LocationSource
    var photoCount: Int
    var displayStatus: SightingDisplayStatus
    var server: Sighting?
    var serverReadAt: Date?
    var outboxState: OutboxState?
    /// Why a terminally failed row failed, in words the contributor can act on.
    var failureReason: String?
    /// Photographs still waiting to upload, of ``photoCount``.
    var photosPending: Int = 0

    /// True when nothing about this sighting has been confirmed by the server yet.
    var isUnconfirmed: Bool { server == nil }
}

/// One row of the sync screen.
struct QueuedItem: Identifiable, Equatable, Sendable {
    var id: String { sightingID }
    let sightingID: String
    let capturedAt: Date
    let state: OutboxState
    let photosTotal: Int
    let photosSent: Int
    let attempts: Int
    var lastError: String?
    /// When the backoff curve permits the next attempt. Nil when it may go now.
    var nextAttemptAt: Date?
}
