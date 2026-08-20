import BackgroundTasks
import Foundation
import UIKit

/// Asks the system to drain the outbox.
///
/// The protocol wants a drain on app foreground, on connectivity returning, after a capture,
/// on a periodic background task, and on pull-to-refresh. All five funnel through here.
///
/// `BGProcessingTaskRequest` rather than `BGAppRefreshTask`: a refresh task gets about thirty
/// seconds, which is not enough to upload five reef photographs on a hotel Wi-Fi connection.
/// A processing task gets minutes, and `requiresNetworkConnectivity` means the system does
/// not wake us to fail.
@MainActor
final class BackgroundSync {
    static let taskIdentifier = AppConfiguration.backgroundDrainTaskIdentifier

    private let engine: SyncEngine
    private var foregroundTask: Task<SyncOutcome, Never>?

    /// Whether a drain is running right now, for the sync screen's indicator.
    private(set) var isSyncing = false

    init(engine: SyncEngine) {
        self.engine = engine
    }

    /// Registers the handler.
    ///
    /// **Must be called before `application(_:didFinishLaunchingWithOptions:)` returns** —
    /// the system raises an exception otherwise, and it is the one piece of setup that
    /// genuinely has to happen that early.
    func registerTaskHandler() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.taskIdentifier,
            using: nil
        ) { [weak self] task in
            guard let processing = task as? BGProcessingTask else { return task.setTaskCompleted(success: false) }
            Task { @MainActor in await self?.handle(processing) }
        }
    }

    /// Runs a drain now, in the foreground.
    ///
    /// Coalesced: a second call while one is running returns the same result rather than
    /// starting a second pass. Connectivity returning and a screen appearing at the same
    /// moment is the ordinary case, not a rare race.
    @discardableResult
    func syncNow() async -> SyncOutcome {
        if let existing = foregroundTask { return await existing.value }

        isSyncing = true
        let task = Task { await engine.drain() }
        foregroundTask = task

        let outcome = await task.value
        foregroundTask = nil
        isSyncing = false

        // Something is still owed and the network is there: keep the system waking us even
        // if the contributor closes the app.
        if outcome.shouldRetry { scheduleBackgroundDrain() }
        return outcome
    }

    /// Asks the system to wake the app later to finish the queue.
    func scheduleBackgroundDrain() {
        let request = BGProcessingTaskRequest(identifier: Self.taskIdentifier)
        request.requiresNetworkConnectivity = true
        // False: a diver's phone on 20% battery in a dry bag should still deliver reef data,
        // and the work is small because photographs are downscaled before they are queued.
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 60)

        // Throws if the identifier is not in Info.plist or the app is in the background
        // without permission. Neither is recoverable at runtime and neither should crash a
        // contributor's app mid-dive.
        try? BGTaskScheduler.shared.submit(request)
    }

    private func handle(_ task: BGProcessingTask) async {
        // Chain the next one first: if the drain runs long and the system kills us, there is
        // still a request queued to finish the job.
        scheduleBackgroundDrain()

        let drain = Task { await engine.drain() }
        task.expirationHandler = { drain.cancel() }

        let outcome = await drain.value
        task.setTaskCompleted(success: !outcome.offline)
    }
}
