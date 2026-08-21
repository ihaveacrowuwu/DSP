import UIKit

/// One titled group of related information.
///
/// The unit the sighting and config screens are built from. A screen made of these reads as a
/// small number of labelled things; the same content laid out as one long column of headings
/// and rows reads as a list of facts the reader has to group themselves.
///
/// A plain grouped-background card rather than glass: this is **content**, and glass belongs
/// on chrome. A card of readings behind a glass panel is the same mistake as a photograph
/// behind one.
final class SectionCardView: UIView {
    /// The stack callers add their rows to.
    let content = UIStackView()

    private let header = UIStackView()
    private let titleLabel = UILabel()

    /// - Parameters:
    ///   - title: Optional. The first card on a screen is often self-evident — a photograph
    ///     does not need to be labelled "Photograph".
    ///   - trailing: Shown at the end of the title row: a count, a toggle, a timestamp.
    init(title: String? = nil, trailing: UIView? = nil) {
        super.init(frame: .zero)

        backgroundColor = .secondarySystemGroupedBackground
        layer.cornerRadius = 16
        layer.cornerCurve = .continuous

        content.axis = .vertical
        content.spacing = Spacing.md

        let outer = UIStackView()
        outer.axis = .vertical
        outer.spacing = Spacing.md
        outer.translatesAutoresizingMaskIntoConstraints = false
        outer.isLayoutMarginsRelativeArrangement = true
        outer.layoutMargins = UIEdgeInsets(
            top: Spacing.lg, left: Spacing.lg, bottom: Spacing.lg, right: Spacing.lg
        )

        if title != nil || trailing != nil {
            header.axis = .horizontal
            header.spacing = Spacing.sm
            header.alignment = .center

            titleLabel.text = title
            titleLabel.font = .preferredFont(forTextStyle: .headline)
            titleLabel.adjustsFontForContentSizeCategory = true
            titleLabel.numberOfLines = 0
            header.addArrangedSubview(titleLabel)

            // A flexible spacer, so `trailing` sits at the end rather than beside the title.
            header.addArrangedSubview(UIView())
            if let trailing { header.addArrangedSubview(trailing) }

            outer.addArrangedSubview(header)
        }

        outer.addArrangedSubview(content)
        addSubview(outer)

        NSLayoutConstraint.activate([
            outer.topAnchor.constraint(equalTo: topAnchor),
            outer.bottomAnchor.constraint(equalTo: bottomAnchor),
            outer.leadingAnchor.constraint(equalTo: leadingAnchor),
            outer.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    /// Adds a row to the card.
    func addRow(_ view: UIView) {
        content.addArrangedSubview(view)
    }

    /// A row of readouts that share a baseline.
    ///
    /// Measurements belong in a row, evenly spaced, so the eye can compare them — which is
    /// the whole reason they are monospaced. Stacked one per line they stop being a set of
    /// related numbers and become a list.
    static func readoutRow(_ readouts: [UIView]) -> UIStackView {
        let row = UIStackView(arrangedSubviews: readouts + [UIView()])
        row.axis = .horizontal
        row.spacing = Spacing.xl
        row.alignment = .top
        return row
    }

    /// A caption under a card's content: a clarification, a provenance note, a warning.
    static func caption(_ text: String, colour: UIColor = .secondaryLabel) -> UILabel {
        let label = UILabel()
        label.text = text
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = colour
        label.numberOfLines = 0
        return label
    }
}
