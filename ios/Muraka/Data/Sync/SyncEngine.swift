import Foundation
import UIKit

/// What one drain pass achieved.
struct SyncOutcome: Sendable, Equatable {
    var sightingsConfirmed = 0
    var photosUploaded = 0
    var stillPending = 0
    var failedTerminally = 0
    /// The pass stopped because the server could not be reached.
    var offline = false
    /// The session ended mid-drain and the contributor must sign in again.
    var needsSignIn = false

    /// Whether the caller should try again later.
    var shouldRetry: Bool { (offline || stillPending > 0) && !needsSignIn }
}

/// The drain loop.
///
/// The algorithm is `mobile-shared/sync-protocol.md`, and the shape of it is:
///
/// ```
/// for each row the contributor still owes, oldest capture first:
///     1. work out what the SERVER already has  (GET /v1/sightings/{id})
///     2. send only what is missing             (metadata, then the missing photos)
///     3. read the sighting back                (GET again)
///     4. only then delete anything local
/// ```
///
/// Step 1 is what makes it safe. The client never has to guess whether a write landed,
/// because the ids are its own: `404` means the server has nothing, and `200` carries
/// `photos[]`, whose ids are also the client's — so the difference is the exact set still
/// missing, not an estimate. No bookkeeping column could be more trustworthy, because it is
/// the database answering.
///
/// Step 4 is where data is lost if you get it wrong. Nothing local is deleted on the strength
/// of an upload call returning, and nothing is deleted on a response that could not be parsed.
///
/// This is deliberately the same algorithm as `SyncEngineImpl.kt`, written from the protocol
/// document rather than translated from the Kotlin — which is what the document asks for.
actor SyncEngine {
    private let api: APIClient
    private let outbox: OutboxStore
    private let photos: PhotoStore
    private let tokens: TokenStore
    private let serverClock: ServerClock

    private let encoder = JSONEncoder.muraka()
    private let decoder = JSONDecoder.muraka()

    /// One drain at a time. Connectivity returning and a background task firing together is
    /// the ordinary case when a boat comes back into range, not a rare race, and two
    /// concurrent drains would upload the same photograph twice — harmless because of the
    /// ids, but it doubles a diver's tethering allowance for nothing.
    private var draining = false

    init(api: APIClient, outbox: OutboxStore, photos: PhotoStore, tokens: TokenStore, serverClock: ServerClock) {
        self.api = api
        self.outbox = outbox
        self.photos = photos
        self.tokens = tokens
        self.serverClock = serverClock
    }

    /// One pass over everything the signed-in contributor still owes the server.
    ///
    /// Safe to call as often as you like: every step is idempotent, and reconciliation is
    /// cheaper than showing a contributor something untrue.
    func drain() async -> SyncOutcome {
        guard !draining else { return SyncOutcome() }
        draining = true
        defer { draining = false }

        // No session means nothing may be uploaded. Rows are left exactly as they are —
        // they belong to their owner and wait for that account to sign back in.
        guard let userID = await tokens.currentUserID() else { return SyncOutcome() }

        var outcome = SyncOutcome()
        let due = (try? await outbox.dueForSync(userID: userID)) ?? []

        for row in due {
            switch await process(row: row) {
            case let .confirmed(uploaded):
                outcome.sightingsConfirmed += 1
                outcome.photosUploaded += uploaded

            case let .stillPending(uploaded):
                outcome.photosUploaded += uploaded

            case .failedTerminally:
                outcome.failedTerminally += 1

            case .offline:
                // Nothing is reachable. Continuing would burn the remaining rows' attempt
                // counters against a network that is not there, and each would then back
                // off as if it had genuinely failed.
                outcome.offline = true
                outcome.stillPending = (try? await outbox.pendingCount(for: userID)) ?? 0
                return outcome

            case .sessionEnded:
                outcome.needsSignIn = true
                outcome.stillPending = (try? await outbox.pendingCount(for: userID)) ?? 0
                return outcome
            }
        }

        outcome.stillPending = (try? await outbox.pendingCount(for: userID)) ?? 0
        return outcome
    }

    // ── One row ─────────────────────────────────────────────────────────────

    private enum RowOutcome {
        case confirmed(photosUploaded: Int)
        case stillPending(photosUploaded: Int)
        case failedTerminally
        case offline
        case sessionEnded
    }

    private func process(row: SightingQueueRecord) async -> RowOutcome {
        let localPhotos = (try? await outbox.photos(for: row.id)) ?? []
        try? await outbox.setSightingState(id: row.id, state: .sending)

        let alreadyHeld: Set<String>
        do {
            alreadyHeld = try await establishServerState(row: row)
        } catch {
            return await handleFailure(row: row, error: ApiError.from(error))
        }

        // Upload only what the server does not already hold. Re-sending a photograph it has
        // is harmless — it answers 200 — but it wastes a diver's tethering allowance.
        var uploaded = 0
        for photo in localPhotos where !alreadyHeld.contains(photo.id) {
            do {
                try await upload(photo: photo, to: row.id)
                uploaded += 1
            } catch {
                // A photograph that will not upload does not fail the sighting: the metadata
                // is on the server and the record is real, just short. The row stays and the
                // sync screen offers the contributor a way out.
                return await handleFailure(row: row, error: ApiError.from(error), photosUploaded: uploaded)
            }
        }

        return await confirmOrKeep(row: row, localPhotos: localPhotos, uploaded: uploaded)
    }

    /// Works out what the server holds, creating the metadata if it holds nothing.
    ///
    /// A row that has never left the device cannot exist server-side, so the reconciliation
    /// GET would be a guaranteed 404 and a wasted round trip on the common path.
    private func establishServerState(row: SightingQueueRecord) async throws -> Set<String> {
        let neverSent = row.outboxState == .queued && row.attempts == 0

        var held: Set<String> = []
        if !neverSent, let detail = try await api.sighting(id: row.id) {
            held = Set(detail.photos.map(\.id))
        }

        // Nothing server-side, in either branch: send the metadata. `201` and `200` are
        // treated identically — the client never has to know which it was.
        if held.isEmpty {
            _ = try await api.createSighting(CreateSightingRequest(
                id: row.id,
                lat: row.lat,
                lon: row.lon,
                locationSource: row.locationSource,
                locationAccuracyM: row.locationAccuracyM,
                depthM: row.depthM,
                // Device time translated into the server's, so a wrong device clock does not
                // turn a captured sighting into a terminal 422.
                capturedAt: serverClock.toServerTime(row.capturedAt),
                note: row.note,
                selfAssessedCondition: row.selfCondition
            ))
        }
        return held
    }

    /// The read-back, and the only place local data may be deleted.
    ///
    /// Uploading is not finishing: until the database itself lists every photo id, the row
    /// stays and the contributor is told "Checking…" rather than something reassuring.
    private func confirmOrKeep(
        row: SightingQueueRecord,
        localPhotos: [PhotoQueueRecord],
        uploaded: Int
    ) async -> RowOutcome {
        let detail: SightingDetailDTO?
        do {
            detail = try await api.sighting(id: row.id)
        } catch {
            return await handleFailure(row: row, error: ApiError.from(error), photosUploaded: uploaded)
        }

        // A 404 immediately after a successful create means something is genuinely wrong.
        // Leave the row alone and let the next pass find out.
        guard let detail else {
            return await handleFailure(row: row, error: .timedOut, photosUploaded: uploaded)
        }

        await cache(detail: detail, userID: row.userID)

        let heldByServer = Set(detail.photos.map(\.id))
        guard localPhotos.allSatisfy({ heldByServer.contains($0.id) }) else {
            return await handleFailure(row: row, error: .timedOut, photosUploaded: uploaded)
        }

        // The database has confirmed every photograph. Only now may anything local go.
        for photo in localPhotos { await photos.delete(photoID: photo.id) }
        try? await outbox.delete(sightingID: row.id)
        return .confirmed(photosUploaded: uploaded)
    }

    private func upload(photo: PhotoQueueRecord, to sightingID: String) async throws {
        let url = await photos.fileURL(for: photo.id)
        guard let data = try? Data(contentsOf: url) else {
            // The bytes are gone and cannot be recovered — a wiped container, or a file that
            // never finished being written. Honest failure beats a silent skip that would
            // leave the sighting permanently one photograph short.
            try? await outbox.recordPhotoAttempt(
                id: photo.id,
                state: .failed,
                error: "the photograph's file is missing"
            )
            throw ApiError.validation(fields: ["file": "is missing from this device"])
        }

        try? await outbox.setPhotoState(id: photo.id, state: .sending)

        do {
            let boundary = "muraka.\(UUID().uuidString)"
            let body = Self.multipartBody(photoID: photo.id, jpeg: data, boundary: boundary)
            _ = try await api.sendIgnoringBody(.upload(
                "v1/sightings/\(sightingID)/photos",
                body: body,
                contentType: "multipart/form-data; boundary=\(boundary)"
            ))
            // Not deleted yet, and not called confirmed yet: the file goes only when the
            // server's own photos[] lists this id.
            try? await outbox.setPhotoState(id: photo.id, state: .inDoubt)
        } catch {
            let apiError = ApiError.from(error)
            try? await outbox.recordPhotoAttempt(
                id: photo.id,
                state: apiError.outcomeIsUnknown ? .inDoubt : .queued,
                error: apiError.message
            )
            throw apiError
        }
    }

    /// `multipart/form-data` with the two parts the API expects.
    private static func multipartBody(photoID: String, jpeg: Data, boundary: String) -> Data {
        var body = Data()
        func append(_ string: String) { body.append(Data(string.utf8)) }

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"photoId\"\r\n\r\n")
        append("\(photoID)\r\n")

        append("--\(boundary)\r\n")
        append("Content-Disposition: form-data; name=\"file\"; filename=\"\(photoID).jpg\"\r\n")
        append("Content-Type: image/jpeg\r\n\r\n")
        body.append(jpeg)
        append("\r\n--\(boundary)--\r\n")

        return body
    }

    // ── Failure handling ────────────────────────────────────────────────────

    private func handleFailure(
        row: SightingQueueRecord,
        error: ApiError,
        photosUploaded: Int = 0
    ) async -> RowOutcome {
        switch error {
        case .unauthorized:
            // The refresh already ran and failed. Leave the row untouched — the queue
            // survives sign-out, and its attempt counter must not be spent on a dead session.
            try? await outbox.setSightingState(id: row.id, state: row.outboxState)
            return .sessionEnded

        case .offline:
            // The request never left the device, so nothing can have happened server-side
            // and this is not an attempt. Not counted, not backed off.
            try? await outbox.setSightingState(id: row.id, state: .queued)
            return .offline

        default:
            break
        }

        if !error.isRetryable || RetryPolicy.isExhausted(attempts: row.attempts + 1) {
            // Out of attempts, or terminally rejected. Marked failed and surfaced with a
            // Retry action rather than retried silently forever.
            try? await outbox.recordSightingAttempt(
                id: row.id,
                state: .failed,
                error: error.message,
                nextAttemptAt: nil
            )
            return .failedTerminally
        }

        // `inDoubt` when the outcome is genuinely unknown, `queued` when it is not. The
        // distinction is the reason the state is a string rather than a boolean.
        try? await outbox.recordSightingAttempt(
            id: row.id,
            state: error.outcomeIsUnknown ? .inDoubt : .queued,
            error: error.message,
            nextAttemptAt: RetryPolicy.nextAttempt(attempts: row.attempts + 1)
        )
        return .stillPending(photosUploaded: photosUploaded)
    }

    private func cache(detail: SightingDetailDTO, userID: String) async {
        let readAt = Date()
        let sighting = detail.sighting.domain

        try? await outbox.cacheSightings([sighting.cacheRecord(userID: userID, readAt: readAt)])
        if let json = try? encoder.encode(detail) {
            try? await outbox.cacheDetail(CachedDetailRecord(
                id: detail.sighting.id,
                json: json,
                readAt: readAt.timeIntervalSince1970
            ))
        }
    }
}
