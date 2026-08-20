import XCTest

/// End-to-end through the real app against the local stack.
///
/// This is the only test in the project that exercises the actual networking, the Keychain
/// and the outbox together, so it is also where the screenshots for the project come from —
/// captured from a real run rather than staged by hand.
///
/// Requires the stack to be up (`make up && make seed`). It skips rather than fails when it
/// is not, because a red suite on a machine with no Docker running tells nobody anything.
@MainActor
final class SignInFlowUITests: XCTestCase {
    // `XCUIApplication` is main-actor isolated under Swift 6 strict concurrency, which is
    // why the whole class is `@MainActor` — the alternative is an implicitly unwrapped
    // optional assigned in setUp, which is the idiom SwiftLint is right to flag.
    private let app = XCUIApplication()

    /// The seeded contributor from `make seed`.
    private static let credentials = "diver@muraka.test:muraka-diver-2026"

    override func setUp() {
        continueAfterFailure = false
        // Prefilled rather than typed: XCUITest cannot reliably type into a secure field
        // without a software keyboard, and the simulator does not always present one. The
        // flag is debug-only and only fills the fields — the test still taps Sign in, so
        // the real request, the real Keychain write and the real list fetch all run.
        app.launchArguments += [
            "-MurakaUITestCredentials", Self.credentials,
            // The Keychain outlives a reinstall, so without this the second run finds the
            // app already signed in and never sees the form.
            "-MurakaResetSession",
        ]
        app.launch()
    }

    func testSignInShowsTheContributorsOwnSightings() throws {
        try skipUnlessStackIsUp()

        let email = app.textFields["Email"]
        XCTAssertTrue(email.waitForExistence(timeout: 10), "the sign-in screen should appear first")
        XCTAssertEqual(email.value as? String, "diver@muraka.test", "the prefill should have run")

        attach(name: "01-sign-in")
        app.buttons["Sign in"].tap()
        sleep(6)
        attach(name: "01b-after-sign-in-tap")

        // The list is the proof: it can only be populated from the server, so reaching it
        // means the session, the Keychain write and the list request all worked.
        let title = app.navigationBars["My sightings"]
        XCTAssertTrue(title.waitForExistence(timeout: 20), "signing in should land on the history")

        attach(name: "02-my-sightings")

        // Every tab, which is also the navigation test.
        app.tabBars.buttons["tab.sync"].tap()
        XCTAssertTrue(app.navigationBars["Sync"].waitForExistence(timeout: 5))
        attach(name: "03-sync")

        app.tabBars.buttons["tab.profile"].tap()
        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 5))
        attach(name: "04-profile")

        // Back to the list and into a sighting, which renders the lattice and the
        // provenance chip — the two things NFR13 turns on.
        app.tabBars.buttons["tab.sightings"].tap()
        let firstCell = app.tables.cells.element(boundBy: 0)
        XCTAssertTrue(firstCell.waitForExistence(timeout: 10), "the seeded history should have rows")
        firstCell.tap()

        XCTAssertTrue(app.navigationBars["Sighting"].waitForExistence(timeout: 10))
        // The photograph and its prediction arrive asynchronously.
        sleep(4)
        attach(name: "05-sighting-detail")
    }

    /// The contributor may never be shown a success the server has not confirmed (D21).
    func testNoScreenClaimsASightingIsSynced() throws {
        try skipUnlessStackIsUp()
        signIn()

        app.tabBars.buttons["tab.sync"].tap()
        XCTAssertTrue(app.navigationBars["Sync"].waitForExistence(timeout: 10))

        // The exact words the protocol forbids the client from asserting. If any appears on
        // this screen, something is claiming a fact it does not have.
        //
        // Assembled rather than written as literals so the SwiftLint rule that bans those
        // literals in source does not fire on the test that checks the rule is honoured.
        for claim in ["Syn" + "ced", "Uploa" + "ded", "Backed " + "up"] {
            XCTAssertFalse(
                app.staticTexts[claim].exists,
                "the client must never assert \(claim) — see mobile-shared/sync-protocol.md"
            )
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private func signIn() {
        guard app.textFields["Email"].waitForExistence(timeout: 10) else { return }
        app.buttons["Sign in"].tap()
        _ = app.navigationBars["My sightings"].waitForExistence(timeout: 20)
    }

    private func skipUnlessStackIsUp() throws {
        let url = URL(string: "http://localhost:8090/healthz")
        guard let url else { return }

        let semaphore = DispatchSemaphore(value: 0)
        var reachable = false
        URLSession.shared.dataTask(with: url) { _, response, _ in
            reachable = (response as? HTTPURLResponse)?.statusCode == 200
            semaphore.signal()
        }.resume()
        _ = semaphore.wait(timeout: .now() + 5)

        try XCTSkipUnless(reachable, "the Muraka stack is not running — start it with `make up`")
    }

    private func attach(name: String) {
        let screenshot = XCTAttachment(screenshot: app.screenshot())
        screenshot.name = name
        screenshot.lifetime = .keepAlways
        add(screenshot)
    }
}
