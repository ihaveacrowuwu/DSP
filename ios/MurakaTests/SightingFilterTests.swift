import Foundation
import Testing
@testable import Muraka

/// The same assertions as `SightingFilterTest.kt`, because a contributor with both phones
/// must get the same results from the same search. The filter is applied locally so that
/// searching works with no connection (NFR7), which makes it ordinary logic and testable
/// exhaustively without a server.
struct SightingFilterTests {
    private let august = Date(timeIntervalSince1970: 1_786_863_600) // 2026-08-15
    private let july = Date(timeIntervalSince1970: 1_782_982_800) // 2026-07-01
    private let june = Date(timeIntervalSince1970: 1_780_304_400) // 2026-06-01

    @Test func anEmptyFilterMatchesEverythingAndIsNotActive() {
        let filter = SightingFilter()
        #expect(!filter.isActive)
        #expect(filter.activeCriteriaCount == 0)
        #expect(filter.apply(to: [bleached(), healthy(), queued()]).count == 3)
    }

    @Test func sortOrderAloneDoesNotCountAsFiltering() {
        // Otherwise the interface would offer to "clear" a filter that filters nothing.
        #expect(!SightingFilter(sort: .oldestFirst).isActive)
    }

    @Test func textSearchMatchesTheSiteName() {
        let filter = SightingFilter(query: "banana")
        #expect(filter.matches(healthy(siteName: "Banana Reef")))
        #expect(!filter.matches(healthy(siteName: "Manta Point")))
    }

    @Test func textSearchMatchesTheNoteCaseInsensitively() {
        #expect(SightingFilter(query: "PATCHY").matches(healthy(note: "North side, patchy")))
    }

    @Test func textSearchMatchesTheCoordinateAsItIsDisplayed() {
        // A contributor answering "the one at 4.1755, 73.50" has nothing else to search for,
        // and the list shows four decimals — so four decimals is what must match.
        let filter = SightingFilter(query: "4.1755")
        #expect(filter.matches(healthy(lat: 4.17552, lon: 73.5093)))
        #expect(!filter.matches(healthy(lat: 6.79221, lon: 73.1944)))
    }

    @Test func textSearchMatchesTheStatusTheContributorCanSee() {
        let filter = SightingFilter(query: "awaiting")
        #expect(filter.matches(healthy(status: .awaitingReview)))
        #expect(!filter.matches(healthy(status: .verifiedByExpert)))
    }

    @Test func aBlankQueryIsNotAFilter() {
        #expect(!SightingFilter(query: "   ").isActive)
        #expect(SightingFilter(query: "   ").matches(healthy()))
    }

    @Test func theDateRangeIsInclusiveAtBothEnds() {
        let filter = SightingFilter(from: july, to: august)
        #expect(filter.matches(healthy(capturedAt: july)))
        #expect(filter.matches(healthy(capturedAt: august)))
        #expect(!filter.matches(healthy(capturedAt: june)))
    }

    @Test func filteringByConditionUsesTheEffectiveLabel() {
        #expect(SightingFilter(condition: .bleached).matches(bleached()))
        #expect(!SightingFilter(condition: .bleached).matches(healthy()))
    }

    @Test func anUnassessedSightingMatchesNoConditionFilter() {
        // It is genuinely neither, rather than being both — a queued sighting the model has
        // not seen must not appear under "Healthy".
        #expect(!SightingFilter(condition: .healthy).matches(queued()))
        #expect(!SightingFilter(condition: .bleached).matches(queued()))
    }

    @Test func statusFilteringAcceptsAnyOfTheSelectedStatuses() {
        let filter = SightingFilter(statuses: [.waitingToUpload, .verifiedByExpert])
        #expect(filter.matches(queued()))
        #expect(filter.matches(healthy(status: .verifiedByExpert)))
        #expect(!filter.matches(healthy(status: .analysing)))
    }

    @Test func togglingAStatusAddsItAndThenRemovesIt() {
        let once = SightingFilter().toggling(.analysing)
        #expect(once.statuses == [.analysing])
        #expect(once.toggling(.analysing).statuses.isEmpty)
    }

