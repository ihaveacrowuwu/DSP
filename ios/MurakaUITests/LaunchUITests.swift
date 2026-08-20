import XCTest

/// Smoke test: the app launches and draws something. Cheap, and it catches the class of
/// failure — a bad Info.plist, a missing scene delegate — that unit tests cannot see.
final class LaunchUITests: XCTestCase {
    func testAppLaunches() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.staticTexts["Muraka"].waitForExistence(timeout: 10))
    }
}
