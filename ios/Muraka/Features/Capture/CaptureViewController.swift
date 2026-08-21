import PhotosUI
import UIKit

/// Capturing a sighting.
///
/// Everything is arranged around NFR6 — under 60 seconds and at most 8 taps:
///
/// ```
/// 1  New sighting     2  Add a photograph     3  the shutter
/// 4  Use Photo        5  Queue this sighting
/// ```
///
/// Five taps for the required path. Position is requested when the screen opens rather than
/// being a step, and depth, note and self-assessment are optional and out of the way. The
/// remaining headroom is what a contributor spends on those if they want to.
///
/// Nothing here waits for the network. Submitting returns as soon as the row and its photo
/// files are durably on disk, which is what makes capture work in aeroplane mode (NFR7, FR3).
final class CaptureViewController: UIViewController {
    private let container: AppContainer

    private let scrollView = UIScrollView()
    private let stack = UIStackView()
    private let photoStrip = UIStackView()
    private let addPhotoButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
    private let positionLabel = UILabel()
    private let positionDetail = UIStackView()
    private let depthField = UITextField()
    private let noteField = UITextField()
    private let assessmentControl = UISegmentedControl(items: ["Not sure", "Healthy", "Bleached"])
    private let submitButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.primary))
    private let messageLabel = UILabel()

    /// The sighting's own id, minted once so retries and photo rows share it.
    private let sightingID = UUIDv7.generate()
    /// Device capture time, taken when the screen opens rather than when submit is tapped.
    private let capturedAt = Date()

    private var photos: [PhotoDraft] = []
    private var fix: LocationFix?

    init(container: AppContainer) {
        self.container = container
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "New sighting"
        view.backgroundColor = .systemBackground
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: "Discard",
            style: .plain,
            target: self,
            action: #selector(discard)
        )
        buildHierarchy()

        // Asked in context, at the moment of capture, never on launch — non-negotiable 6.
        container.locationProvider.requestPermission()
        Task { await findPosition() }
    }

    private func buildHierarchy() {
        scrollView.translatesAutoresizingMaskIntoConstraints = false
        scrollView.keyboardDismissMode = .interactive
        view.addSubview(scrollView)

        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.axis = .vertical
        stack.spacing = 20
        scrollView.addSubview(stack)

        photoStrip.axis = .horizontal
        photoStrip.spacing = 8
        photoStrip.alignment = .center

        addPhotoButton.setTitle("Add a photograph", for: .normal)
        addPhotoButton.setImage(UIImage(systemName: "camera"), for: .normal)
        addPhotoButton.addTarget(self, action: #selector(addPhoto), for: .touchUpInside)

        positionLabel.font = .preferredFont(forTextStyle: .headline)
        positionLabel.adjustsFontForContentSizeCategory = true
        positionLabel.text = "Position"

        positionDetail.axis = .horizontal
        positionDetail.spacing = 20

        configure(depthField, placeholder: "Depth in metres (optional)", keyboard: .decimalPad)
        configure(noteField, placeholder: "Note (optional)")
        let depthContainer = GlassSurface.wrapTextField(depthField)
        let noteContainer = GlassSurface.wrapTextField(noteField)

        assessmentControl.selectedSegmentIndex = 0

        messageLabel.font = .preferredFont(forTextStyle: .footnote)
        messageLabel.adjustsFontForContentSizeCategory = true
        messageLabel.textColor = ReefPalette.amber
        messageLabel.numberOfLines = 0
        messageLabel.isHidden = true

        submitButton.setTitle("Queue this sighting", for: .normal)
        submitButton.addTarget(self, action: #selector(submit), for: .touchUpInside)
        submitButton.isEnabled = false

        let photoHeading = heading("Photographs")
        let assessmentHeading = heading("Your impression (optional)")
        let assessmentNote = UILabel()
        // Recorded for comparison with the model and never mixed into the authoritative
        // condition, which is why it is optional and visually quiet.
        assessmentNote.text = "Recorded for comparison. It never replaces the model's reading "
            + "or an expert's."
        assessmentNote.font = .preferredFont(forTextStyle: .caption1)
        assessmentNote.adjustsFontForContentSizeCategory = true
        assessmentNote.textColor = .secondaryLabel
        assessmentNote.numberOfLines = 0

        let useGPS = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        useGPS.setTitle("Use GPS", for: .normal)
        useGPS.addTarget(self, action: #selector(retryPosition), for: .touchUpInside)

        let dropPinButton = UIButton(configuration: GlassSurface.makeButtonConfiguration(.secondary))
        dropPinButton.setTitle("Drop a pin", for: .normal)
        dropPinButton.addTarget(self, action: #selector(dropPinTapped), for: .touchUpInside)

        let positionButtons = UIStackView(arrangedSubviews: [useGPS, dropPinButton, UIView()])
        positionButtons.axis = .horizontal
        positionButtons.spacing = 8

        [photoHeading, photoStrip, addPhotoButton,
         positionLabel, positionDetail, positionButtons,
         depthContainer, noteContainer,
         assessmentHeading, assessmentControl, assessmentNote,
         messageLabel, submitButton].forEach(stack.addArrangedSubview)

        let guide = view.safeAreaLayoutGuide
        NSLayoutConstraint.activate([
            scrollView.topAnchor.constraint(equalTo: guide.topAnchor),
            scrollView.leadingAnchor.constraint(equalTo: guide.leadingAnchor),
            scrollView.trailingAnchor.constraint(equalTo: guide.trailingAnchor),
            scrollView.bottomAnchor.constraint(equalTo: guide.bottomAnchor),

            stack.topAnchor.constraint(equalTo: scrollView.contentLayoutGuide.topAnchor, constant: 16),
            stack.bottomAnchor.constraint(equalTo: scrollView.contentLayoutGuide.bottomAnchor, constant: -32),
            stack.leadingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.leadingAnchor, constant: 16),
            stack.trailingAnchor.constraint(equalTo: scrollView.frameLayoutGuide.trailingAnchor, constant: -16),
        ])

        updatePositionDisplay()
    }

    private func heading(_ text: String) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .headline)
        label.adjustsFontForContentSizeCategory = true
        return label
    }

    private func configure(_ field: UITextField, placeholder: String, keyboard: UIKeyboardType = .default) {
        field.placeholder = placeholder
        field.borderStyle = .roundedRect
        field.keyboardType = keyboard
        field.font = .preferredFont(forTextStyle: .body)
        field.adjustsFontForContentSizeCategory = true
        field.accessibilityLabel = placeholder
    }

    // ── Position ────────────────────────────────────────────────────────────

    /// Position is required; GPS is not.
    ///
    /// A diver under cloud, or on a hull that blocks the sky, may have no fix at all. Dropping
    /// a pin records `manual_pin`, which researchers filter on — so the two are genuinely
    /// different things rather than one silently standing in for the other.
    private func findPosition() async {
        positionDetailPlaceholder("Finding your position…")
        let found = await container.locationProvider.currentFix()

        // Never overwrite a pin the contributor placed deliberately with a fix that arrived
        // late.
        if fix?.source != .manualPin { fix = found }

        if fix == nil {
            show(message: "No position fix yet. You can drop a pin instead.")
        }
        updatePositionDisplay()
    }

    @objc private func retryPosition() {
        Task { await findPosition() }
    }

    @objc private func dropPinTapped() {
        let alert = UIAlertController(
            title: "Drop a pin",
            message: "Recorded as a manual pin so researchers can tell it apart from a GPS fix.",
            preferredStyle: .alert
        )
        alert.addTextField { $0.placeholder = "Latitude"; $0.keyboardType = .numbersAndPunctuation }
        alert.addTextField { $0.placeholder = "Longitude"; $0.keyboardType = .numbersAndPunctuation }
        alert.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        alert.addAction(UIAlertAction(title: "Use this position", style: .default) { [weak self] _ in
            guard let self,
                  let lat = Double(alert.textFields?.first?.text ?? ""),
                  let lon = Double(alert.textFields?.last?.text ?? "")
            else { return }

            let position = Position(lat: lat, lon: lon)
            guard position.isValid else {
                self.show(message: "That is not a valid coordinate.")
                return
            }
            self.fix = LocationFix(position: position, source: .manualPin)
            self.messageLabel.isHidden = true
            self.updatePositionDisplay()
        })
        present(alert, animated: true)
    }

    private func positionDetailPlaceholder(_ text: String) {
        positionDetail.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .body)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = .secondaryLabel
        positionDetail.addArrangedSubview(label)
    }

    private func updatePositionDisplay() {
        positionDetail.arrangedSubviews.forEach { $0.removeFromSuperview() }

        guard let fix else {
            positionDetailPlaceholder("No position yet.")
            updateSubmitState()
            return
        }

        positionDetail.addArrangedSubview(ReadoutView(
            caption: fix.source == .gps ? "GPS" : "Dropped pin",
            value: String(format: "%.5f, %.5f", fix.position.lat, fix.position.lon)
        ))
        if let accuracy = fix.accuracyM {
            positionDetail.addArrangedSubview(ReadoutView(
                caption: "Accuracy",
                value: "±\(Int(accuracy)) m"
            ))
        }
        positionDetail.addArrangedSubview(UIView())
        updateSubmitState()
    }

    // ── Photographs ─────────────────────────────────────────────────────────

    @objc private func addPhoto() {
        let sheet = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        sheet.popoverPresentationController?.sourceView = addPhotoButton

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            sheet.addAction(UIAlertAction(title: "Take a photograph", style: .default) { [weak self] _ in
                self?.presentCamera()
            })
        }
        // The path for action-camera footage already on the phone.
        sheet.addAction(UIAlertAction(title: "Import from the library", style: .default) { [weak self] _ in
            self?.presentLibrary()
        })
        sheet.addAction(UIAlertAction(title: "Cancel", style: .cancel))
        present(sheet, animated: true)
    }

    private func presentCamera() {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = self
        present(picker, animated: true)
    }

    private func presentLibrary() {
        var configuration = PHPickerConfiguration()
        configuration.filter = .images
        configuration.selectionLimit = CaptureLimits.maxPhotos - photos.count
        let picker = PHPickerViewController(configuration: configuration)
        picker.delegate = self
        present(picker, animated: true)
    }

    /// Copies into app-private storage NOW: a library asset can be deleted long before the
    /// outbox drains.
    private func store(image: UIImage) async {
        guard photos.count < CaptureLimits.maxPhotos else { return }

        let photoID = UUIDv7.generate()
        guard let url = await container.photos.store(photoID: photoID, image: image) else {
            show(message: "That photograph could not be saved to this device.")
            return
        }
        photos.append(PhotoDraft(id: photoID, fileURL: url))
        refreshPhotoStrip()
    }

    private func refreshPhotoStrip() {
        photoStrip.arrangedSubviews.forEach { $0.removeFromSuperview() }

        for (index, photo) in photos.enumerated() {
            let imageView = UIImageView(image: UIImage(contentsOfFile: photo.fileURL.path))
            imageView.translatesAutoresizingMaskIntoConstraints = false
            imageView.contentMode = .scaleAspectFill
            imageView.clipsToBounds = true
            imageView.layer.cornerRadius = 12
            imageView.layer.cornerCurve = .continuous
            imageView.isAccessibilityElement = true
            imageView.accessibilityLabel = "Photograph \(index + 1)"
            imageView.isUserInteractionEnabled = true
            imageView.tag = index
            imageView.addGestureRecognizer(
                UITapGestureRecognizer(target: self, action: #selector(removePhoto(_:)))
            )
            NSLayoutConstraint.activate([
                imageView.widthAnchor.constraint(equalToConstant: 88),
                imageView.heightAnchor.constraint(equalToConstant: 88),
            ])
            photoStrip.addArrangedSubview(imageView)
        }
        photoStrip.addArrangedSubview(UIView())

        addPhotoButton.isEnabled = photos.count < CaptureLimits.maxPhotos
        addPhotoButton.setTitle(
            "Add a photograph  \(photos.count) of \(CaptureLimits.maxPhotos)",
            for: .normal
        )
        updateSubmitState()
    }

    @objc private func removePhoto(_ recognizer: UITapGestureRecognizer) {
        guard let index = recognizer.view?.tag, photos.indices.contains(index) else { return }
        let photo = photos.remove(at: index)
        Task { await container.photos.delete(photoID: photo.id) }
        refreshPhotoStrip()
    }

    // ── Submission ──────────────────────────────────────────────────────────

    private func updateSubmitState() {
        submitButton.isEnabled = fix != nil && photos.count >= CaptureLimits.minPhotos
    }

    @objc private func submit() {
        guard let fix else { return }

        let assessment: Condition? = switch assessmentControl.selectedSegmentIndex {
        case 1: .healthy
        case 2: .bleached
        default: nil
        }

        let draft = SightingDraft(
            id: sightingID,
            fix: fix,
            capturedAt: capturedAt,
            depthM: Double(depthField.text ?? ""),
            note: noteField.text?.isEmpty == false ? noteField.text : nil,
            selfAssessedCondition: assessment,
            photos: photos
        )

        submitButton.isEnabled = false
        Task {
            do {
                _ = try await container.sightingRepository.capture(draft)
                // The sighting is on disk and the drain loop owns it now. Leaving
                // immediately is correct: waiting for an upload would be waiting for a
                // network the contributor may not have for hours.
                container.backgroundSync.syncNowDetached()
                dismiss(animated: true)
            } catch {
                submitButton.isEnabled = true
                show(message: ApiError.from(error).message)
            }
        }
    }

    @objc private func discard() {
        // Nothing has been queued yet, so the photographs on disk are ours to remove.
        let queued = photos
        Task {
            for photo in queued { await container.photos.delete(photoID: photo.id) }
        }
        dismiss(animated: true)
    }

    private func show(message: String) {
        messageLabel.text = message
        messageLabel.isHidden = false
    }
}

extension CaptureViewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true)

        for result in results {
            result.itemProvider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
                guard let image = object as? UIImage else { return }
                Task { @MainActor in await self?.store(image: image) }
            }
        }
    }
}

extension CaptureViewController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(
        _ picker: UIImagePickerController,
        didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]
    ) {
        picker.dismiss(animated: true)
        guard let image = info[.originalImage] as? UIImage else { return }
        Task { await store(image: image) }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true)
    }
}
