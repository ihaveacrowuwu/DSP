import Foundation
import GRDB

/// The outbox and the cache, as an actor.
///
/// Every query that selects work to send filters on `user_id`, without exception. A row
/// belongs to the account that captured it, and uploading it under anyone else's session
/// would attribute reef data to the wrong contributor.
actor OutboxStore {
    /// `nonisolated` because `DatabaseQueue` is itself thread-safe, and the repositories
    /// need it to build `ValueObservation`s. Routing observations through the actor would
    /// serialise every read behind the writer for no benefit.
    nonisolated let queue: DatabaseQueue

    init(queue: DatabaseQueue) {
        self.queue = queue
    }

    // ── Writing ─────────────────────────────────────────────────────────────

    /// Queues a sighting and its photographs in **one transaction**.
    ///
    /// Atomicity is the point: a sighting row with no photo rows uploads as a sighting with
    /// zero photographs, which can never be classified, and a photo row with no parent is
    /// orphaned work. Either both land or neither does.
    func enqueue(sighting: SightingQueueRecord, photos: [PhotoQueueRecord]) throws {
        try queue.write { db in
            try sighting.insert(db, onConflict: .replace)
            for photo in photos {
                try photo.insert(db, onConflict: .replace)
            }
        }
    }

    func insertPhotos(_ photos: [PhotoQueueRecord]) throws {
        try queue.write { db in
            for photo in photos { try photo.insert(db, onConflict: .replace) }
        }
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /// Everything still owed to the server, oldest capture first.
    func queued(for userID: String) throws -> [SightingQueueRecord] {
        try queue.read { db in
            try SightingQueueRecord
                .filter(Column("user_id") == userID && Column("state") != OutboxState.confirmed.rawValue)
                .order(Column("created_at").asc)
                .fetchAll(db)
        }
    }

    /// Undelivered sightings, for the permanent count. A silent queue loses data unnoticed.
    func pendingCount(for userID: String) throws -> Int {
        let states = [
            OutboxState.queued, .sending, .inDoubt, .failed,
        ].map(\.rawValue)

        return try queue.read { db in
            try SightingQueueRecord
                .filter(Column("user_id") == userID && states.contains(Column("state")))
                .fetchCount(db)
        }
    }

    /// The next rows to work on.
    ///
    /// `created_at` ascending so the researcher's queue reflects capture order, and
    /// `next_attempt_at` respects the backoff curve. `sending` rows are included because a
    /// process killed mid-request leaves one behind, and reconciliation is what resolves it.
    /// `failed` rows are excluded: they need the contributor, not another silent attempt.
    func dueForSync(userID: String, now: Date = Date(), limit: Int = 25) throws -> [SightingQueueRecord] {
        let sendable = [OutboxState.queued, .sending, .inDoubt].map(\.rawValue)
        let nowSeconds = now.timeIntervalSince1970

        return try queue.read { db in
            try SightingQueueRecord
                .filter(Column("user_id") == userID)
                .filter(sendable.contains(Column("state")))
                .filter(Column("next_attempt_at") == nil || Column("next_attempt_at") <= nowSeconds)
                .order(Column("created_at").asc)
                .limit(limit)
                .fetchAll(db)
        }
    }

    func sighting(id: String) throws -> SightingQueueRecord? {
        try queue.read { db in try SightingQueueRecord.fetchOne(db, key: id) }
    }

    func photos(for sightingID: String) throws -> [PhotoQueueRecord] {
        try queue.read { db in
            try PhotoQueueRecord
                .filter(Column("sighting_id") == sightingID)
                .order(Column("ordinal").asc)
                .fetchAll(db)
        }
    }

    func allPendingPhotos(for userID: String) throws -> [PhotoQueueRecord] {
        try queue.read { db in
            try PhotoQueueRecord
                .filter(sql: "sighting_id IN (SELECT id FROM sighting_queue WHERE user_id = ?)", arguments: [userID])
                .fetchAll(db)
        }
    }

    // ── State transitions ───────────────────────────────────────────────────

    func setSightingState(id: String, state: OutboxState) throws {
        try queue.write { db in
            try db.execute(
                sql: "UPDATE sighting_queue SET state = ? WHERE id = ?",
                arguments: [state.rawValue, id]
            )
        }
    }

    /// Records a failed attempt.
    ///
    /// `attempts` only ever increases and is never reset by a failure, because the give-up
    /// threshold has to be reachable: a row that resets its own counter retries forever, and
    /// a contributor never finds out something is stuck.
    func recordSightingAttempt(
        id: String,
        state: OutboxState,
        error: String?,
        nextAttemptAt: Date?
    ) throws {
        try queue.write { db in
            try db.execute(
                sql: """
                UPDATE sighting_queue
                SET state = ?, attempts = attempts + 1, last_error = ?, next_attempt_at = ?
                WHERE id = ?
                """,
                arguments: [state.rawValue, error, nextAttemptAt?.timeIntervalSince1970, id]
            )
        }
    }

    func setPhotoState(id: String, state: OutboxState) throws {
        try queue.write { db in
            try db.execute(
                sql: "UPDATE photo_queue SET state = ? WHERE id = ?",
                arguments: [state.rawValue, id]
            )
        }
    }

    func recordPhotoAttempt(id: String, state: OutboxState, error: String?) throws {
        try queue.write { db in
            try db.execute(
                sql: "UPDATE photo_queue SET state = ?, attempts = attempts + 1, last_error = ? WHERE id = ?",
                arguments: [state.rawValue, error, id]
            )
        }
    }

    /// Puts a terminally failed row back in the queue, at the contributor's instruction.
    func requeue(sightingID: String) throws {
        try queue.write { db in
            try db.execute(
                sql: """
                UPDATE sighting_queue
                SET state = 'queued', attempts = 0, last_error = NULL, next_attempt_at = NULL
                WHERE id = ?
                """,
                arguments: [sightingID]
            )
            try db.execute(
                sql: """
                UPDATE photo_queue
                SET state = 'queued', attempts = 0, last_error = NULL
                WHERE sighting_id = ? AND state != 'confirmed'
                """,
                arguments: [sightingID]
            )
        }
    }

    // ── Deletion ────────────────────────────────────────────────────────────

    /// Drops an acknowledged row and its photographs.
    ///
    /// Called **only** after the server has confirmed the sighting exists and holds every
    /// one of its photographs — not when an upload call returns, and never on a response the
    /// client could not parse. Deleting earlier is how a sighting disappears with nothing
    /// left to retry from.
    func delete(sightingID: String) throws {
        _ = try queue.write { db in
            try SightingQueueRecord.deleteOne(db, key: sightingID)
        }
    }

    func deletePhoto(id: String) throws {
        _ = try queue.write { db in try PhotoQueueRecord.deleteOne(db, key: id) }
    }

    /// Account deletion. Everything for this owner goes.
    func deleteAll(for userID: String) throws {
        _ = try queue.write { db in
            try SightingQueueRecord.filter(Column("user_id") == userID).deleteAll(db)
            try CachedSightingRecord.filter(Column("user_id") == userID).deleteAll(db)
            try CachedProfileRecord.filter(Column("user_id") == userID).deleteAll(db)
        }
    }

    // ── Cache ───────────────────────────────────────────────────────────────

    func cacheSightings(_ records: [CachedSightingRecord]) throws {
        try queue.write { db in
            for record in records { try record.insert(db, onConflict: .replace) }
        }
    }

    func cacheDetail(_ record: CachedDetailRecord) throws {
        try queue.write { db in try record.insert(db, onConflict: .replace) }
    }

    func cacheProfile(_ record: CachedProfileRecord) throws {
        try queue.write { db in try record.insert(db, onConflict: .replace) }
    }

    func cachedSightings(for userID: String) throws -> [CachedSightingRecord] {
        try queue.read { db in
            try CachedSightingRecord
                .filter(Column("user_id") == userID)
                .order(Column("captured_at").desc)
                .fetchAll(db)
        }
    }

    func cachedDetail(id: String) throws -> CachedDetailRecord? {
        try queue.read { db in try CachedDetailRecord.fetchOne(db, key: id) }
    }

    func cachedProfile(for userID: String) throws -> CachedProfileRecord? {
        try queue.read { db in try CachedProfileRecord.fetchOne(db, key: userID) }
    }

    /// Removes cached rows the server no longer lists for this contributor.
    ///
    /// Scenario 10: a sighting deleted straight out of the database must stop appearing in
    /// the app. Nothing survives in the interface on local authority alone.
    func pruneCachedSightings(userID: String, keeping ids: [String]) throws {
        _ = try queue.write { db in
            try CachedSightingRecord
                .filter(Column("user_id") == userID && !ids.contains(Column("id")))
                .deleteAll(db)
            try db.execute(sql: "DELETE FROM cached_detail WHERE id NOT IN (SELECT id FROM cached_sighting)")
        }
    }

    /// A pragma read, for the durability tests.
    func pragma(_ name: String) throws -> String? {
        try queue.read { db in
            try String.fetchOne(db, sql: "PRAGMA \(name)")
        }
    }
}
