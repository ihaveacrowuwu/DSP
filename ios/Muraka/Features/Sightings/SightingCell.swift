import UIKit

/// One sighting in the history list.
final class SightingCell: UITableViewCell {
    static let reuseIdentifier = "SightingCell"

    private let swatchContainer = UIView()
    private let statusRow = UIStackView()
    private let coordinateLabel = UILabel()
    private let ageLabel = UILabel()
    private let failureLabel = UILabel()
    private let column = UIStackView()

    override init(style: UITableViewCell.CellStyle, reuseIdentifier: String?) {
        super.init(style: style, reuseIdentifier: reuseIdentifier)
        accessoryType = .disclosureIndicator

        swatchContainer.translatesAutoresizingMaskIntoConstraints = false

        statusRow.axis = .horizontal
        statusRow.spacing = 8
        statusRow.alignment = .center

        coordinateLabel.font = ReadoutView.monospaced(.footnote)
        coordinateLabel.adjustsFontForContentSizeCategory = true

        ageLabel.font = .preferredFont(forTextStyle: .caption1)
        ageLabel.adjustsFontForContentSizeCategory = true
        ageLabel.textColor = .secondaryLabel
        ageLabel.numberOfLines = 0

        failureLabel.font = .preferredFont(forTextStyle: .caption1)
        failureLabel.adjustsFontForContentSizeCategory = true
        failureLabel.textColor = ReefPalette.rust
        failureLabel.numberOfLines = 0

        column.axis = .vertical
        column.spacing = 4
        column.translatesAutoresizingMaskIntoConstraints = false
        [statusRow, coordinateLabel, ageLabel, failureLabel].forEach(column.addArrangedSubview)

        contentView.addSubview(swatchContainer)
        contentView.addSubview(column)

        NSLayoutConstraint.activate([
            swatchContainer.leadingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.leadingAnchor),
            swatchContainer.centerYAnchor.constraint(equalTo: contentView.centerYAnchor),
            swatchContainer.widthAnchor.constraint(equalToConstant: 22),
            swatchContainer.heightAnchor.constraint(equalToConstant: 22),

            column.leadingAnchor.constraint(equalTo: swatchContainer.trailingAnchor, constant: 12),
            column.trailingAnchor.constraint(equalTo: contentView.layoutMarginsGuide.trailingAnchor),
            column.topAnchor.constraint(equalTo: contentView.layoutMarginsGuide.topAnchor),
            column.bottomAnchor.constraint(equalTo: contentView.layoutMarginsGuide.bottomAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    func configure(with sighting: ContributorSighting) {
        swatchContainer.subviews.forEach { $0.removeFromSuperview() }
        statusRow.arrangedSubviews.forEach { $0.removeFromSuperview() }

        let swatch: UIView = if let severity = sighting.server?.severity {
            SeveritySwatchView(severity: severity)
        } else {
            UnassessedGlyphView()
        }
        swatch.translatesAutoresizingMaskIntoConstraints = false
        swatchContainer.addSubview(swatch)
        NSLayoutConstraint.activate([
            swatch.topAnchor.constraint(equalTo: swatchContainer.topAnchor),
            swatch.bottomAnchor.constraint(equalTo: swatchContainer.bottomAnchor),
            swatch.leadingAnchor.constraint(equalTo: swatchContainer.leadingAnchor),
            swatch.trailingAnchor.constraint(equalTo: swatchContainer.trailingAnchor),
        ])

        statusRow.addArrangedSubview(StatusPillView(status: sighting.displayStatus))
        if sighting.photosPending > 0 {
            let pending = UILabel()
            let plural = sighting.photosPending == 1 ? "" : "s"
            pending.text = "\(sighting.photosPending) photograph\(plural) left"
            pending.font = .preferredFont(forTextStyle: .caption2)
            pending.adjustsFontForContentSizeCategory = true
            pending.textColor = ReefPalette.amber
            statusRow.addArrangedSubview(pending)
        }
        statusRow.addArrangedSubview(UIView())

        coordinateLabel.text = String(
            format: "%.4f, %.4f",
            sighting.position.lat,
            sighting.position.lon
        )

        var age = "Captured \(RelativeTime.describe(sighting.capturedAt))"
        // The age of the KNOWLEDGE, not of the sighting. A stale truth labelled stale is
        // fine; a stale truth presented as current is the bug this design exists to prevent.
        if let readAt = sighting.serverReadAt {
            age += " · checked \(RelativeTime.describe(readAt))"
        }
        ageLabel.text = age

        failureLabel.text = sighting.failureReason
        failureLabel.isHidden = sighting.failureReason == nil
    }
}
