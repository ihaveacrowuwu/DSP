import Foundation
import GRDB
import UIKit

/// The dependency graph, assembled once at launch.
///
/// A hand-written container rather than a DI framework: the graph is small, the wiring is
/// readable in one screen, and it adds no third-party dependency. Android uses Hilt because
/// that is the platform-idiomatic choice there — the *testability* property is identical
/// either way, and that property is the point.
@MainActor
final class AppContainer {
    let tokens: TokenStore
    let serverClock: ServerClock
    let sessionEvents: SessionEvents
    let api: APIClient
    let outbox: OutboxStore
    let photos: PhotoStore

    let authRepository: AuthRepository
    let sightingRepository: SightingRepository
    let outboxRepository: OutboxRepository
    let syncEngine: SyncEngine
    let backgroundSync: BackgroundSync
    let locationProvider: LocationProvider

    private init(database: DatabaseQueue) {
        tokens = TokenStore()
        serverClock = ServerClock()
        sessionEvents = SessionEvents()
        outbox = OutboxStore(queue: database)
        photos = PhotoStore()

        api = APIClient(
            tokens: tokens,
            serverClock: serverClock,
            sessionEvents: sessionEvents
        )

        authRepository = AuthRepository(api: api, outbox: outbox, photos: photos, tokens: tokens)
        sightingRepository = SightingRepository(api: api, outbox: outbox, photos: photos, tokens: tokens)
        outboxRepository = OutboxRepository(outbox: outbox, photos: photos, tokens: tokens)

        syncEngine = SyncEngine(
            api: api,
            outbox: outbox,
            photos: photos,
            tokens: tokens,
            serverClock: serverClock
        )
        backgroundSync = BackgroundSync(engine: syncEngine)
        locationProvider = LocationProvider()
    }

    /// The real graph, on the on-disk store.
    static func live() -> AppContainer {
        // A failure here means the store is unusable. Falling back to memory would be worse
        // than crashing: the outbox is the only thing between a captured sighting and lost
        // data, and an in-memory outbox silently discards every sighting on the next launch.
        // Better to fail visibly than to accept reef data we cannot keep.
        guard let database = try? MurakaDatabase.open() else {
            fatalError("the Muraka outbox could not be opened, and a sighting queued in memory would be lost")
        }
        return AppContainer(database: database)
    }

    /// An in-memory graph, for tests.
    static func inMemory() throws -> AppContainer {
        AppContainer(database: try MurakaDatabase.inMemory())
    }
}
