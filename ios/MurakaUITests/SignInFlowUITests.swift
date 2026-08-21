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

    /// Search and filtering, which run entirely on local data so they work offline (NFR7).
    func testSearchingAndFilteringNarrowsTheHistory() throws {
        try skipUnlessStackIsUp()
        signIn()

        let cells = app.tables.cells
        XCTAssertTrue(cells.element(boundBy: 0).waitForExistence(timeout: 15), "seed the stack first")
        let unfiltered = cells.count
        XCTAssertGreaterThan(unfiltered, 1, "the seeded history should have several rows")

        // ── Search ───────────────────────────────────────────────────────────
        let search = app.searchFields.firstMatch
        XCTAssertTrue(search.waitForExistence(timeout: 5), "the search field should be in the navigation bar")
        search.tap()
        // A status word, which every row's pill contains, so the match is predictable.
        search.typeText("Verified")
        attach(name: "06-search")

        // Narrowed, and not to nothing — some seeded sightings are verified.
        let searched = cells.count
        XCTAssertLessThan(searched, unfiltered, "searching should narrow the list")
        XCTAssertGreaterThan(searched, 0, "the seed includes verified sightings")

        // Clearing restores everything, which is the property a contributor actually relies
        // on — a filter you cannot get out of is worse than no filter.
        if app.buttons["Clear text"].exists {
            app.buttons["Clear text"].tap()
        } else {
            search.buttons.firstMatch.tap()
        }
        // Leaving search mode, because an active search controller takes the navigation bar
        // over and the filter button is genuinely not in the hierarchy while it does.
        //
        // iOS 26 labels that button "Close"; earlier versions labelled it "Cancel". Both are
        // checked rather than assumed — the element tree said "Close", which is the only
        // reason this works.
        for label in ["Close", "Cancel"] where app.buttons[label].exists {
            app.buttons[label].tap()
            break
        }
        XCTAssertEqual(cells.count, unfiltered, "clearing the search should restore every row")

        // ── The filter menu ──────────────────────────────────────────────────
        // Not scoped to `navigationBars`: with a stacked search bar the button is not
        // necessarily a descendant of it.
        let filterButton = app.buttons["filters"]
        if !filterButton.waitForExistence(timeout: 5) {
            // Attach the tree rather than just failing: "button not found" on its own sends
            // you guessing at query syntax, which is where the last two attempts went.
            let tree = XCTAttachment(string: app.debugDescription)
            tree.name = "element-tree"
            tree.lifetime = .keepAlways
            add(tree)
            XCTFail("the filter button should be reachable")
        }
        filterButton.tap()
        attach(name: "07-filter-menu")

        app.buttons["Bleached"].tap()

        // The button now reports how many criteria are on, which is what stops a filtered
        // list from looking like the whole history.
        // Same element, addressed by identifier; the label is what changed.
        let activeFilter = app.buttons["filters"]
        XCTAssertTrue(activeFilter.waitForExistence(timeout: 5))
        XCTAssertEqual(
            activeFilter.label,
            "Filters, 1 active",
            "the button should say how many criteria are on"
        )
        attach(name: "08-filtered")

        XCTAssertLessThan(cells.count, unfiltered, "filtering by condition should narrow the list")

        // ── Clearing from the menu ───────────────────────────────────────────
        activeFilter.tap()
        app.buttons["Clear all filters"].tap()
        XCTAssertEqual(
            app.buttons["filters"].label,
            "Filters",
            "clearing should return the button to its inactive state"
        )
        XCTAssertEqual(cells.count, unfiltered)
    }

    /// Every screen in dark mode.
    ///
    /// NFR14 asks for both appearances to be **correct**, not merely to render, so this walks
    /// the same route as the light-mode test with the interface style forced dark. The
    /// screenshots it attaches are the evidence — a dark-mode bug is almost always something
    /// a person has to look at, not something an assertion catches.
    func testEveryScreenInDarkMode() throws {
        try skipUnlessStackIsUp()

        // The appearance comes from the SIMULATOR, set by the caller:
        //
        //     xcrun simctl ui <device> appearance dark
        //
        // A `-UIUserInterfaceStyle Dark` launch argument was the first attempt and it does
        // nothing for a UIKit app — the test passed while every screenshot came out light,
        // which is exactly the kind of test that proves nothing. `make ios-dark-shots` sets
        // the appearance, runs this, and sets it back.
        try XCTSkipUnless(
            app.windows.firstMatch.exists,
            "the app should be running"
        )

        attach(name: "10-dark-sign-in")
        signIn()

        XCTAssertTrue(app.navigationBars["My sightings"].waitForExistence(timeout: 20))
        attach(name: "11-dark-my-sightings")

        app.tabBars.buttons["tab.sync"].tap()
        XCTAssertTrue(app.navigationBars["Sync"].waitForExistence(timeout: 5))
        attach(name: "12-dark-sync")

        app.tabBars.buttons["tab.profile"].tap()
        XCTAssertTrue(app.navigationBars["Profile"].waitForExistence(timeout: 5))
        attach(name: "13-dark-profile")

        app.tabBars.buttons["tab.sightings"].tap()
        let firstCell = app.tables.cells.element(boundBy: 0)
        XCTAssertTrue(firstCell.waitForExistence(timeout: 10))
        firstCell.tap()
        XCTAssertTrue(app.navigationBars["Sighting"].waitForExistence(timeout: 10))
        // The lattice and the photograph arrive asynchronously, and they are the whole point
        // of this screenshot: the condition colours must NOT have followed the appearance
        // into a different meaning.
        sleep(4)
        attach(name: "14-dark-sighting-detail")
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
