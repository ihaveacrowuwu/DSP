import UIKit

/// The contributor's own history.
///
/// Offline-first: the list comes from local state and is shown immediately, cached or not. A
/// refresh that fails leaves it exactly as it was and says so — never a blank screen and never
/// a spinner over data the app already has.
final class MySightingsViewController: UIViewController {
    private let container: AppContainer
    private let tableView = UITableView(frame: .zero, style: .insetGrouped)
    private let refreshControl = UIRefreshControl()

    /// Everything the device knows about, unfiltered.
    private var allSightings: [ContributorSighting] = []
    /// What the table shows: `allSightings` with `filter` applied.
    private var sightings: [ContributorSighting] = []

    /// Applied locally, so searching keeps working with no connection (NFR7).
    private var filter = SightingFilter() {
        didSet { applyFilter() }
    }

    private let searchController = UISearchController(searchResultsController: nil)
    private var observationTask: Task<Void, Never>?
    private var emptyView: UIView?

    init(container: AppContainer) {
        self.container = container
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    deinit { observationTask?.cancel() }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "My sightings"
        navigationItem.largeTitleDisplayMode = .always
        navigationController?.navigationBar.prefersLargeTitles = true
        view.backgroundColor = .systemBackground

        buildHierarchy()
        installSearchAndFilter()
        observeSightings()
        Task { await refresh() }
    }

    private func buildHierarchy() {
        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(SightingCell.self, forCellReuseIdentifier: SightingCell.reuseIdentifier)
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 88
        // The floating tab bar is inside the safe area, which the automatic inset already
        // accounts for; this is breathing room below the last row rather than clearance.
        // It was 96 to clear a floating capture button that has since moved into the
        // navigation bar.
        tableView.contentInset.bottom = Spacing.lg
        refreshControl.addTarget(self, action: #selector(pullToRefresh), for: .valueChanged)
        tableView.refreshControl = refreshControl
        view.addSubview(tableView)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
    }

    private func observeSightings() {
        observationTask = Task { [weak self] in
            guard let self, let userID = await container.tokens.currentUserID() else { return }
            do {
                for try await rows in container.sightingRepository.mySightingsStream(userID: userID) {
                    allSightings = rows
                    applyFilter()
                }
            } catch {
                // The stream ending is not a user-facing failure: the last rows stay on
                // screen, and pull-to-refresh still works.
            }
        }
    }

    // ── Search and filtering ────────────────────────────────────────────────

    /// The native search field in the navigation item, plus a filter menu beside it.
    ///
    /// `UISearchController` is what gives the search field its glass treatment, its scroll
    /// behaviour and its VoiceOver handling for free — none of which a custom text field in a
    /// header view would have.
    private func installSearchAndFilter() {
        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = false
        searchController.searchBar.placeholder = "Search sightings"
        searchController.searchBar.accessibilityLabel = "Search your sightings"
        navigationItem.searchController = searchController
        // Visible from the start rather than revealed by pulling down: a contributor with
        // hundreds of sightings should not have to discover that search exists.
        navigationItem.preferredSearchBarPlacement = .stacked
        navigationItem.hidesSearchBarWhenScrolling = false

        // Condition also as a scope bar, because activating search takes the whole navigation
        // row: UIKit removes *both* bar-button items and substitutes its own Close button, so
        // while the field is active the filter menu is unreachable. A scope bar is UIKit's own
        // answer to "narrow this search" and is the one filter affordance that survives, so
        // the axis that carries the science stays available while typing.
        //
        // It is a second control over one piece of state, not a second piece of state: both it
        // and the menu write `filter.condition`, and `syncScopeBar()` keeps them agreeing.
        searchController.searchBar.delegate = self
        searchController.searchBar.scopeButtonTitles = Self.scopeTitles
        // `.onSearchActivation`, not the default `.automatic`: the point is to have the control
        // the moment the navigation row is taken away, not once something has been typed.
        searchController.scopeBarActivation = .onSearchActivation
        syncScopeBar()

        // `+` in the top right, which is where Contacts and Calendar have put "add" for
        // years. Apple's newest apps (Mail, Notes on iOS 26) use a floating bottom-right
        // button instead — but neither has a tab bar, so bottom-right is free for them. Ours
        // does, and a floating pill there competed with the tab bar and covered the last row.
        let capture = UIBarButtonItem(
            systemItem: .add,
            primaryAction: UIAction { [weak self] _ in self?.startCapture() }
        )
        capture.accessibilityLabel = "Record a new sighting"
        capture.accessibilityIdentifier = "newSighting"
        navigationItem.rightBarButtonItem = capture

        refreshFilterButton()
    }

    /// The filter lives in the **left** slot.
    ///
    /// Not the conventional place — Files, Photos and Reminders all put view and sort options
    /// top right, and the left slot is normally Back, Cancel or Edit. But this is a tab root,
    /// so there is no Back to collide with, and the right slot now holds `+`; two icons there
    /// alongside a search field is crowded enough to be worse than the unconventional
    /// placement.
    private func refreshFilterButton() {
        navigationItem.leftBarButtonItem = SightingFilterMenu.makeBarButton(
            filter: filter,
            actions: SightingFilterMenu.Actions(
                setCondition: { [weak self] in self?.filter.condition = $0 },
                toggleStatus: { [weak self] status in
                    guard let self else { return }
                    filter = filter.toggling(status)
                },
                setLocationSource: { [weak self] in self?.filter.locationSource = $0 },
                toggleSort: { [weak self] in self?.filter.sort = self?.filter.sort.toggled ?? .newestFirst },
                pickDateRange: { [weak self] in self?.presentDateRangePicker() },
                clear: { [weak self] in
                    guard let self else { return }
                    filter = filter.cleared
                    searchController.searchBar.text = ""
                }
            )
        )
    }

    /// The scope bar's selection, derived from the filter rather than stored beside it.
    private static let scopeTitles = ["Any", "Healthy", "Bleached"]

    private func syncScopeBar() {
        let index: Int = switch filter.condition {
        case .none: 0
        case .healthy: 1
        case .bleached: 2
        }
        let bar = searchController.searchBar
        // Guarded: assigning the index unconditionally re-enters the delegate on every
        // `applyFilter()`, and the filter is what `applyFilter()` was called about.
        if bar.selectedScopeButtonIndex != index { bar.selectedScopeButtonIndex = index }
    }

    private func presentDateRangePicker() {
        let picker = DateRangePickerViewController(from: filter.from, to: filter.to) { [weak self] from, to in
            self?.filter.from = from
            self?.filter.to = to
        }
        present(UINavigationController(rootViewController: picker), animated: true)
    }

    private func applyFilter() {
        sightings = filter.apply(to: allSightings)
        tableView.reloadData()
        // The menu shows the current selection, so it has to be rebuilt when that changes.
        refreshFilterButton()
        syncScopeBar()
        updateFilterSummary()
        updateEmptyState()
    }

    /// "6 of 50", so a filtered list never looks like the whole history.
    private func updateFilterSummary() {
        guard filter.isActive, !sightings.isEmpty else {
            if tableView.tableHeaderView is UILabel { tableView.tableHeaderView = nil }
            return
        }

        let label = UILabel()
        label.text = "\(sightings.count) of \(allSightings.count)"
        label.font = .preferredFont(forTextStyle: .footnote)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .secondaryLabel
        label.textAlignment = .center
        label.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: 32)
        tableView.tableHeaderView = label
    }

