import UIKit

/// Builds the window and the root controller in code.
///
/// No storyboard: a UIKit hierarchy written in Swift reviews as a diff, where a XIB
/// reviews as an unreadable XML blob. That matters more than usual on a solo project
/// whose history is part of the submission.
final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?

    func scene(
        _ scene: UIScene,
        willConnectTo _: UISceneSession,
        options _: UIScene.ConnectionOptions
    ) {
        guard let windowScene = scene as? UIWindowScene else { return }

        let window = UIWindow(windowScene: windowScene)
        window.rootViewController = RootViewController()
        window.makeKeyAndVisible()
        self.window = window
    }
}