    @Test func locationSourceSeparatesAGPSFixFromADroppedPin() {
        #expect(SightingFilter(locationSource: .manualPin).matches(queued()))
        #expect(!SightingFilter(locationSource: .gps).matches(queued()))
    }

    @Test func criteriaCombineTheyDoNotCompete() {
        let filter = SightingFilter(query: "banana", from: july, condition: .bleached)
        #expect(filter.activeCriteriaCount == 3)
        #expect(filter.matches(bleached(siteName: "Banana Reef", capturedAt: august)))
        // Right name, right date, wrong condition.
        #expect(!filter.matches(healthy(siteName: "Banana Reef", capturedAt: august)))
        // Right name, right condition, too early.
        #expect(!filter.matches(bleached(siteName: "Banana Reef", capturedAt: june)))
    }

    @Test func sortingRunsInTheRequestedDirection() {
        let list = [healthy(capturedAt: july), healthy(capturedAt: august), healthy(capturedAt: june)]
        #expect(SightingFilter().apply(to: list).map(\.capturedAt) == [august, july, june])
        #expect(
            SightingFilter(sort: .oldestFirst).apply(to: list).map(\.capturedAt) == [june, july, august]
        )
    }

    @Test func clearingKeepsTheSortOrderButDropsEveryCriterion() {
        let filter = SightingFilter(query: "banana", condition: .bleached, sort: .oldestFirst).cleared
        #expect(!filter.isActive)
        #expect(filter.sort == .oldestFirst)
    }

    // ── Fixtures ────────────────────────────────────────────────────────────

    private func healthy(
        siteName: String? = "Manta Point",
        note: String? = nil,
        capturedAt: Date? = nil,
        lat: Double = 4.1755,
        lon: Double = 73.5093,
        status: SightingDisplayStatus = .awaitingReview
    ) -> ContributorSighting {
        server(Fixture(
            condition: .healthy,
            siteName: siteName,
            note: note,
            capturedAt: capturedAt ?? august,
            lat: lat,
            lon: lon,
            status: status
        ))
    }

    private func bleached(
        siteName: String? = "Manta Point",
        capturedAt: Date? = nil
    ) -> ContributorSighting {
        server(Fixture(
            condition: .bleached,
            siteName: siteName,
            note: nil,
            capturedAt: capturedAt ?? august,
            lat: 4.1755,
            lon: 73.5093,
            status: .awaitingReview
        ))
    }

    /// Everything a fixture varies, as one value.
    ///
    /// Seven positional parameters is a soup where a transposed pair goes unnoticed — and in
    /// a test, a fixture that quietly builds the wrong thing is worse than a failing one.
    private struct Fixture {
        var condition: Condition
        var siteName: String?
        var note: String?
        var capturedAt: Date
        var lat: Double
        var lon: Double
        var status: SightingDisplayStatus
    }

    private func server(_ fixture: Fixture) -> ContributorSighting {
        let (condition, siteName, note) = (fixture.condition, fixture.siteName, fixture.note)
        let (capturedAt, lat, lon, status) = (fixture.capturedAt, fixture.lat, fixture.lon, fixture.status)

        let position = Position(lat: lat, lon: lon)
        var record = Sighting(
            id: "s",
            contributorID: "diver-a",
            position: position,
            locationSource: .gps,
            capturedAt: capturedAt,
            status: .awaitingVerification,
            createdAt: capturedAt
        )
        record.siteName = siteName
        record.note = note
        record.condition = condition

        return ContributorSighting(
            id: "s-\(condition.rawValue)-\(capturedAt.timeIntervalSince1970)-\(lat)",
            capturedAt: capturedAt,
            position: position,
            locationSource: .gps,
            photoCount: 1,
            displayStatus: status,
            server: record,
            serverReadAt: capturedAt
        )
    }

    /// Never sent, so no server record and no assessment.
    private func queued() -> ContributorSighting {
        ContributorSighting(
            id: "queued",
            capturedAt: august,
            position: Position(lat: 4.1755, lon: 73.5093),
            locationSource: .manualPin,
            photoCount: 1,
            displayStatus: .waitingToUpload,
            outboxState: .queued
        )
    }
}
