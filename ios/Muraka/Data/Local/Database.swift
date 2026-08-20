import Foundation
import GRDB

/// The on-device database.
///
/// The defaults trade durability for speed, which is the wrong trade for the only thing
/// standing between a captured sighting and lost data:
///
/// - **WAL**, which `DatabasePool` uses, so a reader (the history screen) never blocks the
///   writer (the capture flow), and a crash mid-write leaves a recoverable log rather than a
///   torn page.
/// - **`synchronous = FULL`**, so a committed transaction has actually reached storage
///   before `enqueue` returns. SQLite's default under WAL is `NORMAL`, which lets the OS
///   buffer a commit — and a phone that dies in that window loses a sighting the contributor
///   watched the app accept.
///
/// Both are asserted by `DurabilityPragmaTests`, because a pragma set in the wrong place
/// silently does nothing, and a durability setting that quietly is not applied is worse than
/// one that was never attempted.
enum MurakaDatabase {
    /// Opens the store, creating and migrating it if needed.
    static func open(at url: URL? = nil) throws -> DatabaseQueue {
        var configuration = Configuration()

        configuration.prepareDatabase { db in
            // Per connection, not per database: there is no other place this can go.
            try db.execute(sql: "PRAGMA synchronous = FULL")
            try db.execute(sql: "PRAGMA journal_mode = WAL")
            // Photo rows are meaningless without their parent sighting.
            try db.execute(sql: "PRAGMA foreign_keys = ON")
        }

        let queue = try DatabaseQueue(path: (url ?? defaultURL()).path, configuration: configuration)
        try migrator.migrate(queue)
        return queue
    }

    /// An in-memory store, for tests.
    static func inMemory() throws -> DatabaseQueue {
        var configuration = Configuration()
        configuration.prepareDatabase { db in
            try db.execute(sql: "PRAGMA foreign_keys = ON")
        }
        let queue = try DatabaseQueue(configuration: configuration)
        try migrator.migrate(queue)
        return queue
    }

    static func defaultURL() throws -> URL {
        let directory = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )
        return directory.appendingPathComponent("muraka.sqlite")
    }

    /// Schema history.
    ///
    /// Migrations are registered rather than the schema being recreated, so a version bump
    /// can never silently drop a contributor's undelivered sightings.
    private static var migrator: DatabaseMigrator {
        var migrator = DatabaseMigrator()

        migrator.registerMigration("v1") { db in
            try db.create(table: "sighting_queue") { table in
                // The client's own UUIDv7. Sent as-is; it is the idempotency key.
                table.primaryKey("id", .text)
                // The account that captured this. A row is only ever uploaded under its
                // owner's session — two people share a boat and a phone more often than you
                // would think, and uploading under the wrong one is corrupt scientific data
                // and an ethics problem.
                table.column("user_id", .text).notNull().indexed()
                table.column("lat", .double).notNull()
                table.column("lon", .double).notNull()
                table.column("location_source", .text).notNull()
                table.column("location_accuracy_m", .double)
                table.column("depth_m", .double)
                // Device time. Translated by ServerClock at upload, once an offset is known.
                table.column("captured_at_device", .double).notNull()
                table.column("note", .text)
                table.column("self_condition", .text)
                table.column("state", .text).notNull().indexed()
                table.column("attempts", .integer).notNull().defaults(to: 0)
                table.column("last_error", .text)
                table.column("next_attempt_at", .double)
                table.column("created_at", .double).notNull().indexed()
            }

            try db.create(table: "photo_queue") { table in
                table.primaryKey("id", .text)
                table.column("sighting_id", .text)
                    .notNull()
                    .indexed()
                    .references("sighting_queue", onDelete: .cascade)
                // App-private storage, never the shared library: a PHPicker asset can be
                // deleted long before the outbox drains.
                table.column("local_path", .text).notNull()
                table.column("ordinal", .integer).notNull()
                table.column("state", .text).notNull().indexed()
                table.column("attempts", .integer).notNull().defaults(to: 0)
                table.column("last_error", .text)
            }

            // Last-known server state — a display cache, never a record.
            try db.create(table: "cached_sighting") { table in
                table.primaryKey("id", .text)
                table.column("user_id", .text).notNull().indexed()
                table.column("lat", .double).notNull()
                table.column("lon", .double).notNull()
                table.column("location_source", .text).notNull()
                table.column("captured_at", .double).notNull().indexed()
                table.column("created_at", .double).notNull()
                table.column("status", .text).notNull()
                table.column("photo_count", .integer).notNull().defaults(to: 0)
                table.column("condition", .text)
                table.column("severity", .double)
                table.column("confidence", .double)
                table.column("verified", .boolean).notNull().defaults(to: false)
                table.column("depth_m", .double)
                table.column("note", .text)
                table.column("site_name", .text)
                // What lets the interface say "checked 20 minutes ago" rather than
                // presenting a stale truth as a current one.
                table.column("read_at", .double).notNull()
            }

            // The full detail response, as the JSON the server sent. A blob because the
            // protocol says a cached record is replaced WHOLESALE on every refresh — never
            // merged, never patched field by field — so there is no merge logic to get
            // wrong and no schema to migrate when the prediction payload grows a field.
            try db.create(table: "cached_detail") { table in
                table.primaryKey("id", .text)
                table.column("json", .blob).notNull()
                table.column("read_at", .double).notNull()
            }

            try db.create(table: "cached_profile") { table in
                table.primaryKey("user_id", .text)
                table.column("json", .blob).notNull()
                table.column("read_at", .double).notNull()
            }
        }

        return migrator
    }
}
