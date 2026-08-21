import UIKit

/// The account, and the contributor's totals.
///
/// The totals come from `GET /v1/me` and are never computed from local rows. A client-side
/// tally drifts the moment anything is rejected, verified or anonymised, and the number the
/// contributor sees would then disagree with the dashboard — D21 again.
final class ProfileViewController: UIViewController {
    private let container: AppContainer
    private let onSignedOut: () -> Void

    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let nameLabel = UILabel()
    private let emailLabel = UILabel()
    private let totalsRow = UIStackView()
    private let messageLabel = UILabel()

    init(container: AppContainer, onSignedOut: @escaping () -> Void) {
        self.container = container
        self.onSignedOut = onSignedOut
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Profile"
        navigationController?.navigationBar.prefersLargeTitles = true
        view.backgroundColor = .systemBackground
        buildHierarchy()
        Task { await load() }
    }

    private func buildHierarchy() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(scrollView)
        // iOS 26's tab bar floats over content rather than sitting below it, so a scroll
        // view pinned to the bottom of the window ends underneath it. The safe-area inset
        // already accounts for the bar; this makes the scrollable content respect it.
        scrollView.contentInsetAdjustmentBehavior = .always
        scrollView.contentInset.bottom = 24

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 16
        stack.alignment = .fill
        scrollView.addSubview(stack)

        nameLabel.font = .preferredFont(forTextStyle: .title1)
        nameLabel.adjustsFontForContentSizeCategory = true

        emailLabel.font = .preferredFont(forTextStyle: .body)
        emailLabel.adjustsFontForContentSizeCategory = true
        emailLabel.textColor = .secondaryLabel

        let totalsHeading = UILabel()
        totalsHeading.text = "Your contributions"
        totalsHeading.font = .preferredFont(forTextStyle: .headline)
        totalsHeading.adjustsFontForContentSizeCategory = true

        totalsRow.axis = .horizontal
        totalsRow.distribution = .fillEqually
        totalsRow.spacing = 12

        let totalsNote = UILabel()
        totalsNote.text = "Counted by the server, not by this device."
        totalsNote.font = .preferredFont(forTextStyle: .caption1)
        totalsNote.adjustsFontForContentSizeCategory = true
        totalsNote.textColor = .secondaryLabel
        totalsNote.numberOfLines = 0

        messageLabel.font = .preferredFont(forTextStyle: .footnote)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = ReefPalette.amber
        messageLabel.numberOfLines = 0
        messageLabel.isHidden = true

        let refresh = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        refresh.setTitle("Refresh totals", for: .normal)
        refresh.addTarget(self, action: #selector(refreshTotals), for: .touchUpInside)

        let signOutButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        signOutButton.setTitle("Sign out", for: .normal)
        signOutButton.addTarget(self, action: #selector(signOutTapped), for: .touchUpInside)

        let signOutNote = UILabel()
        // Reassurance that matters on a shared boat phone: signing out does not throw away
        // work that has not been delivered.
        signOutNote.text = "Signing out keeps anything still waiting to upload. It will be "
            + "sent when you sign back in."
        signOutNote.font = .preferredFont(forTextStyle: .caption1)
        signOutNote.adjustsFontForContentSizeCategory = true
        signOutNote.textColor = .secondaryLabel
        signOutNote.numberOfLines = 0

        // Destructive, so tinted with the signal colour rather than the accent.
        let delete = UIButton(configuration: GlassSurface.makeDestructiveButtonConfiguration())
        delete.setTitle("Delete my account", for: .normal)
        delete.addTarget(self, action: #selector(confirmDelete), for: .touchUpInside)

        [nameLabel, emailLabel, totalsHeading, totalsRow, totalsNote, messageLabel,
         refresh, signOutButton, signOutNote, delete].forEach(stack.addArrangedSubview)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -16),
        ])
    }

    private func load() async {
        // Cached first, so the screen has content immediately even with no connection.
        if let userID = await container.tokens.currentUserID(),
           let cached = try? await container.authRepository.cachedProfile(userID: userID) {
            apply(cached)
        }
        await refreshFromServer()
    }

    @objc private func refreshTotals() {
        Task { await refreshFromServer() }
    }

    private func refreshFromServer() async {
        do {
            apply(try await container.authRepository.refreshProfile())
            messageLabel.isHidden = true
        } catch {
            // Totals from the last successful read stay on screen; they are simply labelled
            // as what they are.
            messageLabel.text = "Could not refresh your totals. Showing the last known figures."
            messageLabel.isHidden = false
        }
    }

    private func apply(_ profile: Profile) {
        nameLabel.text = profile.user.displayName
        emailLabel.text = profile.user.email

        totalsRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        // Every one of these comes from GET /v1/me. Counting local rows would drift the
        // moment anything is verified or rejected.
        totalsRow.addArrangedSubview(ReadoutView(caption: "Total", value: "\(profile.stats.total)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Verified", value: "\(profile.stats.verified)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Pending", value: "\(profile.stats.pending)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Rejected", value: "\(profile.stats.rejected)"))
    }

    @objc private func signOutTapped() {
        Task { [self] in
            await container.authRepository.signOut()
            onSignedOut()
        }
    }

    /// NFR15, stated **before** the confirmation rather than after it.
    ///
    /// The sightings are scientific record; what is deleted is the link to the person, not
    /// the science.
    @objc private func confirmDelete() {
        let alert = UIAlertController(
            title: "Delete your account?",
            message: "Your sightings will not be deleted. They stay in the scientific record "
                + "under an anonymous contributor, so the reef data researchers have already "
                + "used remains valid.\n\nWhat is removed is the link between those sightings "
                + "and you: your name, your email, and your account. This cannot be undone.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "Keep my account", style: .cancel))
        alert.addAction(UIAlertAction(title: "Delete and anonymise", style: .destructive) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                do {
                    try await self.container.authRepository.deleteAccount()
                    self.onSignedOut()
                } catch {
                    self.presentMessage(
                        title: "Could not delete",
                        message: "The account could not be deleted. Try again when you have a connection."
                    )
                }
            }
        })
        present(alert, animated: true)
    }
}
