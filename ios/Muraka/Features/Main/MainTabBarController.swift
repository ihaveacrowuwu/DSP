import UIKit

/// The signed-in app.
///
/// Three tabs and a capture action. The feature set is deliberately small - review, maps and
/// administration live in the dashboard, not on the phone - and keeping it that way is what
/// makes the capture flow fit in under 60 seconds and 8 taps (NFR6).
///
/// The badge on the Sync tab is not decoration: `sync-protocol.md` asks for pending work to be
/// permanently visible, because a silent queue is how reef data goes missing unnoticed.
final class MainTabBarController: UITabBarController {
    private let container: AppContainer
    private let onSignedOut: () -> Void
    private var badgeTask: Task<Void, Never>?

    init(container: AppContainer, onSignedOut: @escaping () -> Void) {
        self.container = container
        self.onSignedOut = onSignedOut
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    deinit { badgeTask?.cancel() }

    override func viewDidLoad() {
        super.viewDidLoad()

        let sightings = MySightingsViewController(container: container)
        sightings.tabBarItem = UITabBarItem(
            title: "Sightings",
            image: UIImage(systemName: "water.waves"),
            selectedImage: UIImage(systemName: "water.waves")
        )

        let sync = SyncStatusViewController(container: container)
        sync.tabBarItem = UITabBarItem(
            title: "Sync",
            image: UIImage(systemName: "icloud.and.arrow.up"),
            selectedImage: UIImage(systemName: "icloud.and.arrow.up.fill")
        )

        let config = ConfigViewController(container: container, onSignedOut: onSignedOut)
        config.tabBarItem = UITabBarItem(
            title: "Config",
            image: UIImage(systemName: "slider.horizontal.3"),
            selectedImage: UIImage(systemName: "slider.horizontal.3")
        )

        // Explicit identifiers so UI tests address the tabs by name rather than by index or
        // by their visible title, which is localisable and which iOS may abbreviate.
        sightings.tabBarItem.accessibilityIdentifier = "tab.sightings"
        sync.tabBarItem.accessibilityIdentifier = "tab.sync"
        config.tabBarItem.accessibilityIdentifier = "tab.config"

        viewControllers = [sightings, sync, config].map(UINavigationController.init(rootViewController:))
        observePendingCount()
    }

    /// Keeps the Sync tab's badge showing how much is still owed to the server.
    private func observePendingCount() {
        badgeTask = Task { [weak self] in
            guard let self, let userID = await container.tokens.currentUserID() else { return }

            do {
                for try await count in container.outboxRepository.pendingCountStream(userID: userID) {
                    viewControllers?[1].tabBarItem.badgeValue = count > 0 ? "\(count)" : nil
                }
            } catch {
                // The badge is an affordance, not a source of truth. If the observation
                // stops, the sync screen still shows the queue itself.
            }
        }
    }
}
