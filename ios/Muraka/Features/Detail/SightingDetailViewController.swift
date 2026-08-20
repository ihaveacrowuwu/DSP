import UIKit

/// One sighting: the photographs, the model's reading of each, and any expert verdict.
///
/// Refreshes on open, which is the read-back that turns "Checking…" into whatever the server
/// actually says.
final class SightingDetailViewController: UIViewController {
    private let container: AppContainer
    private let sightingID: String

    private let scrollView = UIScrollView()
    private let stack = UIStackView()

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
        view.backgroundColor = .systemBackground

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
        scrollView.addSubview(stack)

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

        // Status, and how old the app's knowledge of it is.
        let statusRow = UIStackView()
        statusRow.axis = .horizontal
        statusRow.spacing = 8
        statusRow.alignment = .center
        statusRow.addArrangedSubview(StatusPillView(status: detail.summary.displayStatus))
        if let readAt = detail.summary.serverReadAt {
            let checked = UILabel()
            checked.text = "checked \(RelativeTime.describe(readAt))"
            checked.font = .preferredFont(forTextStyle: .caption1)
            checked.adjustsFontForContentSizeCategory = true
            checked.textColor = .secondaryLabel
            statusRow.addArrangedSubview(checked)
        }
        statusRow.addArrangedSubview(UIView())
        stack.addArrangedSubview(statusRow)

        // Photographs still on the device, so a queued sighting is not a blank screen while
        // it waits for a connection.
        for path in detail.pendingPhotoPaths {
            let frame = photoFrame()
            if let image = UIImage(contentsOfFile: path.path) {
                let imageView = UIImageView(image: image)
                imageView.contentMode = .scaleAspectFit
                imageView.accessibilityLabel = "A photograph waiting to upload"
                fill(frame, with: imageView)
            }
            stack.addArrangedSubview(frame)
        }

        for photo in detail.photos {
            stack.addArrangedSubview(makePhotoSection(
                photo: photo,
                verified: detail.summary.server?.verified == true
            ))
        }

        stack.addArrangedSubview(makeWhereAndWhen(detail.summary))

