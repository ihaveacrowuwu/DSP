import UIKit

/// The account and the app's settings.
///
/// Four titled cards in a deliberate order: who you are, what you have contributed, how the
/// app looks, and — kept last and kept apart — the two actions that end something. Grouping
/// matters more here than anywhere else in the app, because "sign out" and "delete my
/// account" sitting in an undifferentiated column of controls is how somebody taps the wrong
/// one.
///
/// Named "Config" at the user's request. Both platforms conventionally call this "Settings" —
/// Apple's HIG and Material both use that word — so if a design review flags it, the title
/// here and the tab item in `MainTabBarController` are the two places to change.
final class ConfigViewController: UIViewController {
    private let container: AppContainer
    private let onSignedOut: () -> Void

    private let scrollView = UIScrollView()
    private let stack = UIStackView()

    private let nameLabel = UILabel()
    private let emailLabel = UILabel()
    private let totalsRow = UIStackView()
    private let messageLabel = UILabel()
    private let appearanceControl = UISegmentedControl(
        items: ThemePreference.allCases.map(\.label)
    )
    private let appearanceExplanation = UILabel()

    init(container: AppContainer, onSignedOut: @escaping () -> Void) {
        self.container = container
        self.onSignedOut = onSignedOut
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Config"
        navigationController?.navigationBar.prefersLargeTitles = true
        view.backgroundColor = .systemGroupedBackground
        buildHierarchy()
        Task { await load() }
    }

    private func buildHierarchy() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.contentInsetAdjustmentBehavior = .always
        scrollView.contentInset.bottom = Spacing.listBottom
        view.addSubview(scrollView)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = Spacing.md
        scrollView.addSubview(stack)

        stack.addArrangedSubview(makeAccountCard())
        stack.addArrangedSubview(makeContributionsCard())
        stack.addArrangedSubview(makeAppearanceCard())
        stack.addArrangedSubview(makeSessionCard())

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: Spacing.md),
            stack.bottomAnchor.constraint(
                equalTo: scrollView.contentLayoutGuide.bottomAnchor,
                constant: -Spacing.xxl
            ),
            stack.leadingAnchor.constraint(
                equalTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: Spacing.lg
            ),
            stack.trailingAnchor.constraint(
                equalTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -Spacing.lg
            ),
        ])
    }

    // ── Cards ───────────────────────────────────────────────────────────────

    private func makeAccountCard() -> UIView {
        let card = SectionCardView(title: "Account")

        nameLabel.font = .preferredFont(forTextStyle: .title2)
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.numberOfLines = 0
        card.addRow(nameLabel)

        emailLabel.font = .preferredFont(forTextStyle: .body)
        emailLabel.adjustsFontForContentSizeCategory = true
        emailLabel.textColor = .secondaryLabel
        emailLabel.numberOfLines = 0
        card.addRow(emailLabel)

        return card
    }

    private func makeContributionsCard() -> UIView {
        let card = SectionCardView(title: "Your contributions")

        totalsRow.axis = .horizontal
        totalsRow.distribution = .fillEqually
        totalsRow.spacing = Spacing.md
        totalsRow.alignment = .top
        card.addRow(totalsRow)

        card.addRow(SectionCardView.caption("Counted by the server, not by this device."))

        messageLabel.font = .preferredFont(forTextStyle: .footnote)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = ReefPalette.amber
        messageLabel.numberOfLines = 0
        messageLabel.isHidden = true
        card.addRow(messageLabel)

        let refresh = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        refresh.setTitle("Refresh totals", for: .normal)
        refresh.addTarget(self, action: #selector(refreshTotals), for: .touchUpInside)
        card.addRow(refresh)

        return card
    }

    /// The appearance toggle.
    ///
    /// A `UISegmentedControl`, which is the UIKit control for a small set of mutually
    /// exclusive options where all of them should be visible at once. Words rather than
    /// symbols: a symbol cannot distinguish "System" from "Light", and UIKit shows one or the
    /// other per segment.
    private func makeAppearanceCard() -> UIView {
        let card = SectionCardView(title: "Appearance")

        for (index, preference) in ThemePreference.allCases.enumerated() {
            appearanceControl.setAction(
                UIAction(title: preference.label) { [weak self] _ in
                    self?.selectAppearance(preference)
                },
                forSegmentAt: index
            )
        }
        appearanceControl.accessibilityLabel = "Appearance"
        appearanceControl.selectedSegmentIndex =
            ThemePreference.allCases.firstIndex(of: container.appearanceStore.preference) ?? 0
        card.addRow(appearanceControl)

        appearanceExplanation.font = .preferredFont(forTextStyle: .caption1)
        appearanceExplanation.adjustsFontForContentSizeCategory = true
        appearanceExplanation.textColor = .secondaryLabel
        appearanceExplanation.numberOfLines = 0
        appearanceExplanation.text = container.appearanceStore.preference.explanation
        card.addRow(appearanceExplanation)

        return card
    }

    /// Signing out, and deleting the account.
    ///
    /// Deliberately the last card, and deliberately the only one holding actions that end
    /// something. The two are separated by their own explanation rather than sitting side by
    /// side, because they are not the same size of decision.
    private func makeSessionCard() -> UIView {
        let card = SectionCardView(title: "Session")

        let signOut = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        signOut.setTitle("Sign out", for: .normal)
        signOut.addTarget(self, action: #selector(signOutTapped), for: .touchUpInside)
        card.addRow(signOut)

        card.addRow(SectionCardView.caption(
            // Reassurance that matters on a shared boat phone.
            "Signing out keeps anything still waiting to upload. It will be sent when you "
                + "sign back in."
        ))

        let delete = UIButton(configuration: GlassSurface.makeDestructiveButtonConfiguration())
        delete.setTitle("Delete my account", for: .normal)
        delete.addTarget(self, action: #selector(confirmDelete), for: .touchUpInside)
        card.addRow(delete)

        return card
    }

    // ── Behaviour ───────────────────────────────────────────────────────────

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
        // moment anything is verified or rejected (D21).
        totalsRow.addArrangedSubview(ReadoutView(caption: "Total", value: "\(profile.stats.total)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Verified", value: "\(profile.stats.verified)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Pending", value: "\(profile.stats.pending)"))
        totalsRow.addArrangedSubview(ReadoutView(caption: "Rejected", value: "\(profile.stats.rejected)"))
    }

    /// Applies the choice, and tells the window so the change reaches the whole app.
    private func selectAppearance(_ preference: ThemePreference) {
        container.appearanceStore.preference = preference
        appearanceExplanation.text = preference.explanation
        NotificationCenter.default.post(name: .murakaThemePreferenceChanged, object: nil)
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
