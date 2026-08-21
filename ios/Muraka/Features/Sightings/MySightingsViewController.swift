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

    private var sightings: [ContributorSighting] = []
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
                    sightings = rows
                    tableView.reloadData()
                    updateEmptyState()
                }
            } catch {
                // The stream ending is not a user-facing failure: the last rows stay on
                // screen, and pull-to-refresh still works.
            }
        }
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

        let empty = MessageStateView(
            title: "No sightings yet",
            body: "Photograph a reef and Muraka will queue it. It uploads by itself when you "
                + "have a connection — you can capture all day with no signal.",
            systemImage: "water.waves"
        )
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
