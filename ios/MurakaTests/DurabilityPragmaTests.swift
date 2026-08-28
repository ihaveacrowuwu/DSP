import Foundation
import GRDB
import Testing
@testable import Muraka

/// The outbox is the only thing between a captured sighting and lost data, so
/// `sync-protocol.md` asks for WAL journalling with `synchronous = FULL`.
///
/// These read the pragmas back out of a real on-disk SQLite file rather than trusting that
/// setting them worked, because **a pragma applied in the wrong place silently does nothing**
/// - and a durability setting that quietly is not applied is worse than one that was never
/// attempted. The Android app has the same three assertions in `DurabilityPragmaTest.kt`.
struct DurabilityPragmaTests {
    /// On disk, not in memory: WAL is meaningless for an in-memory database, so an
    /// in-memory test here would pass while proving nothing.
    private func withTemporaryDatabase(_ body: (DatabaseQueue) throws -> Void) throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("muraka-durability-\(UUID().uuidString).sqlite")
        defer {
            for suffix in ["", "-wal", "-shm"] {
                try? FileManager.default.removeItem(
                    at: url.deletingLastPathComponent()
                        .appendingPathComponent(url.lastPathComponent + suffix)
                )
            }
        }
        try body(try MurakaDatabase.open(at: url))
    }

    @Test func journalsWriteAheadSoAReaderNeverBlocksTheCaptureFlow() throws {
        try withTemporaryDatabase { queue in
            let mode = try queue.read { try String.fetchOne($0, sql: "PRAGMA journal_mode") }
            #expect(mode?.lowercased() == "wal")
        }
    }

    @Test func commitsReachTheStorageMediumBeforeEnqueueReturns() throws {
        try withTemporaryDatabase { queue in
            // 2 is FULL. SQLite's default under WAL is 1 (NORMAL), which lets the OS buffer a
            // commit - and a phone that dies in that window loses a sighting the contributor
            // watched the app accept. If this reads 1, the durability claim in the project is
            // not real.
            let synchronous = try queue.read { try Int.fetchOne($0, sql: "PRAGMA synchronous") }
            #expect(synchronous == 2)
        }
    }

    @Test func foreignKeysAreEnforcedSoAPhotoCannotOutliveItsSighting() throws {
        try withTemporaryDatabase { queue in
            let enabled = try queue.read { try Int.fetchOne($0, sql: "PRAGMA foreign_keys") }
            #expect(enabled == 1)
        }
    }

    @Test func aPhotoCannotBeQueuedWithoutItsSighting() throws {
        try withTemporaryDatabase { queue in
            let orphan = PhotoQueueRecord(
                id: UUIDv7.generate(),
                sightingID: "a-sighting-that-does-not-exist",
                localPath: "/tmp/nothing.jpg",
                ordinal: 0,
                state: OutboxState.queued.rawValue
            )
            // The pragma above made this a database-level guarantee rather than a convention.
            #expect(throws: (any Error).self) {
                try queue.write { try orphan.insert($0) }
            }
        }
    }

    @Test func deletingASightingTakesItsPhotographsWithIt() throws {
        try withTemporaryDatabase { queue in
            let store = OutboxStore(queue: queue)
            let sightingID = UUIDv7.generate()

            try queue.write { db in
                try SightingQueueRecord(
                    id: sightingID,
                    userID: "diver-a",
                    lat: 4.1755,
                    lon: 73.5093,
                    locationSource: LocationSource.gps.rawValue,
                    capturedAtDevice: Date().timeIntervalSince1970,
                    state: OutboxState.queued.rawValue,
                    createdAt: Date().timeIntervalSince1970
                ).insert(db)

                try PhotoQueueRecord(
                    id: UUIDv7.generate(),
                    sightingID: sightingID,
                    localPath: "/tmp/one.jpg",
                    ordinal: 0,
                    state: OutboxState.queued.rawValue
                ).insert(db)
            }

            #expect(try queue.read { try PhotoQueueRecord.fetchCount($0) } == 1)
            _ = store
            try queue.write { db in _ = try SightingQueueRecord.deleteOne(db, key: sightingID) }
            #expect(try queue.read { try PhotoQueueRecord.fetchCount($0) } == 0)
        }
    }
}
