import UIKit

/// The queue, made visible.
///
/// This screen exists because `sync-protocol.md` insists pending work must be visible: a
/// silent queue is how a contributor's reef data goes missing without anyone noticing. It is
/// also where a terminally failed row gets its way out — retry, retry smaller, or discard —
/// rather than sitting in a failure the contributor can see but not act on.
final class SyncStatusViewController: UIViewController {
    private let container: AppContainer
    private let tableView = UITableView(frame: .zero, style: .insetGrouped)
    private let refreshControl = UIRefreshControl()

    private var items: [QueuedItem] = []
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
        title = "Sync"
        navigationController?.navigationBar.prefersLargeTitles = true
        view.backgroundColor = .systemBackground

        tableView.translatesAutoresizingMaskIntoConstraints = false
        tableView.dataSource = self
        tableView.delegate = self
        tableView.register(QueueCell.self, forCellReuseIdentifier: QueueCell.reuseIdentifier)
        tableView.rowHeight = UITableView.automaticDimension
        tableView.estimatedRowHeight = 96
        refreshControl.addTarget(self, action: #selector(syncNow), for: .valueChanged)
        tableView.refreshControl = refreshControl
        view.addSubview(tableView)

        NSLayoutConstraint.activate([
            tableView.topAnchor.constraint(equalTo: view.topAnchor),
            tableView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            tableView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            tableView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])

        observeQueue()
    }

    private func observeQueue() {
        observationTask = Task { [weak self] in
            guard let self, let userID = await container.tokens.currentUserID() else { return }
            do {
                for try await rows in container.outboxRepository.queueStream(userID: userID) {
                    items = rows
                    tableView.reloadData()
                    updateEmptyState()
                }
            } catch {
                // The queue itself is still on disk; only the live updates stopped.
            }
        }
    }

    /// Pull-to-refresh: one of the five triggers the protocol asks for.
    @objc private func syncNow() {
        Task {
            await container.backgroundSync.syncNow()
            refreshControl.endRefreshing()
        }
    }

    private func updateEmptyState() {
        emptyView?.removeFromSuperview()
        emptyView = nil
        guard items.isEmpty else { return }

        let empty = MessageStateView(
            title: "Everything is delivered",
            // Careful wording: this says the QUEUE is empty, which the client does know,
            // rather than that everything synced, which only the server can say (D21).
            body: "Nothing is waiting to upload. Statuses on each sighting come from the "
                + "server, not from this device.",
            systemImage: "checkmark.icloud"
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
}

extension SyncStatusViewController: UITableViewDataSource, UITableViewDelegate {
    func tableView(_: UITableView, numberOfRowsInSection _: Int) -> Int { items.count }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(
            withIdentifier: QueueCell.reuseIdentifier,
            for: indexPath
        )
        (cell as? QueueCell)?.configure(with: items[indexPath.row])
        return cell
    }

    /// A failed row must never be a dead end.
    ///
    /// `sync-protocol.md` is explicit: never retry silently forever, and always give the
    /// contributor something to do. "Retry smaller" is the only thing that helps a `413`,
    /// which retrying unchanged cannot.
    func tableView(
        _: UITableView,
        trailingSwipeActionsConfigurationForRowAt indexPath: IndexPath
    ) -> UISwipeActionsConfiguration? {
        let item = items[indexPath.row]
        guard item.state == .failed else { return nil }

        let retry = UIContextualAction(style: .normal, title: "Retry") { [weak self] _, _, done in
            Task { try? await self?.container.outboxRepository.retry(sightingID: item.sightingID); done(true) }
        }

        let smaller = UIContextualAction(style: .normal, title: "Retry smaller") { [weak self] _, _, done in
            Task {
                do {
                    try await self?.container.outboxRepository.retryWithSmallerPhotos(
                        sightingID: item.sightingID
                    )
                    done(true)
                } catch {
                    self?.presentMessage(
                        title: "Could not resize",
                        message: "The photographs could not be made smaller."
                    )
                    done(false)
                }
            }
        }
        smaller.backgroundColor = ReefPalette.amber

        let discard = UIContextualAction(style: .destructive, title: "Discard") { [weak self] _, _, done in
            Task { try? await self?.container.outboxRepository.discard(sightingID: item.sightingID); done(true) }
        }

        return UISwipeActionsConfiguration(actions: [retry, smaller, discard])
    }
}

/// One row of the queue.
final class QueueCell: UITableViewCell {
    static let reuseIdentifier = "QueueCell"

    private let column = UIStackView()
    private let titleLabel = UILabel()
    private let readoutRow = UIStackView()
    private let errorLabel = UILabel()
    private let nextAttemptLabel = UILabel()
    private let progress = UIProgressView(progressViewStyle: .default)

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)

        titleLabel.font = .preferredFont(forTextStyle: .headline)
        titleLabel.adjustsFontForContentSizeCategory = true

        readoutRow.axis = .horizontal
        readoutRow.spacing = 20
        readoutRow.alignment = .top

        errorLabel.font = .preferredFont(forTextStyle: .caption1)
        errorLabel.adjustsFontForContentSizeCategory = true
        errorLabel.textColor = ReefPalette.rust
        errorLabel.numberOfLines = 0

        nextAttemptLabel.font = .preferredFont(forTextStyle: .caption2)
        nextAttemptLabel.adjustsFontForContentSizeCategory = true
        nextAttemptLabel.textColor = .secondaryLabel

        column.axis = .vertical
        column.spacing = 8
        column.translatesAutoresizingMaskIntoConstraints = false
        [titleLabel, readoutRow, progress, errorLabel, nextAttemptLabel]
            .forEach(column.addArrangedSubview)
        contentView.addSubview(column)

        NSLayoutConstraint.activate([
            column.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            column.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            column.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            column.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func configure(with item: QueuedItem) {
        readoutRow.arrangedSubviews.forEach { $0.removeFromSuperview() }

        titleLabel.text = "Captured \(RelativeTime.describe(item.capturedAt))"

        readoutRow.addArrangedSubview(StatusPillView(status: SightingDisplayStatus.of(
            outboxState: item.state,
            serverStatus: nil
        )))
        readoutRow.addArrangedSubview(ReadoutView(
            caption: "Photographs",
            value: "\(item.photosSent)/\(item.photosTotal)",
            style: .footnote
        ))
        if item.attempts > 0 {
            readoutRow.addArrangedSubview(ReadoutView(
                caption: "Attempts",
                value: "\(item.attempts)",
                style: .footnote
            ))
        }
        readoutRow.addArrangedSubview(UIView())

        progress.isHidden = item.state != .sending
        progress.progress = item.photosTotal > 0
            ? Float(item.photosSent) / Float(item.photosTotal)
            : 0

        errorLabel.text = item.lastError
        errorLabel.isHidden = item.lastError == nil

        if item.state == .failed {
            nextAttemptLabel.text = "Swipe for Retry, Retry smaller, or Discard."
            nextAttemptLabel.isHidden = false
        } else if let next = item.nextAttemptAt {
            nextAttemptLabel.text = "Next attempt \(RelativeTime.describe(next))"
            nextAttemptLabel.isHidden = false
        } else {
            nextAttemptLabel.isHidden = true
        }
    }
}
