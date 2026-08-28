import UIKit

/// Says whether a label came from the model or from an expert.
///
/// NFR13, and the single most important rule in the whole interface: **a model label must
/// never be mistaken for an expert verdict.** The distinction is carried three ways over, so
/// that losing any one of them still leaves it legible:
///
/// 1. **Shape** - a dashed outline for the model, a solid filled surface for an expert.
/// 2. **A marker** - hollow for the model, filled for an expert.
/// 3. **A word** - literally "model" or "expert".
///
/// That redundancy is the requirement. It survives greyscale, it survives colour blindness,
/// and it survives a screenshot pasted into the project at 60% scale.
final class ProvenanceChipView: UIView {
    private let border = CAShapeLayer()
    private let markerView = UIView()
    private let label = UILabel()

    private var isVerified = false

    init(verified: Bool) {
        super.init(frame: .zero)
        isVerified = verified

        let accent = verified ? ReefPalette.verified : UIColor.secondaryLabel

        // Filled for an expert, transparent for the model: the weight of the thing on the
        // screen matches the weight of the claim.
        backgroundColor = verified ? accent.withAlphaComponent(0.16) : .clear
        layer.cornerRadius = 8
        layer.cornerCurve = .continuous

        border.fillColor = UIColor.clear.cgColor
        border.strokeColor = accent.cgColor
        border.lineWidth = 1
        // The dash IS the distinction. UIKit has no dashed-border property, which is the
        // only reason there is a shape layer in a component whose argument is to prefer the
        // platform's own.
        border.lineDashPattern = verified ? nil : [3, 3]
        layer.addSublayer(border)

        markerView.translatesAutoresizingMaskIntoConstraints = false
        markerView.layer.cornerRadius = 4
        markerView.backgroundColor = verified ? accent : .clear
        markerView.layer.borderWidth = verified ? 0 : 1.5
        markerView.layer.borderColor = accent.cgColor
        addSubview(markerView)

        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = verified ? "expert" : "model"
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = accent
        addSubview(label)

        NSLayoutConstraint.activate([
            markerView.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            markerView.centerYAnchor.constraint(equalTo: centerYAnchor),
            markerView.widthAnchor.constraint(equalToConstant: 8),
            markerView.heightAnchor.constraint(equalToConstant: 8),

            label.leadingAnchor.constraint(equalTo: markerView.trailingAnchor, constant: 6),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            label.topAnchor.constraint(equalTo: topAnchor, constant: 4),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -4),
        ])

        isAccessibilityElement = true
        accessibilityLabel = verified
            ? "Verified by an expert"
            : "Automatic assessment by the model, not yet reviewed"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        border.frame = bounds
        border.path = UIBezierPath(roundedRect: bounds.insetBy(dx: 0.5, dy: 0.5), cornerRadius: 8).cgPath
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        // CALayer colours do not resolve dynamically, so they have to be reapplied when the
        // appearance changes - the classic reason a dark-mode border stays light.
        let accent = isVerified ? ReefPalette.verified : UIColor.secondaryLabel
        border.strokeColor = accent.resolvedColor(with: traitCollection).cgColor
        markerView.layer.borderColor = accent.resolvedColor(with: traitCollection).cgColor
    }
}
