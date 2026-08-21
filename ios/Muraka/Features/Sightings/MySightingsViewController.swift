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
    private let captureButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.primary))

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
        // Room for the floating capture button, which would otherwise cover the last row —
        // and on a short history, every row.
        tableView.contentInset.bottom = 96
        refreshControl.addTarget(self, action: #selector(pullToRefresh), for: .valueChanged)
        tableView.refreshControl = refreshControl
        view.addSubview(tableView)

        // Glass, because this is chrome floating over content — exactly what it is for.
        captureButton.configuration?.title = "New sighting"
        captureButton.configuration?.image = UIImage(systemName: "camera.fill")
        captureButton.configuration?.imagePadding = 8
        captureButton.translatesAutoresizingMaskIntoConstraints = false
        captureButton.addTarget(self, action: #selector(startCapture), for: .touchUpInside)
        captureButton.accessibilityLabel = "Record a new sighting"
        view.addSubview(captureButton)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            captureButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            captureButton.bottomAnchor.constraint(
                equalTo: view.safeAreaLayoutGuide.bottomAnchor,
                constant: -16
            ),
            captureButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 50),
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

        refreshFilterButton()
    }

    private func refreshFilterButton() {
        navigationItem.rightBarButtonItem = SightingFilterMenu.makeBarButton(
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
