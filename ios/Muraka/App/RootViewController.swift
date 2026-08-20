import UIKit

/// One decision only: signed in or not.
///
/// Note what happens on sign-out — the app returns to sign-in, and **the outbox is
/// untouched**. Queued sightings belong to the account that captured them and wait for that
/// account to come back, which is what stops one diver's reef data uploading under whoever
/// borrows the phone next.
final class RootViewController: UIViewController {
    private let container: AppContainer
    private var current: UIViewController?
    private var sessionEventTask: Task<Void, Never>?

    init(container: AppContainer) {
        self.container = container
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        observeSessionEvents()
        Task { await refreshSession() }
    }

    /// Re-reads who is signed in and swaps the interface to match.
    func refreshSession() async {
        switch await container.authRepository.sessionState() {
        case .signedIn:
            show(MainTabBarController(container: container, onSignedOut: { [weak self] in
                Task { await self?.refreshSession() }
            }))
            container.backgroundSync.syncNowDetached()

        case .signedOut, .unknown:
            show(SignInViewController(container: container, onSignedIn: { [weak self] in
                Task { await self?.refreshSession() }
            }))
        }
    }

    func syncOnForeground() {
        container.backgroundSync.syncNowDetached()
    }

    /// A refresh that failed for good ends the session from underneath whatever screen the
    /// contributor is on, so the swap has to be driven from here rather than from a screen.
    private func observeSessionEvents() {
        sessionEventTask = Task { [weak self] in
            guard let events = self?.container.sessionEvents.events else { return }
            for await event in events {
                guard let self else { return }
                await refreshSession()

                if case .accountDisabled = event {
                    presentMessage(
                        title: "Account suspended",
                        message: "An administrator has suspended this account. Anything waiting to "
                            + "upload is still on this device."
                    )
                }
            }
        }
    }

    private func show(_ controller: UIViewController) {
        if let current, type(of: current) == type(of: controller) { return }

        current?.willMove(toParent: nil)
        current?.view.removeFromSuperview()
        current?.removeFromParent()

        addChild(controller)
        controller.view.frame = view.bounds
        controller.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(controller.view)
        controller.didMove(toParent: self)
        current = controller
    }
}

extension BackgroundSync {
    /// Fire-and-forget drain, for the triggers where nothing waits on the result.
    func syncNowDetached() {
        Task { await syncNow() }
    }
}
