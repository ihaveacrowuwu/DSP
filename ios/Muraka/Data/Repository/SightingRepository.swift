import Foundation
import GRDB

/// A sighting as the detail screen needs it.
struct SightingWithDetail: Sendable, Equatable {
    let summary: ContributorSighting
    var detail: SightingDetail?
    var photos: [Photo] = []
    var verifications: [Verification] = []
    /// Local files for photographs that have not reached the server yet.
    var pendingPhotoPaths: [URL] = []
}

/// Capture, history and detail.
///
/// The merge in ``mySightings()`` is the heart of D21: outbox rows and cached server records
/// are held separately and combined only for display, so the interface can never accidentally
/// present a local flag as a server fact.
final class SightingRepository: Sendable {
    private let api: APIClient
    private let outbox: OutboxStore
    private let photos: PhotoStore
    private let tokens: TokenStore

    private let decoder = JSONDecoder.muraka()
    private let encoder = JSONEncoder.muraka()

    init(api: APIClient, outbox: OutboxStore, photos: PhotoStore, tokens: TokenStore) {
        self.api = api
        self.outbox = outbox
        self.photos = photos
        self.tokens = tokens
    }

    /// Queues a sighting.
    ///
    /// **Local only** — this returns as soon as the row and its photo files are durably on
    /// disk, and never waits for the network. NFR7 is the whole point: the app is fully
    /// functional with no connectivity except sign-in.
    func capture(_ draft: SightingDraft) async throws -> String {
        let problems = draft.validate()
        guard problems.isEmpty else { throw ApiError.validation(fields: problems) }
        guard let userID = await tokens.currentUserID() else { throw ApiError.unauthorized }

        let now = Date().timeIntervalSince1970
        try await outbox.enqueue(
            sighting: SightingQueueRecord(
                id: draft.id,
                userID: userID,
                lat: draft.fix.position.lat,
                lon: draft.fix.position.lon,
                locationSource: draft.fix.source.rawValue,
                locationAccuracyM: draft.fix.accuracyM,
                depthM: draft.depthM,
                capturedAtDevice: draft.capturedAt.timeIntervalSince1970,
                note: draft.note?.isEmpty == false ? draft.note : nil,
                selfCondition: draft.selfAssessedCondition?.rawValue,
                state: OutboxState.queued.rawValue,
                createdAt: now
            ),
            photos: draft.photos.enumerated().map { index, photo in
                PhotoQueueRecord(
                    id: photo.id,
                    sightingID: draft.id,
                    localPath: photo.fileURL.path,
                    ordinal: index,
                    state: OutboxState.queued.rawValue
                )
            }
        )
        return draft.id
    }

    /// The contributor's own history, re-emitted whenever anything local changes.
    ///
    /// Three sources, combined without ever letting one speak for another: the outbox (what
    /// we still owe), the photo queue (how much of each is left) and the cache (what the
    /// server last said). A sighting the server has never confirmed shows what the outbox
    /// knows and nothing more.
    func mySightingsStream(userID: String) -> AsyncValueObservation<[ContributorSighting]> {
        ValueObservation
            .tracking { db -> [ContributorSighting] in
                let queued = try SightingQueueRecord
                    .filter(Column("user_id") == userID)
                    .filter(Column("state") != OutboxState.confirmed.rawValue)
                    .fetchAll(db)

                let cached = try CachedSightingRecord
                    .filter(Column("user_id") == userID)
                    .fetchAll(db)

                let pendingPhotos = try PhotoQueueRecord
                    .filter(sql: "sighting_id IN (SELECT id FROM sighting_queue WHERE user_id = ?)",
                            arguments: [userID])
                    .filter(Column("state") != OutboxState.confirmed.rawValue)
                    .fetchAll(db)

                return Self.merge(queued: queued, pendingPhotos: pendingPhotos, cached: cached)
            }
            .values(in: outbox.queue)
    }

    func mySightings(userID: String) async throws -> [ContributorSighting] {
        let queued = try await outbox.queued(for: userID)
        let cached = try await outbox.cachedSightings(for: userID)
        let pending = try await outbox.allPendingPhotos(for: userID)
            .filter { $0.state != OutboxState.confirmed.rawValue }
        return Self.merge(queued: queued, pendingPhotos: pending, cached: cached)
    }

