import UIKit

/// Application entry point.
///
/// Deliberately thin: nothing here reaches the network or opens the outbox on the main
/// thread at launch, so a cold start on a boat with no signal is instant. The drain loop
/// is registered with `BGTaskScheduler` here because that registration must happen
/// before `application(_:didFinishLaunchingWithOptions:)` returns — the one piece of
/// setup the system genuinely requires this early.
@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(
        _: UIApplication,
        didFinishLaunchingWithOptions _: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        true
    }

    func application(
        _: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options _: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }
}
