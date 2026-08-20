import Foundation
import GRDB

/// The queue, as the sync screen sees it.
///
/// Everything here is about making pending work **visible**. A silent queue is how data goes
/// missing unnoticed, and the two escape hatches — retry, and retry smaller — exist because
/// `sync-protocol.md` requires a stranded sighting to have a way out rather than sitting in a
/// failure the contributor can see but not act on.
final class OutboxRepository: Sendable {
    private let outbox: OutboxStore
    private let photos: PhotoStore
    private let tokens: TokenStore

    init(outbox: OutboxStore, photos: PhotoStore, tokens: TokenStore) {
        self.outbox = outbox
        self.photos = photos
        self.tokens = tokens
    }

    /// Everything not yet acknowledged, re-emitted whenever the queue changes.
    func queueStream(userID: String) -> AsyncValueObservation<[QueuedItem]> {
        ValueObservation
            .tracking { db -> [QueuedItem] in
                let rows = try SightingQueueRecord
                    .filter(Column("user_id") == userID)
                    .filter(Column("state") != OutboxState.confirmed.rawValue)
                    .order(Column("created_at").asc)
                    .fetchAll(db)

                let photos = try PhotoQueueRecord
                    .filter(sql: "sighting_id IN (SELECT id FROM sighting_queue WHERE user_id = ?)",
                            arguments: [userID])
                    .fetchAll(db)
                let byID = Dictionary(grouping: photos, by: \.sightingID)

                return rows.map { row in
                    let mine = byID[row.id] ?? []
                    return QueuedItem(
                        sightingID: row.id,
                        capturedAt: row.capturedAt,
                        state: row.outboxState,
                        photosTotal: mine.count,
                        // "Sent" here means the server acknowledged the upload call. It is
                        // NOT a claim that the photograph is safe — that only follows the
                        // read-back, which is what deletes the row entirely.
                        photosSent: mine.count { $0.state != OutboxState.queued.rawValue },
                        attempts: row.attempts,
                        lastError: row.lastError,
                        nextAttemptAt: row.nextAttemptAt.map { Date(timeIntervalSince1970: $0) }
                    )
                }
            }
            .values(in: outbox.queue)
    }

    /// How many sightings are still undelivered. Shown permanently, because a silent queue
    /// is how reef data goes missing unnoticed.
    func pendingCountStream(userID: String) -> AsyncValueObservation<Int> {
        let states = [OutboxState.queued, .sending, .inDoubt, .failed].map(\.rawValue)
        return ValueObservation
            .tracking { db in
                try SightingQueueRecord
                    .filter(Column("user_id") == userID && states.contains(Column("state")))
                    .fetchCount(db)
            }
            .values(in: outbox.queue)
    }

    /// Puts a terminally failed row back in the queue after the contributor acts.
    func retry(sightingID: String) async throws {
        try await outbox.requeue(sightingID: sightingID)
    }

    /// The way out of a `413`.
    ///
    /// Each oversized photograph is re-encoded smaller and queued under a **new** photo id,
    /// because the old id may already be half-known to the server — reusing it would ask the
    /// server to reconcile two different images under one key. The old row and its file go
    /// only once the replacement is durably written.
    func retryWithSmallerPhotos(sightingID: String) async throws {
        let pending = try await outbox.photos(for: sightingID)
            .filter { $0.state != OutboxState.confirmed.rawValue }
        guard !pending.isEmpty else { return try await retry(sightingID: sightingID) }

        var replacements: [PhotoQueueRecord] = []
        for photo in pending {
            let replacementID = UUIDv7.generate()
            guard let url = await photos.downscaleFurther(from: photo.id, to: replacementID) else {
                throw ApiError.validation(fields: ["file": "could not be made smaller"])
            }
            var replacement = photo
            replacement.id = replacementID
            replacement.localPath = url.path
            replacement.state = OutboxState.queued.rawValue
            replacement.attempts = 0
            replacement.lastError = nil
            replacements.append(replacement)
        }

        try await outbox.insertPhotos(replacements)
        // Only now: the replacements exist on disk and in the queue, so nothing is lost if
        // the process dies on the next line.
        for photo in pending {
            try await outbox.deletePhoto(id: photo.id)
            await photos.delete(photoID: photo.id)
        }
        try await retry(sightingID: sightingID)
    }

    /// Gives up on a row, at the contributor's explicit instruction and never otherwise.
    ///
    /// This cannot un-send anything: if the metadata already reached the server, the sighting
    /// is real and stays real. It deletes the device's copy of work it will no longer attempt.
    func discard(sightingID: String) async throws {
        for photo in try await outbox.photos(for: sightingID) {
            await photos.delete(photoID: photo.id)
        }
        try await outbox.delete(sightingID: sightingID)
    }
}
