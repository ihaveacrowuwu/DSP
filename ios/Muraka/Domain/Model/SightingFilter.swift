import Foundation

/// Which end of the history the contributor wants to see first.
enum SightingSort: CaseIterable, Sendable {
    case newestFirst
    case oldestFirst

    var label: String {
        switch self {
        case .newestFirst: "Newest first"
        case .oldestFirst: "Oldest first"
        }
    }

    var toggled: SightingSort { self == .newestFirst ? .oldestFirst : .newestFirst }
}

/// What the contributor is looking for in their own history.
///
/// Applied **locally**, to the merged list, and that is the whole design: a contributor on a
/// boat can search and filter everything the device knows about, with no network at all. The
/// API does support `from`/`to`/`condition` query parameters, and using them would have been
/// the obvious thing - but then filtering would stop working the moment the signal did, on a
/// screen whose entire purpose is to work offline (NFR7).
///
/// Deliberately the same fields and the same semantics as `SightingFilter.kt` on Android,
/// written from the same reasoning rather than translated - a contributor with both phones
/// must get the same results from the same search.
struct SightingFilter: Equatable, Sendable {
    /// Free text. Matches the site name, the note and the coordinate.
    var query: String = ""
    /// Inclusive lower bound on capture time.
    var from: Date?
    /// Inclusive upper bound on capture time.
    var to: Date?
    /// Nil means any condition.
    var condition: Condition?
    /// Empty means any status. Filters on what the contributor *sees*, not on wire values.
    var statuses: Set<SightingDisplayStatus> = []
    /// Nil means either. Researchers care about this distinction, and so might a diver.
    var locationSource: LocationSource?
    var sort: SightingSort = .newestFirst

    /// Whether anything is actually being filtered - sort order alone does not count.
    var isActive: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty
            || from != nil
            || to != nil
            || condition != nil
            || !statuses.isEmpty
            || locationSource != nil
    }

    /// How many criteria are on, for a badge on the filter control.
    var activeCriteriaCount: Int {
        [
            !query.trimmingCharacters(in: .whitespaces).isEmpty,
            from != nil || to != nil,
            condition != nil,
            !statuses.isEmpty,
            locationSource != nil,
        ].count { $0 }
    }

    var cleared: SightingFilter { SightingFilter(sort: sort) }

    /// Toggles one status in or out of the set.
    func toggling(_ status: SightingDisplayStatus) -> SightingFilter {
        var copy = self
        if copy.statuses.contains(status) {
            copy.statuses.remove(status)
        } else {
            copy.statuses.insert(status)
        }
        return copy
    }

    func matches(_ sighting: ContributorSighting) -> Bool {
        matchesQuery(sighting)
            && matchesDate(sighting)
            && matchesCondition(sighting)
            && matchesStatus(sighting)
            && matchesLocationSource(sighting)
    }

    /// Filters and sorts in one pass.
    func apply(to sightings: [ContributorSighting]) -> [ContributorSighting] {
        let matching = sightings.filter(matches)
        return switch sort {
        case .newestFirst: matching.sorted { $0.capturedAt > $1.capturedAt }
        case .oldestFirst: matching.sorted { $0.capturedAt < $1.capturedAt }
        }
    }

    // -- The individual criteria ---------------------------------------------

    /// Free text over the site name, the note and the coordinate.
    ///
    /// The coordinate is included because it is often the only thing that distinguishes two
    /// sightings from the same dive, and a contributor reading a researcher's email about
    /// "the one at 4.17, 73.50" has nothing else to search for. Matched against the same
    /// four-decimal rendering the list shows, so what is on screen is what is searchable.
    private func matchesQuery(_ sighting: ContributorSighting) -> Bool {
        let needle = query.trimmingCharacters(in: .whitespaces).lowercased()
        guard !needle.isEmpty else { return true }

        let haystacks = [
            sighting.server?.siteName,
            sighting.server?.note,
            String(format: "%.4f, %.4f", sighting.position.lat, sighting.position.lon),
            sighting.displayStatus.label,
        ].compactMap { $0 }

        return haystacks.contains { $0.lowercased().contains(needle) }
    }

    /// Bounds are compared against **capture** time, not the time the server received it.
    ///
    /// A diver looking for "last Tuesday" means the day they were in the water, which for a
    /// sighting that sat in the outbox for a week is not the day it was uploaded.
    private func matchesDate(_ sighting: ContributorSighting) -> Bool {
        if let from, sighting.capturedAt < from { return false }
        if let to, sighting.capturedAt > to { return false }
        return true
    }

    /// The **effective** condition - an expert's label where one exists, otherwise the
    /// model's. A sighting with no assessment yet matches no condition filter, because it
    /// genuinely is neither rather than being both.
    private func matchesCondition(_ sighting: ContributorSighting) -> Bool {
        condition == nil || sighting.server?.condition == condition
    }

    private func matchesStatus(_ sighting: ContributorSighting) -> Bool {
        statuses.isEmpty || statuses.contains(sighting.displayStatus)
    }

    private func matchesLocationSource(_ sighting: ContributorSighting) -> Bool {
        locationSource == nil || sighting.locationSource == locationSource
    }
}

extension SightingDisplayStatus: Hashable {}
