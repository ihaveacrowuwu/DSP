import UIKit

/// One sighting.
///
/// Built from titled cards rather than one long column: a photograph, what the model made of
/// it, where and when it was taken, and what an expert said. Grouping is the difference
/// between a screen a contributor can read at a glance and a list of facts they have to sort
/// out for themselves.
///
/// Refreshes on open, which is the read-back that turns "Checking..." into whatever the server
/// actually says.
final class SightingDetailViewController: UIViewController {
    private let container: AppContainer
    private let sightingID: String

    private let scrollView = UIScrollView()
    private let stack = UIStackView()

    /// Kept so the toggle can show and hide the overlay without rebuilding the screen.
    private var latticeViews: [PatchLatticeView] = []
    private var gridCaption: UILabel?

    init(container: AppContainer, sightingID: String) {
        self.container = container
        self.sightingID = sightingID
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Sighting"
        // Grouped, because the screen is a stack of cards and the grouped background is what
        // makes a card read as raised rather than as an arbitrary grey rectangle.
        view.backgroundColor = .systemGroupedBackground

        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.contentInsetAdjustmentBehavior = .always
        // The tab bar floats over content on iOS 26, so the last card needs room to clear it.
        scrollView.contentInset.bottom = Spacing.listBottom
        view.addSubview(scrollView)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = Spacing.md
        scrollView.addSubview(stack)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: view.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: view.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: Spacing.md),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -Spacing.xxl),
            stack.leadingAnchor.constraint(
                equalTo: scrollView.frameLayoutGuide.leadingAnchor,
                constant: Spacing.lg
            ),
            stack.trailingAnchor.constraint(
                equalTo: scrollView.frameLayoutGuide.trailingAnchor,
                constant: -Spacing.lg
            ),
        ])

        Task { await load() }
    }

    private func load() async {
        // Cached first, so there is content immediately even with no connection.
        await render()
        try? await container.sightingRepository.refreshSighting(id: sightingID)
        await render()
    }

    private func render() async {
        guard let userID = await container.tokens.currentUserID(),
              let detail = try? await container.sightingRepository.sighting(
                  id: sightingID,
                  userID: userID
              )
        else { return }

        stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        latticeViews.removeAll()
        gridCaption = nil

        stack.addArrangedSubview(makeStatusHeader(detail.summary))

        // Photographs still on the device, so a queued sighting is not a blank screen while
        // it waits for a connection. No lattice: the model has not seen these yet.
        for path in detail.pendingPhotoPaths {
            let card = SectionCardView(title: "Waiting to upload")
            let frame = makePhotoFrame()
            if let image = UIImage(contentsOfFile: path.path) {
                let imageView = UIImageView(image: image)
                imageView.contentMode = .scaleAspectFit
                imageView.accessibilityLabel = "A photograph waiting to upload"
                fill(frame, with: imageView)
            }
            card.addRow(frame)
            stack.addArrangedSubview(card)
        }

        for (index, photo) in detail.photos.enumerated() {
            stack.addArrangedSubview(makePhotographCard(
                photo: photo,
                index: index,
                total: detail.photos.count
            ))
            if let prediction = photo.prediction {
                stack.addArrangedSubview(makeAssessmentCard(
                    prediction: prediction,
                    verified: detail.summary.server?.verified == true
                ))
            }
        }

        stack.addArrangedSubview(makeWhereAndWhenCard(detail.summary))

        if !detail.verifications.isEmpty {
            let card = SectionCardView(title: "Expert review")
            detail.verifications.forEach { card.addRow(makeVerificationRow($0)) }
            stack.addArrangedSubview(card)
        }
    }

    /// Status and how fresh it is - the two things read first, so they sit above the cards.
    private func makeStatusHeader(_ summary: ContributorSighting) -> UIView {
        let row = UIStackView(arrangedSubviews: [StatusPillView(status: summary.displayStatus)])
        row.axis = .horizontal
        row.spacing = Spacing.sm
        row.alignment = .center

        if let readAt = summary.serverReadAt {
            let checked = UILabel()
            // The age of the KNOWLEDGE, not of the sighting.
            checked.text = "checked \(RelativeTime.describe(readAt))"
            checked.font = .preferredFont(forTextStyle: .footnote)
            checked.adjustsFontForContentSizeCategory = true
            checked.textColor = .secondaryLabel
            row.addArrangedSubview(checked)
        }
        row.addArrangedSubview(UIView())
        return row
    }

    /// A photograph, with the lattice over it and a toggle for the lattice.
    ///
    /// The toggle exists because the lattice is an annotation, and an annotation you cannot
    /// remove is an obstruction. Turning it off is how a contributor checks the model's
    /// reading against the reef rather than against the model's own drawing of it - which is
    /// the whole argument for drawing the grid in the first place.
    private func makePhotographCard(photo: Photo, index: Int, total: Int) -> UIView {
        let hasPrediction = photo.prediction != nil
        let showGrid = container.appearanceStore.showPatchGrid

        var toggle: UIButton?
        if hasPrediction {
            let button = UIButton()
            button.accessibilityIdentifier = "toggleGrid"
            button.addTarget(self, action: #selector(toggleGrid), for: .touchUpInside)
            applyGridToggleAppearance(to: button, showing: showGrid)
            toggle = button
        }

        // Always titled, even for a single photograph: the card carries the grid toggle in
        // its title row, and a header holding nothing but a right-aligned button reads as a
        // gap rather than as a control belonging to something.
        let card = SectionCardView(
            title: total > 1 ? "Photograph \(index + 1) of \(total)" : "Photograph",
            trailing: toggle
        )

        let frame = makePhotoFrame()
        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFit
        imageView.accessibilityLabel = "Reef photograph"
        fill(frame, with: imageView)

        // Photograph bytes are NOT a public URL: `GET /v1/photos/{id}/image` needs the bearer
        // token, so this goes through the API client rather than a URL handed to a loader.
        Task { [weak imageView] in
            guard let data = try? await container.sightingRepository.photoData(id: photo.id) else { return }
            imageView?.image = UIImage(data: data)
        }

        if let prediction = photo.prediction {
            let lattice = PatchLatticeView(mode: .overlay)
            lattice.configure(patches: prediction.patches, grid: prediction.patchGrid)
            lattice.isHidden = !showGrid
            fill(frame, with: lattice)
            latticeViews.append(lattice)
        }
        card.addRow(frame)

        if hasPrediction {
            let caption = SectionCardView.caption(gridCaptionText(showing: showGrid))
            gridCaption = caption
            card.addRow(caption)
        }

        return card
    }

    private func gridCaptionText(showing: Bool) -> String {
        showing
            ? "The grid is the model's own reading, cell by cell. Turn it off to see the reef."
            : "Showing the photograph as taken."
    }

    /// The toggle's two states, made obvious.
    ///
    /// The **fill** carries the state, not the symbol. An outline-versus-filled variant of the
    /// same glyph is what this had first, and at 24pt on a glass button the two were
    /// indistinguishable - a toggle whose state you cannot read is a button that appears to do
    /// nothing. Accent-filled when the grid is on, plain glass when it is off.
    private func applyGridToggleAppearance(to button: UIButton, showing: Bool) {
        button.configuration = GlassSurface.makeButtonConfiguration(showing ? .primary : .secondary)
        button.configuration?.image = UIImage(systemName: "square.grid.3x3")
        button.configuration?.contentInsets = NSDirectionalEdgeInsets(
            top: 10, leading: 10, bottom: 10, trailing: 10
        )
        button.accessibilityLabel = showing ? "Hide the model's grid" : "Show the model's grid"
    }

    /// Flips the overlay without rebuilding the screen, so the scroll position survives.
    @objc private func toggleGrid(_ sender: UIButton) {
        let showing = !container.appearanceStore.showPatchGrid
        container.appearanceStore.showPatchGrid = showing

        applyGridToggleAppearance(to: sender, showing: showing)
        gridCaption?.text = gridCaptionText(showing: showing)

        UIView.animate(withDuration: 0.2) { [weak self] in
            self?.latticeViews.forEach { $0.isHidden = !showing }
            self?.latticeViews.forEach { $0.alpha = showing ? 1 : 0 }
        }
    }

    /// What the model made of the photograph.
    ///
    /// Severity leads: "6% bleached" tells a contributor something "bleached" does not, and it
    /// is the number researchers work with. The provenance chip sits beside it rather than
    /// below, because the two are one claim and separating them is how a model label gets read
    /// as fact.
    private func makeAssessmentCard(prediction: Prediction, verified: Bool) -> UIView {
        let card = SectionCardView(title: "Assessment")

        let extent = UILabel()
        extent.text = "\(Int((prediction.severity * 100).rounded()))% bleached"
        extent.font = ReadoutView.monospaced(.title3, weight: .semibold)
        extent.adjustsFontForContentSizeCategory = true

        let headline = UIStackView(arrangedSubviews: [
            SeveritySwatchView(severity: prediction.severity),
            extent,
            ProvenanceChipView(verified: verified),
            UIView(),
        ])
        headline.axis = .horizontal
        headline.spacing = Spacing.sm
        headline.alignment = .center
        card.addRow(headline)

        var readouts: [UIView] = [
            ReadoutView(
                caption: "Confidence",
                value: "\(Int((prediction.confidence * 100).rounded()))%"
            ),
            ReadoutView(caption: "Grid", value: "\(prediction.patchGrid)×\(prediction.patchGrid)"),
        ]
        if let inference = prediction.inferenceMs {
            readouts.append(ReadoutView(caption: "Inference", value: "\(inference) ms"))
        }
        card.addRow(SectionCardView.readoutRow(readouts))

        // Provenance: `fake-0.0.0` means no trained model is loaded yet, so a reader can
        // tell which screenshots predate the real one.
        card.addRow(ReadoutView(caption: "Model", value: prediction.modelVersion, style: .footnote))

        return card
    }

    private func makeWhereAndWhenCard(_ summary: ContributorSighting) -> UIView {
        let card = SectionCardView(title: "Where and when")

        var readouts: [UIView] = [
            ReadoutView(
                caption: summary.locationSource == .gps ? "GPS" : "Dropped pin",
                value: String(format: "%.5f, %.5f", summary.position.lat, summary.position.lon)
            ),
        ]
        if let depth = summary.server?.depthM {
            readouts.append(ReadoutView(caption: "Depth", value: "\(Int(depth.rounded())) m"))
        }
        card.addRow(SectionCardView.readoutRow(readouts))

        card.addRow(SectionCardView.caption("Captured \(RelativeTime.describe(summary.capturedAt))"))

        if let site = summary.server?.siteName {
            card.addRow(ReadoutView(caption: "Site", value: site))
        }

        if let note = summary.server?.note {
            let label = UILabel()
            label.text = note
            label.font = .preferredFont(forTextStyle: .body)
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
            card.addRow(label)
        }

        return card
    }

    private func makeVerificationRow(_ verification: Verification) -> UIView {
        let column = UIStackView()
        column.axis = .vertical
        column.spacing = Spacing.xs

        let decision = UILabel()
        decision.text = switch verification.decision {
        case .confirmed: "Confirmed the model"
        case .corrected: "Corrected the model"
        case .rejected: "Rejected this photograph"
        }
        decision.font = .preferredFont(forTextStyle: .body)
        decision.adjustsFontForContentSizeCategory = true
        decision.numberOfLines = 0

        let row = UIStackView(arrangedSubviews: [
            ProvenanceChipView(verified: true), decision, UIView(),
        ])
        row.axis = .horizontal
        row.spacing = Spacing.sm
        row.alignment = .center
        column.addArrangedSubview(row)

        // Rejected sightings vanish from research views but remain the contributor's own
        // record (FR11), so the reason is shown rather than hidden.
        if let reason = verification.rejectReason {
            column.addArrangedSubview(
                SectionCardView.caption("Reason: \(reason.readable)", colour: ReefPalette.rust)
            )
        }
        if let comment = verification.comment {
            let label = UILabel()
            label.text = comment
            label.font = .preferredFont(forTextStyle: .body)
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
            column.addArrangedSubview(label)
        }
        column.addArrangedSubview(
            SectionCardView.caption(RelativeTime.describe(verification.createdAt))
        )

        return column
    }

    /// One fixed square frame for every photograph.
    ///
    /// The dashboard learnt this the hard way (D24): sizing each frame to its source made the
    /// 224 px dataset crops too small to judge, and made two photographs of the same reef look
    /// like different sizes of thing. A square also matches the centre square the server
    /// tiles, so the lattice lands where the model actually looked.
    private func makePhotoFrame() -> UIView {
        let frame = UIView()
        frame.backgroundColor = .tertiarySystemGroupedBackground
        frame.layer.cornerRadius = Spacing.md
        frame.layer.cornerCurve = .continuous
        frame.clipsToBounds = true
        frame.translatesAutoresizingMaskIntoConstraints = false
        frame.widthAnchor.constraint(equalTo: frame.heightAnchor).isActive = true
        return frame
    }

    private func fill(_ frame: UIView, with subview: UIView) {
        subview.translatesAutoresizingMaskIntoConstraints = false
        frame.addSubview(subview)
        NSLayoutConstraint.activate([
            subview.topAnchor.constraint(equalTo: frame.topAnchor),
            subview.bottomAnchor.constraint(equalTo: frame.bottomAnchor),
            subview.leadingAnchor.constraint(equalTo: frame.leadingAnchor),
            subview.trailingAnchor.constraint(equalTo: frame.trailingAnchor),
        ])
    }
}