        if !detail.verifications.isEmpty {
            let heading = UILabel()
            heading.text = "Expert review"
            heading.font = .preferredFont(forTextStyle: .headline)
            heading.adjustsFontForContentSizeCategory = true
            stack.addArrangedSubview(heading)
            detail.verifications.forEach { stack.addArrangedSubview(makeVerification($0)) }
        }
    }

    /// A photograph with the model's reading of it.
    ///
    /// The lattice sits **over** the photograph and the numbers **below** it, for the reason
    /// the dashboard settled on (D24): the reef has to stay visible while the judgement is
    /// being checked against it.
    private func makePhotoSection(photo: Photo, verified: Bool) -> UIView {
        let column = UIStackView()
        column.axis = .vertical
        column.spacing = 12

        let frame = photoFrame()
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
            fill(frame, with: lattice)
        }
        column.addArrangedSubview(frame)

        guard let prediction = photo.prediction else {
            // Absent is not an error. Say what is happening rather than showing nothing.
            let pending = UILabel()
            pending.text = "Not yet assessed by the model."
            pending.font = .preferredFont(forTextStyle: .body)
            pending.adjustsFontForContentSizeCategory = true
            pending.textColor = .secondaryLabel
            column.addArrangedSubview(pending)
            return column
        }

        // Severity leads, not the label: "62% bleached" tells a contributor something
        // "bleached" does not, and it is the number researchers actually work with.
        let headline = UIStackView()
        headline.axis = .horizontal
        headline.spacing = 8
        headline.alignment = .center
        headline.addArrangedSubview(SeveritySwatchView(severity: prediction.severity))

        let extent = UILabel()
        extent.text = "\(Int((prediction.severity * 100).rounded()))% bleached"
        extent.font = ReadoutView.monospaced(.title3, weight: .semibold)
        extent.adjustsFontForContentSizeCategory = true
        headline.addArrangedSubview(extent)
        headline.addArrangedSubview(ProvenanceChipView(verified: verified))
        headline.addArrangedSubview(UIView())
        column.addArrangedSubview(headline)

        let numbers = UIStackView()
        numbers.axis = .horizontal
        numbers.spacing = 20
        numbers.alignment = .top
        numbers.addArrangedSubview(ReadoutView(
            caption: "Confidence",
            value: "\(Int((prediction.confidence * 100).rounded()))%",
            style: .footnote
        ))
        numbers.addArrangedSubview(ReadoutView(
            caption: "Grid",
            value: "\(prediction.patchGrid)×\(prediction.patchGrid)",
            style: .footnote
        ))
        if let inference = prediction.inferenceMs {
            numbers.addArrangedSubview(ReadoutView(
                caption: "Inference",
                value: "\(inference) ms",
                style: .footnote
            ))
        }
        numbers.addArrangedSubview(UIView())
        column.addArrangedSubview(numbers)

        // Provenance: `fake-0.0.0` means no trained model is loaded yet, and a reader of the
        // report needs to be able to tell which screenshots predate the real one.
        column.addArrangedSubview(ReadoutView(
            caption: "Model",
            value: prediction.modelVersion,
            style: .footnote
        ))

        return column
    }

    private func makeWhereAndWhen(_ sighting: ContributorSighting) -> UIView {
        let column = UIStackView()
        column.axis = .vertical
        column.spacing = 8

        let heading = UILabel()
        heading.text = "Where and when"
        heading.font = .preferredFont(forTextStyle: .headline)
        heading.adjustsFontForContentSizeCategory = true
        column.addArrangedSubview(heading)

        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 20
        row.addArrangedSubview(ReadoutView(
            caption: sighting.locationSource == .gps ? "GPS" : "Dropped pin",
            value: String(format: "%.5f, %.5f", sighting.position.lat, sighting.position.lon)
        ))
        if let depth = sighting.server?.depthM {
            row.addArrangedSubview(ReadoutView(caption: "Depth", value: "\(Int(depth)) m"))
        }
        row.addArrangedSubview(UIView())
        column.addArrangedSubview(row)

        let captured = UILabel()
        captured.text = "Captured \(RelativeTime.describe(sighting.capturedAt))"
        captured.font = .preferredFont(forTextStyle: .footnote)
        captured.adjustsFontForContentSizeCategory = true
        captured.textColor = .secondaryLabel
        column.addArrangedSubview(captured)

        if let note = sighting.server?.note {
            let noteLabel = UILabel()
            noteLabel.text = note
            noteLabel.font = .preferredFont(forTextStyle: .body)
            noteLabel.adjustsFontForContentSizeCategory = true
            noteLabel.numberOfLines = 0
            column.addArrangedSubview(noteLabel)
        }

        return column
    }

    private func makeVerification(_ verification: Verification) -> UIView {
        let column = UIStackView()
        column.axis = .vertical
        column.spacing = 8

        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = 8
        row.alignment = .center
        row.addArrangedSubview(ProvenanceChipView(verified: true))

        let decision = UILabel()
        decision.text = switch verification.decision {
        case .confirmed: "Confirmed the model"
        case .corrected: "Corrected the model"
        case .rejected: "Rejected this photograph"
        }
        decision.font = .preferredFont(forTextStyle: .body)
        decision.adjustsFontForContentSizeCategory = true
        row.addArrangedSubview(decision)
        row.addArrangedSubview(UIView())
        column.addArrangedSubview(row)

        // Rejected sightings vanish from research views but remain the contributor's own
        // record (FR11), so the reason is shown rather than hidden.
        if let reason = verification.rejectReason {
            let label = UILabel()
            label.text = "Reason: \(reason.readable)"
            label.font = .preferredFont(forTextStyle: .footnote)
            label.adjustsFontForContentSizeCategory = true
            label.textColor = ReefPalette.rust
            column.addArrangedSubview(label)
        }

        if let comment = verification.comment {
            let label = UILabel()
            label.text = comment
            label.font = .preferredFont(forTextStyle: .body)
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
            column.addArrangedSubview(label)
        }

        return column
    }

    /// One fixed square frame for every photograph.
    ///
    /// The dashboard learnt this the hard way (D24): sizing each frame to its source made the
    /// 224 px dataset crops too small to judge, and made two photographs of the same reef look
    /// like different sizes of thing. A square also matches the centre square the server
    /// tiles, so the lattice lands where the model actually looked.
    private func photoFrame() -> UIView {
        let frame = UIView()
        frame.backgroundColor = .secondarySystemBackground
        frame.layer.cornerRadius = 12
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
