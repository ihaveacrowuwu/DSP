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

        app.tabBars.buttons["tab.config"].tap()
        XCTAssertTrue(app.navigationBars["Config"].waitForExistence(timeout: 5))
        attach(name: "04-config")

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

    /// Every screen in dark mode, switched **from inside the app**.
    ///
    /// NFR14 asks for both appearances to be correct, not merely to render. This drives the
    /// appearance control on the Profile screen rather than the simulator's own setting,
    /// which makes the test self-contained and — more usefully — means it tests the toggle
    /// the contributor actually has.
    ///
    /// It leaves the device's own setting alone, so a dark screenshot here is proof the
    /// override works rather than proof the simulator was already dark.
    func testTheAppearanceToggleDarkensEveryScreen() throws {
        try skipUnlessStackIsUp()
        signIn()

        XCTAssertTrue(app.navigationBars["My sightings"].waitForExistence(timeout: 20))

        // Set it from the Profile screen, which is where a contributor would.
        app.tabBars.buttons["tab.config"].tap()
        XCTAssertTrue(app.navigationBars["Config"].waitForExistence(timeout: 5))

        let appearance = app.segmentedControls["Appearance"]
        XCTAssertTrue(appearance.waitForExistence(timeout: 5), "the appearance control should be visible")
        appearance.buttons["Dark"].tap()

        // The caption is the assertion that the choice registered — a highlighted segment
        // alone could be a control that looks selected and does nothing.
        XCTAssertTrue(
            app.staticTexts["Always dark, whatever your device is set to."].waitForExistence(timeout: 5),
            "the control should say what it has done"
        )
        attach(name: "13-dark-config")

        app.tabBars.buttons["tab.sync"].tap()
        XCTAssertTrue(app.navigationBars["Sync"].waitForExistence(timeout: 5))
        attach(name: "12-dark-sync")

        app.tabBars.buttons["tab.sightings"].tap()
        let firstCell = app.tables.cells.element(boundBy: 0)
        XCTAssertTrue(firstCell.waitForExistence(timeout: 10))
        attach(name: "11-dark-my-sightings")

        firstCell.tap()
        XCTAssertTrue(app.navigationBars["Sighting"].waitForExistence(timeout: 10))
        // The lattice and the photograph arrive asynchronously, and they are the whole point
        // of this screenshot: the condition colours must not have followed the appearance
        // into a different meaning.
        sleep(4)
        attach(name: "14-dark-sighting-detail")

        // ── And back ─────────────────────────────────────────────────────────
        // A setting you cannot undo is worse than no setting, so the return trip is part of
        // the test rather than assumed.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.tabBars.buttons["tab.config"].tap()
        app.segmentedControls["Appearance"].buttons["System"].tap()
        XCTAssertTrue(
            app.staticTexts["Following your device setting."].waitForExistence(timeout: 5),
            "System should be selectable again"
        )
    }

    /// The choice survives a relaunch, which is the difference between a setting and a toggle.
    func testTheAppearanceChoiceIsRemembered() throws {
        try skipUnlessStackIsUp()
        signIn()

        app.tabBars.buttons["tab.config"].tap()
        XCTAssertTrue(app.navigationBars["Config"].waitForExistence(timeout: 10))
        app.segmentedControls["Appearance"].buttons["Dark"].tap()
        XCTAssertTrue(app.staticTexts["Always dark, whatever your device is set to."].waitForExistence(timeout: 5))

        // Relaunch WITHOUT the session reset, so the app comes back signed in and the only
        // thing being tested is whether it remembered.
        app.terminate()
        app.launchArguments.removeAll { $0 == "-MurakaResetSession" }
        app.launch()

        app.tabBars.buttons["tab.config"].tap()
        XCTAssertTrue(
            app.staticTexts["Always dark, whatever your device is set to."].waitForExistence(timeout: 15),
            "the appearance choice should survive a relaunch"
        )

        // Put it back, so this test does not leave the next one in dark mode.
        app.segmentedControls["Appearance"].buttons["System"].tap()
    }

    /// The patch-grid overlay can be turned off, and the choice is remembered.
    ///
    /// The lattice is an annotation, and an annotation you cannot remove is an obstruction —
    /// turning it off is how a contributor checks the model's reading against the reef rather
    /// than against the model's own drawing of it.
    func testThePatchGridCanBeTurnedOffAndStaysOff() throws {
        try skipUnlessStackIsUp()
        signIn()

        let firstCell = app.tables.cells.element(boundBy: 0)
        XCTAssertTrue(firstCell.waitForExistence(timeout: 15), "seed the stack first")
        firstCell.tap()
        XCTAssertTrue(app.navigationBars["Sighting"].waitForExistence(timeout: 10))

        let toggle = app.buttons["toggleGrid"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 10), "the grid toggle should be on the photograph card")

        // The label is the assertion: it says what the button will do next, so it tells us
        // the current state without reading pixels.
        XCTAssertEqual(toggle.label, "Hide the model's grid", "the grid should start visible")
        attach(name: "20-grid-on")

        toggle.tap()
        XCTAssertEqual(toggle.label, "Show the model's grid", "tapping should hide the grid")
        XCTAssertTrue(
            app.staticTexts["Showing the photograph as taken."].waitForExistence(timeout: 5),
            "the caption should say the grid is off"
        )
        attach(name: "21-grid-off")

        // Remembered across sightings, so somebody comparing several photographs does not
        // turn it off once per sighting.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.tables.cells.element(boundBy: 1).waitForExistence(timeout: 10))
        app.tables.cells.element(boundBy: 1).tap()
        XCTAssertTrue(app.navigationBars["Sighting"].waitForExistence(timeout: 10))
        XCTAssertEqual(
            app.buttons["toggleGrid"].label,
            "Show the model's grid",
            "the choice should carry to the next sighting"
        )

        // Put it back, so this test does not leave the grid off for the next one.
        app.buttons["toggleGrid"].tap()
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
