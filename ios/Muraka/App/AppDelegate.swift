import BackgroundTasks
import UIKit

/// Application entry point.
///
/// Deliberately thin: nothing here reaches the network or opens the outbox on the main thread,
/// so a cold start on a boat with no signal is instant. The one thing that genuinely has to
/// happen this early is the background-task registration, because the system raises an
/// exception if it arrives after `didFinishLaunchingWithOptions` returns.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    /// The graph. Held here because it must outlive any one scene.
    private(set) lazy var container = AppContainer.live()

    func application(
        _: UIApplication,
        didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        resetSessionForUITestsIfRequested()
        container.backgroundSync.registerTaskHandler()
        // Idempotent: a request already queued is simply replaced, so calling this on every
        // launch does not accumulate work.
        container.backgroundSync.scheduleBackgroundDrain()
        return true
    }

    /// Clears the stored session on launch, for a **debug-only** launch argument.
    ///
    /// UI tests have to start from a known state, and the Keychain deliberately outlives an
    /// app reinstall — which is correct behaviour and exactly what makes a test that assumes
    /// a signed-out app fail on its second run.
    ///
    /// Only the session goes. The outbox is untouched, because a flag that also wiped queued
    /// sightings would be a footgun aimed at the one thing this app must not lose.
    private func resetSessionForUITestsIfRequested() {
        #if DEBUG
            guard ProcessInfo.processInfo.arguments.contains("-MurakaResetSession") else { return }
            let tokens = container.tokens
            // Synchronously, before any scene connects, or the first screen is chosen from a
            // session this is about to delete.
            let done = DispatchSemaphore(value: 0)
            Task { await tokens.clear(); done.signal() }
            _ = done.wait(timeout: .now() + 2)
        #endif
    }

    func application(
        _: UIApplication,
        configurationForConnecting session: UISceneSession,
        options _: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: session.role)
    }
}
