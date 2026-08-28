import UIKit

/// Builds the filter control for the history screen.
///
/// A `UIMenu` on a navigation bar button, not a custom panel. The menu is what iOS uses for
/// exactly this - a set of independent toggles behind one control - and it comes with the
/// glass treatment, the checkmarks, the keyboard support and the VoiceOver behaviour already
/// done. A hand-built sheet would start non-compliant on all four.
///
/// Android gets chips instead, and that is not an inconsistency: Material's filter chips are
/// the same idea in that platform's idiom. What matches across the two is the *filter* and
/// its wording, not the control.
/// `@MainActor` because every `UIAction` handler is main-actor isolated, and the callbacks
/// here close over view-controller state. Isolating the builder is the correct answer rather
/// than making the closures `Sendable` - this is UIKit, it only ever runs on the main actor,
/// and pretending otherwise would be silencing the warning rather than answering it.
@MainActor
enum SightingFilterMenu {
    /// The statuses worth offering.
    ///
    /// `.checking` is deliberately absent: it is a transient "we do not know yet" that
    /// resolves within a request, so a filter for it would empty itself while the
    /// contributor was looking at it.
    static let filterableStatuses: [SightingDisplayStatus] = [
        .waitingToUpload, .photosPending, .analysing,
        .awaitingReview, .verifiedByExpert, .notUsable, .failed,
    ]

    /// Everything the menu needs to talk back to the screen.
    @MainActor
    struct Actions {
        var setCondition: (Condition?) -> Void
        var toggleStatus: (SightingDisplayStatus) -> Void
        var setLocationSource: (LocationSource?) -> Void
        var toggleSort: () -> Void
        var pickDateRange: () -> Void
        var clear: () -> Void
    }

    static func makeMenu(filter: SightingFilter, actions: Actions) -> UIMenu {
        var sections: [UIMenuElement] = [
            conditionSection(filter, actions),
            statusSection(filter, actions),
            positionSection(filter, actions),
            dateAndSortSection(filter, actions),
        ]

        if filter.isActive {
            sections.append(
                UIMenu(options: .displayInline, children: [
                    UIAction(
                        title: "Clear all filters",
                        image: UIImage(systemName: "xmark.circle"),
                        attributes: .destructive
                    ) { _ in actions.clear() },
                ])
            )
        }

        return UIMenu(children: sections)
    }

    /// The bar button, with a badge on its symbol when anything is filtered.
    ///
    /// The badge matters: a contributor who has filtered and scrolled away must still be able
    /// to tell that what they are looking at is not everything.
    static func makeBarButton(filter: SightingFilter, actions: Actions) -> UIBarButtonItem {
        let symbol = filter.isActive
            ? "line.3.horizontal.decrease.circle.fill"
            : "line.3.horizontal.decrease.circle"

        let item = UIBarButtonItem(
            image: UIImage(systemName: symbol),
            menu: makeMenu(filter: filter, actions: actions)
        )
        // A stable identifier as well as a label. The label is what VoiceOver reads and it
        // changes as filters are applied; the identifier is what tests address, and querying
        // by a label that moves is how a UI test ends up chasing its own tail.
        item.accessibilityIdentifier = "filters"
        item.accessibilityLabel = filter.isActive
            ? "Filters, \(filter.activeCriteriaCount) active"
            : "Filters"
        return item
    }

    // -- Sections ------------------------------------------------------------

    private static func conditionSection(_ filter: SightingFilter, _ actions: Actions) -> UIMenu {
        let items = Condition.allCases.map { condition in
            UIAction(
                title: condition == .healthy ? "Healthy" : "Bleached",
                state: filter.condition == condition ? .on : .off
            ) { _ in
                actions.setCondition(filter.condition == condition ? nil : condition)
            }
        }
        return UIMenu(title: "Condition", options: .displayInline, children: items)
    }

    private static func statusSection(_ filter: SightingFilter, _ actions: Actions) -> UIMenu {
        let items = filterableStatuses.map { status in
            UIAction(
                title: status.label,
                state: filter.statuses.contains(status) ? .on : .off
            ) { _ in actions.toggleStatus(status) }
        }
        // Nested rather than inline: seven statuses inline would push everything else off
        // the bottom of the menu.
        return UIMenu(title: "Status", image: UIImage(systemName: "circle.dashed"), children: items)
    }

    private static func positionSection(_ filter: SightingFilter, _ actions: Actions) -> UIMenu {
        let items = LocationSource.allCases.map { source in
            UIAction(
                title: source == .gps ? "GPS fix" : "Dropped pin",
                state: filter.locationSource == source ? .on : .off
            ) { _ in
                actions.setLocationSource(filter.locationSource == source ? nil : source)
            }
        }
        return UIMenu(title: "Position", image: UIImage(systemName: "location"), children: items)
    }

    private static func dateAndSortSection(_ filter: SightingFilter, _ actions: Actions) -> UIMenu {
        UIMenu(options: .displayInline, children: [
            UIAction(
                title: dateRangeLabel(filter),
                image: UIImage(systemName: "calendar")
            ) { _ in actions.pickDateRange() },
            UIAction(
                title: filter.sort.label,
                image: UIImage(systemName: "arrow.up.arrow.down")
            ) { _ in actions.toggleSort() },
        ])
    }

    static func dateRangeLabel(_ filter: SightingFilter) -> String {
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none

        return switch (filter.from, filter.to) {
        case (nil, nil): "Any date"
        case let (from?, to?): "\(formatter.string(from: from)) - \(formatter.string(from: to))"
        case let (from?, nil): "From \(formatter.string(from: from))"
        case let (nil, to?): "Until \(formatter.string(from: to))"
        }
    }
}
