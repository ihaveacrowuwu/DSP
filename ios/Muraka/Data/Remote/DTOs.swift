import Foundation

/// The wire format, one type per JSON shape the Go API produces.
///
/// These never leave `Data/`; mappers turn them into domain models at the repository
/// boundary. Every optional has a default because Go's `omitempty` drops nulls entirely
/// rather than sending `null`, so absence is the normal case, not an error.

struct UserDTO: Codable, Sendable {
    let id: String
    var email: String = ""
    var displayName: String = ""
    var role: String = "contributor"
    var status: String = "active"
    var createdAt: Date
}

struct ContributorStatsDTO: Codable, Sendable {
    var total: Int = 0
    var verified: Int = 0
    var pending: Int = 0
    var rejected: Int = 0
}

struct SessionDTO: Codable, Sendable {
    let accessToken: String
    let refreshToken: String
    let expiresAt: Date
    let user: UserDTO
}

struct MeDTO: Codable, Sendable {
    let user: UserDTO
    var stats = ContributorStatsDTO()
}

struct PointDTO: Codable, Sendable {
    var lat: Double = 0
    var lon: Double = 0
}

struct SightingDTO: Codable, Sendable {
    let id: String
    var contributorId: String = ""
    var contributorName: String?
    var siteId: String?
    var siteName: String?
    var location = PointDTO()
    var locationSource: String = "gps"
    var locationAccuracyM: Double?
    var depthM: Double?
    var capturedAt: Date
    var note: String?
    var selfAssessedCondition: String?
    var status: String = "pending_photos"
    var createdAt: Date
    var photoCount: Int = 0
    var condition: String?
    var severity: Double?
    var confidence: Double?
    var verified: Bool = false
}

struct PatchDTO: Codable, Sendable {
    var row: Int = 0
    var col: Int = 0
    var label: String = "healthy"
    var confidence: Double = 0
}

struct PredictionDTO: Codable, Sendable {
    var id: String = ""
    var photoId: String = ""
    var modelVersion: String = ""
    var label: String = "healthy"
    var confidence: Double = 0
    var severity: Double = 0
    var patchGrid: Int = 0
    var patches: [PatchDTO] = []
    var inferenceMs: Int?
    var createdAt: Date
}

struct PhotoDTO: Codable, Sendable {
    let id: String
    var sightingId: String = ""
    var url: String = ""
    var width: Int = 0
    var height: Int = 0
    var bytes: Int = 0
    var createdAt: Date
    /// Absent until classification finishes. Absent is not an error.
    var prediction: PredictionDTO?
}

struct VerificationDTO: Codable, Sendable {
    let id: String
    var sightingId: String = ""
    var verifierId: String = ""
    var verifierName: String?
    var decision: String = "confirmed"
    var label: String?
    var rejectReason: String?
    var comment: String?
    var createdAt: Date
}

struct SightingDetailDTO: Codable, Sendable {
    let sighting: SightingDTO
    var photos: [PhotoDTO] = []
    var verifications: [VerificationDTO] = []
}

struct SightingPageDTO: Codable, Sendable {
    var items: [SightingDTO] = []
    var total: Int = 0
    var limit: Int = 0
    var offset: Int = 0
}

// ── Requests ────────────────────────────────────────────────────────────────

struct RegisterRequest: Encodable, Sendable {
    let email: String
    let password: String
    let displayName: String
}

struct LoginRequest: Encodable, Sendable {
    let email: String
    let password: String
}

struct RefreshRequest: Encodable, Sendable {
    let refreshToken: String
}

/// `POST /v1/sightings`.
///
/// `id` is the client's own UUIDv7 and is what makes the whole submission idempotent. The
/// server resolves `siteId` itself from the coordinate, so the client must not send one.
struct CreateSightingRequest: Encodable, Sendable {
    let id: String
    let lat: Double
    let lon: Double
    let locationSource: String
    var locationAccuracyM: Double?
    var depthM: Double?
    let capturedAt: Date
    var note: String?
    var selfAssessedCondition: String?
}

struct PhotoUploadResponse: Decodable, Sendable {
    var photoId: String = ""
    var sightingId: String = ""
    var width: Int = 0
    var height: Int = 0
    var bytes: Int = 0
    /// False when this was a replay of an upload the server already had.
    var queued: Bool = false
}

// ── Coding ──────────────────────────────────────────────────────────────────

/// RFC 3339 parsing that tolerates however many fractional digits the source felt like.
///
/// This exists because `Date.ISO8601FormatStyle(includingFractionalSeconds:)` accepts exactly
/// **three** fractional digits, and neither producer in this system emits three:
///
/// ```
/// 2026-08-20T23:10:01.340152254Z   ← Go's time.Time, RFC3339Nano, nine digits
/// 2026-08-20T22:12:34.405288Z      ← PostgreSQL, six digits
/// 2026-08-20T22:12:34Z             ← no fractional part at all
/// ```
///
/// Decoding failed on every timestamp the API returned, which presented as "sign-in does not
/// work" rather than as a date problem — the decode error was the last thing in a chain that
/// started at the login screen. Splitting the fraction off and adding it back as a
/// `TimeInterval` handles any precision exactly, with no formatter that can be surprised.
enum RFC3339 {
    static func date(from raw: String) -> Date? {
        guard let dot = raw.firstIndex(of: ".") else {
            return try? Date(raw, strategy: .iso8601)
        }

        var cursor = raw.index(after: dot)
        var digits = ""
        while cursor < raw.endIndex, raw[cursor].isNumber {
            digits.append(raw[cursor])
            cursor = raw.index(after: cursor)
        }

        // The same string with the fractional part excised, which the strict parser accepts.
        let withoutFraction = String(raw[raw.startIndex ..< dot]) + String(raw[cursor...])
        guard let whole = try? Date(withoutFraction, strategy: .iso8601) else { return nil }

        return whole.addingTimeInterval(Double("0." + digits) ?? 0)
    }
}

extension JSONDecoder {
    /// The decoder every response goes through.
    static func muraka() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let raw = try container.decode(String.self)

            guard let date = RFC3339.date(from: raw) else {
                throw DecodingError.dataCorruptedError(
                    in: container,
                    debugDescription: "not an RFC 3339 timestamp: \(raw)"
                )
            }
            return date
        }
        return decoder
    }
}

extension JSONEncoder {
    static func muraka() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            // Whole seconds. The server parses RFC 3339 and needs no sub-second precision
            // from a capture time.
            try container.encode(date.formatted(.iso8601))
        }
        return encoder
    }
}
