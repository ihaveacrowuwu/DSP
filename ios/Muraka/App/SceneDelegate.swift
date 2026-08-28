import UIKit

/// Builds the window and the root controller in code.
///
/// No storyboard: a UIKit hierarchy written in Swift reviews as a diff, where a XIB reviews as
/// an unreadable XML blob. That matters more than usual on a solo project whose history is
/// part of the submission.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    private var root: RootViewController?

    func scene(
        _ scene: UIScene,
        willConnectTo _: UISceneSession,
        options _: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene,
              let delegate = UIApplication.shared.delegate as? AppDelegate
        else { return }

        let root = RootViewController(container: delegate.container)
        self.root = root

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = root
        // The accent is set on the window so every system control inherits it, which is the
        // whole point of a tint rather than colouring controls one at a time.
        window.tintColor = ReefPalette.accent
        window.makeKeyAndVisible()
        self.window = window

        applyThemePreference()
        // The profile screen changes the setting; the window is the only thing that can act
        // on it for the whole app, so it listens rather than being reached into.
        NotificationCenter.default.addObserver(
            self,
            selector: #selector(applyThemePreference),
            name: .murakaThemePreferenceChanged,
            object: nil
        )
    }

    @objc private func applyThemePreference() {
        guard let delegate = UIApplication.shared.delegate as? AppDelegate else { return }
        let style = delegate.container.appearanceStore.preference.interfaceStyle

        // Animated, because an instant inversion of the whole screen is jarring - and
        // because UIKit will cross-fade this for free if asked.
        UIView.animate(withDuration: 0.25) { [weak self] in
            self?.window?.overrideUserInterfaceStyle = style
        }
    }

    /// Trigger: app foreground. One of the five the sync protocol asks for.
    func sceneDidBecomeActive(_: UIScene) {
        root?.syncOnForeground()
    }
}
