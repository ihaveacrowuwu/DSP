import XCTest

/// Smoke test: the app launches and draws its first screen.
///
/// Cheap, and it catches the class of failure - a bad Info.plist, a missing scene delegate,
/// a crash in the dependency graph - that no unit test can see.
final class LaunchUITests: XCTestCase {
    @MainActor
    func testAppLaunchesToItsFirstScreen() {
        let app = XCUIApplication()
        app.launchArguments += ["-MurakaResetSession"]
        app.launch()

        // Signed out, so the first screen is sign-in. Asserting on the field rather than on
        // the title, because the title is also what a launch screen would show - and a test
        // that passes on a launch screen proves nothing about the app having started.
        XCTAssertTrue(
            app.textFields["Email"].waitForExistence(timeout: 15),
            "the app should launch to the sign-in form"
        )
    }
}
