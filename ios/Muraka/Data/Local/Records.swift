import Foundation
import GRDB

/// The outbox, and a cache of what the server has said.
///
/// Two separate tables on purpose, and nothing joins them in SQL. The outbox is
/// authoritative only about what has NOT been delivered; the cache is last-known server
/// state and never a record. Merging them into one table with a `synced` column is the
/// design this one exists to avoid (D21).

/// One queued sighting.
struct SightingQueueRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "sighting_queue"

    var id: String
    var userID: String
    var lat: Double
    var lon: Double
    var locationSource: String
    var locationAccuracyM: Double?
    var depthM: Double?
    /// Epoch seconds, as the **device** clock reported it.
    var capturedAtDevice: Double
    var note: String?
    var selfCondition: String?
    /// `queued` | `sending` | `in_doubt` | `confirmed` | `failed`.
    var state: String
    var attempts: Int = 0
    var lastError: String?
    /// Epoch seconds before which the backoff curve forbids another attempt.
    var nextAttemptAt: Double?
    var createdAt: Double

    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case lat, lon
        case locationSource = "location_source"
        case locationAccuracyM = "location_accuracy_m"
        case depthM = "depth_m"
        case capturedAtDevice = "captured_at_device"
        case note
        case selfCondition = "self_condition"
        case state, attempts
        case lastError = "last_error"
        case nextAttemptAt = "next_attempt_at"
        case createdAt = "created_at"
    }

    var outboxState: OutboxState { OutboxState(rawValue: state) ?? .queued }
    var capturedAt: Date { Date(timeIntervalSince1970: capturedAtDevice) }
    var position: Position { Position(lat: lat, lon: lon) }
}

/// One queued photograph.
struct PhotoQueueRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "photo_queue"

    var id: String
    var sightingID: String
    /// A file in app-private storage. A picked asset can be revoked; these bytes are ours.
    var localPath: String
    /// Capture order, so photographs upload in the order they were taken.
    var ordinal: Int
    var state: String
    var attempts: Int = 0
    var lastError: String?

    enum CodingKeys: String, CodingKey {
        case id
        case sightingID = "sighting_id"
        case localPath = "local_path"
        case ordinal, state, attempts
        case lastError = "last_error"
    }

    var fileURL: URL { URL(fileURLWithPath: localPath) }
}

/// Last-known server state for one sighting.
struct CachedSightingRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "cached_sighting"

    var id: String
    var userID: String
    var lat: Double
    var lon: Double
    var locationSource: String
    var capturedAt: Double
    var createdAt: Double
    var status: String
    var photoCount: Int
    var condition: String?
    var severity: Double?
    var confidence: Double?
    var verified: Bool
    var depthM: Double?
    var note: String?
    var siteName: String?
    /// When this row was last read from the server.
    var readAt: Double

    enum CodingKeys: String, CodingKey {
        case id
        case userID = "user_id"
        case lat, lon
        case locationSource = "location_source"
        case capturedAt = "captured_at"
        case createdAt = "created_at"
        case status
        case photoCount = "photo_count"
        case condition, severity, confidence, verified
        case depthM = "depth_m"
        case note
        case siteName = "site_name"
        case readAt = "read_at"
    }

    var domain: Sighting {
        Sighting(
            id: id,
            contributorID: userID,
            siteName: siteName,
            position: Position(lat: lat, lon: lon),
            locationSource: LocationSource(rawValue: locationSource) ?? .gps,
            depthM: depthM,
            capturedAt: Date(timeIntervalSince1970: capturedAt),
            note: note,
            status: SightingStatus(rawValue: status) ?? .processing,
            createdAt: Date(timeIntervalSince1970: createdAt),
            photoCount: photoCount,
            condition: Condition(wire: condition),
            severity: severity,
            confidence: confidence,
            verified: verified
        )
    }
}

/// The full detail response, as JSON.
struct CachedDetailRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "cached_detail"

    var id: String
    var json: Data
    var readAt: Double

    enum CodingKeys: String, CodingKey {
        case id, json
        case readAt = "read_at"
    }
}

/// Last-known profile, so the profile screen shows something while offline.
struct CachedProfileRecord: Codable, FetchableRecord, PersistableRecord, Sendable {
    static let databaseTableName = "cached_profile"

    var userID: String
    var json: Data
    var readAt: Double

    enum CodingKeys: String, CodingKey {
        case userID = "user_id"
        case json
        case readAt = "read_at"
    }
}

extension Sighting {
    /// The cache row for a server record, stamped with when it was read.
    func cacheRecord(userID: String, readAt: Date) -> CachedSightingRecord {
        CachedSightingRecord(
            id: id,
            userID: userID,
            lat: position.lat,
            lon: position.lon,
            locationSource: locationSource.rawValue,
            capturedAt: capturedAt.timeIntervalSince1970,
            createdAt: createdAt.timeIntervalSince1970,
            status: status.rawValue,
            photoCount: photoCount,
            condition: condition?.rawValue,
            severity: severity,
            confidence: confidence,
            verified: verified,
            depthM: depthM,
            note: note,
            siteName: siteName,
            readAt: readAt.timeIntervalSince1970
        )
    }
}