    @objc private func pullToRefresh() {
        Task { await refresh() }
    }

    /// Also runs reconciliation implicitly: opening this screen is one of the moments the
    /// sync protocol asks the client to ask the server what it really has.
    private func refresh() async {
        defer { refreshControl.endRefreshing() }
        do {
            try await container.sightingRepository.refreshMySightings()
            await container.backgroundSync.syncNow()
        } catch {
            let apiError = ApiError.from(error)
            guard case .unauthorized = apiError else {
                // A banner rather than a dialogue: the cached list underneath is still
                // perfectly useful and blocking it would be the wrong trade.
                showStaleBanner(offline: apiError == .offline)
                return
            }
        }
    }

    private func showStaleBanner(offline: Bool) {
        let banner = UILabel()
        banner.text = offline
            ? "Offline. Showing what was last read from the server."
            : "Could not reach Muraka. Showing the last known state."
        banner.font = .preferredFont(forTextStyle: .footnote)
        banner.adjustsFontForContentSizeCategory = true
        banner.textColor = ReefPalette.amber
        banner.textAlignment = .center
        banner.numberOfLines = 0
        banner.frame = CGRect(x: 0, y: 0, width: tableView.bounds.width, height: 44)
        tableView.tableHeaderView = banner
    }

    private func updateEmptyState() {
        emptyView?.removeFromSuperview()
        emptyView = nil
        guard sightings.isEmpty else { return }

        // Two different empty states, because they mean different things. Telling a
        // contributor with ninety sightings that they have none, because a filter is set
        // behind a menu, is the kind of small lie that makes an app feel broken.
        let empty: MessageStateView = if filter.isActive {
            MessageStateView(
                title: "Nothing matches",
                body: "None of your \(allSightings.count) "
                    + "sighting\(allSightings.count == 1 ? "" : "s") matches this search. "
                    + "Clear the filter to see them all again.",
                systemImage: "magnifyingglass",
                actionTitle: "Clear the filter",
                action: { [weak self] in
                    guard let self else { return }
                    filter = filter.cleared
                    searchController.searchBar.text = ""
                }
            )
        } else {
            MessageStateView(
                title: "No sightings yet",
                body: "Photograph a reef and Muraka will queue it. It uploads by itself when you "
                    + "have a connection — you can capture all day with no signal.",
                systemImage: "water.waves"
            )
        }
        empty.translatesAutoresizingMaskIntoConstraints = false
        view.insertSubview(empty, aboveSubview: tableView)
        NSLayoutConstraint.activate([
            empty.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            empty.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            empty.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            empty.trailingAnchor.constraint(equalTo: view.trailingAnchor),
        ])
        emptyView = empty
    }

    @objc private func startCapture() {
        let capture = CaptureViewController(container: container)
        let navigation = UINavigationController(rootViewController: capture)
        present(navigation, animated: true)
    }
}

extension MySightingsViewController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_: UITableView, numberOfRowsInSection _: Int) -> Int { sightings.count }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(
            withIdentifier: SightingCell.reuseIdentifier,
            for: indexPath
        )
        (cell as? SightingCell)?.configure(with: sightings[indexPath.row])
        return cell
    }

    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        let detail = SightingDetailViewController(
            container: container,
            sightingID: sightings[indexPath.row].id
        )
        navigationController?.pushViewController(detail, animated: true)
    }
}

extension MySightingsViewController: UISearchBarDelegate {
    func searchBar(_: UISearchBar, selectedScopeButtonIndexDidChange index: Int) {
        filter.condition = switch index {
        case 1: .healthy
        case 2: .bleached
        default: nil
        }
    }
}

extension MySightingsViewController: UISearchResultsUpdating {
    /// No debounce, on purpose.
    ///
    /// Filtering a few hundred rows already in memory is instant, so a debounce would add lag
    /// to something that has none. This would need one if the query went to the server —
    /// which is exactly why it does not.
    func updateSearchResults(for searchController: UISearchController) {
        filter.query = searchController.searchBar.text ?? ""
    }
}
