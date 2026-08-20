import Foundation

/// A coordinate in WGS84.
struct Position: Equatable, Sendable {
    let lat: Double
    let lon: Double

    /// The server rejects anything outside these bounds with a terminal `422`.
    static let latitudeRange = -90.0 ... 90.0
    static let longitudeRange = -180.0 ... 180.0

    var isValid: Bool {
        Self.latitudeRange.contains(lat) && Self.longitudeRange.contains(lon)
    }
}

/// A sighting as the **server** holds it.
///
/// There is deliberately no `synced` field and no client-computed status. This type only
/// ever comes from a server response; what the client knows about work it has not
/// delivered lives in the outbox, and the two are combined only for display. See D21.
struct Sighting: Identifiable, Equatable, Sendable {
    let id: String
    let contributorID: String
    var contributorName: String?
    var siteName: String?
    let position: Position
    let locationSource: LocationSource
    var locationAccuracyM: Double?
    var depthM: Double?
    let capturedAt: Date
    var note: String?
    var selfAssessedCondition: Condition?
    let status: SightingStatus
    /// When the server received it, as distinct from when it was captured.
    let createdAt: Date
    var photoCount: Int = 0
    /// Expert label where one exists, otherwise the model's. Never render without
    /// ``verified`` beside it — that is the NFR13 failure.
    var condition: Condition?
    /// Worst bleached extent across the sighting's photographs, 0–1.
    var severity: Double?
    var confidence: Double?
    /// True only when an expert confirmed or corrected.
    var verified: Bool = false
}

/// One cell of the inference grid.
struct Patch: Equatable, Sendable {
    let row: Int
    let col: Int
    let label: Condition
    let confidence: Double
}

/// What the model made of one photograph.
///
/// ``severity`` is the number to lead with, not ``label``: "62% bleached" tells a
/// contributor something "bleached" does not. ``modelVersion`` is provenance and must be
/// shown — `fake-0.0.0` means no trained model is loaded yet (D19).
struct Prediction: Equatable, Sendable {
    let id: String
    let photoID: String
    let modelVersion: String
    let label: Condition
    let confidence: Double
    let severity: Double
    let patchGrid: Int
    let patches: [Patch]
    var inferenceMs: Int?
    let createdAt: Date
}

/// One photograph, with the model's reading of it if it exists.
struct Photo: Identifiable, Equatable, Sendable {
    let id: String
    let sightingID: String
    /// Relative path to the bytes. Requires the bearer token — not a public URL.
    let url: String
    let width: Int
    let height: Int
    let bytes: Int
    let createdAt: Date
    /// Absent until classification finishes. Absent is not an error.
    var prediction: Prediction?
}

/// An expert's decision on a sighting.
struct Verification: Identifiable, Equatable, Sendable {
    let id: String
    let sightingID: String
    var verifierName: String?
    let decision: VerificationDecision
    var label: Condition?
    var rejectReason: RejectReason?
    var comment: String?
    let createdAt: Date
}

/// Everything `GET /v1/sightings/{id}` returns.
struct SightingDetail: Equatable, Sendable {
    let sighting: Sighting
    var photos: [Photo] = []
    var verifications: [Verification] = []
}