    /// One sighting, with its photographs and any expert verdict.
    func sighting(id: String, userID: String) async throws -> SightingWithDetail? {
        guard let summary = try await mySightings(userID: userID).first(where: { $0.id == id })
        else { return nil }

        let detail = try await outbox.cachedDetail(id: id)
            .flatMap { try? decoder.decode(SightingDetailDTO.self, from: $0.json) }?
            .domain

        let pendingPaths = try await outbox.photos(for: id)
            .filter { $0.state != OutboxState.confirmed.rawValue }
            .map(\.fileURL)

        return SightingWithDetail(
            summary: summary,
            detail: detail,
            photos: detail?.photos ?? [],
            verifications: detail?.verifications ?? [],
            pendingPhotoPaths: pendingPaths
        )
    }

    /// Pulls the list from the server. A failure leaves the cache exactly as it was —
    /// never a blank history because the network dropped.
    func refreshMySightings() async throws {
        guard let userID = await tokens.currentUserID() else { throw ApiError.unauthorized }

        let page = try await api.listSightings(limit: 50)
        let readAt = Date()
        let records = page.items.map { $0.domain.cacheRecord(userID: userID, readAt: readAt) }

        try await outbox.cacheSightings(records)
        // Scenario 10: a sighting removed server-side must stop appearing here. Nothing
        // survives in the interface on local authority alone.
        try await outbox.pruneCachedSightings(userID: userID, keeping: records.map(\.id))
    }

    /// The read that turns "Checking…" into a real status.
    func refreshSighting(id: String) async throws {
        guard let userID = await tokens.currentUserID() else { throw ApiError.unauthorized }
        guard let detail = try await api.sighting(id: id) else { return }

        let readAt = Date()
        // Replaced wholesale, never merged: an expert's correction, a rejection and an
        // anonymisation all arrive the same way, and there is no merge logic to get wrong.
        try await outbox.cacheSightings([detail.sighting.domain.cacheRecord(userID: userID, readAt: readAt)])
        if let json = try? encoder.encode(detail) {
            try await outbox.cacheDetail(CachedDetailRecord(
                id: id,
                json: json,
                readAt: readAt.timeIntervalSince1970
            ))
        }
    }

    /// Photograph bytes for display, fetched with the bearer token.
    func photoData(id: String) async throws -> Data {
        try await api.photoImage(id: id)
    }

    // ── The merge ───────────────────────────────────────────────────────────

    static func merge(
        queued: [SightingQueueRecord],
        pendingPhotos: [PhotoQueueRecord],
        cached: [CachedSightingRecord]
    ) -> [ContributorSighting] {
        let outboxByID = Dictionary(uniqueKeysWithValues: queued.map { ($0.id, $0) })
        let cachedByID = Dictionary(uniqueKeysWithValues: cached.map { ($0.id, $0) })
        let pendingByID = Dictionary(grouping: pendingPhotos, by: \.sightingID)

        let ids = Set(outboxByID.keys).union(cachedByID.keys)

        return ids.map { id -> ContributorSighting in
            let row = outboxByID[id]
            let record = cachedByID[id]
            let server = record?.domain
            let pending = pendingByID[id]?.count ?? 0

            return ContributorSighting(
                id: id,
                // The device's capture time is what the contributor recognises, so it wins
                // for display even once the server has stored its own version.
                capturedAt: row?.capturedAt ?? server?.capturedAt ?? .distantPast,
                position: row?.position ?? server?.position ?? Position(lat: 0, lon: 0),
                locationSource: row.flatMap { LocationSource(rawValue: $0.locationSource) }
                    ?? server?.locationSource ?? .gps,
                photoCount: max(server?.photoCount ?? 0, pending),
                displayStatus: SightingDisplayStatus.of(
                    outboxState: row?.outboxState,
                    serverStatus: server?.status
                ),
                server: server,
                serverReadAt: record.map { Date(timeIntervalSince1970: $0.readAt) },
                outboxState: row?.outboxState,
                failureReason: row?.outboxState == .failed ? row?.lastError : nil,
                photosPending: pending
            )
        }
        .sorted { $0.capturedAt > $1.capturedAt }
    }
}
