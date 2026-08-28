import UIKit

/// A measured quantity, monospaced with tabular figures.
///
/// Every number in this app goes through here: coordinates, depths, severities, counts, model
/// versions. It is the cheapest piece of family resemblance the three clients have and the
/// strongest - columns line up, and the interface reads like an instrument.
///
/// The font is `monospacedSystemFont` at the size the text style asks for, so it still scales
/// with Dynamic Type. A fixed point size here would be a bug, not a design.
final class ReadoutView: UIStackView {
    private let captionLabel = UILabel()
    private let valueLabel = UILabel()

    init(caption: String? = nil, value: String, style: UIFont.TextStyle = .body) {
        super.init(frame: .zero)
        axis = .vertical
        spacing = 2

        if let caption {
            captionLabel.text = caption
            captionLabel.font = .preferredFont(forTextStyle: .caption2)
            captionLabel.adjustsFontForContentSizeCategory = true
            captionLabel.textColor = .secondaryLabel
            addArrangedSubview(captionLabel)
        }

        valueLabel.text = value
        valueLabel.font = Self.monospaced(style)
        valueLabel.adjustsFontForContentSizeCategory = true
        valueLabel.textColor = .label
        addArrangedSubview(valueLabel)
    }

    @available(*, unavailable)
    required init(coder _: NSCoder) {
        // UIStackView's init(coder:) is non-failable, so this cannot return nil the way a
        // UIView subclass's can. Nothing in this app comes from a XIB, so reaching it is a
        // programmer error rather than a runtime condition.
        fatalError("Muraka builds its views in code; init(coder:) is unused")
    }

    var value: String? {
        get { valueLabel.text }
        set { valueLabel.text = newValue }
    }

    /// A monospaced font that still tracks Dynamic Type.
    static func monospaced(_ style: UIFont.TextStyle, weight: UIFont.Weight = .medium) -> UIFont {
        let descriptor = UIFontDescriptor.preferredFontDescriptor(withTextStyle: style)
        let base = UIFont.monospacedSystemFont(ofSize: descriptor.pointSize, weight: weight)
        return UIFontMetrics(forTextStyle: style).scaledFont(for: base)
    }
}

/// The contributor-facing status of a sighting.
///
/// The vocabulary is ``SightingDisplayStatus`` and nothing else - there is no "Synced",
/// because a local flag claiming the upload worked is a claim rather than a fact. The colour
/// is decoration; the **word** is the information, which is what makes this legible in a
/// greyscale screenshot.
final class StatusPillView: UIView {
    private let label = UILabel()

    init(status: SightingDisplayStatus) {
        super.init(frame: .zero)

        let colour: UIColor = switch status {
        case .verifiedByExpert: ReefPalette.verified
        case .notUsable, .failed: ReefPalette.rust
        case .waitingToUpload, .uploading, .checking, .photosPending: ReefPalette.amber
        case .analysing, .awaitingReview: .secondaryLabel
        }

        backgroundColor = colour.withAlphaComponent(0.14)
        layer.cornerRadius = 6
        layer.cornerCurve = .continuous

        label.translatesAutoresizingMaskIntoConstraints = false
        label.text = status.label
        label.font = .preferredFont(forTextStyle: .caption1)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = colour
        label.numberOfLines = 1
        addSubview(label)

        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 8),
            label.trailingAnchor.constraint(equalTo: trailingAnchor, constant: -8),
            label.topAnchor.constraint(equalTo: topAnchor, constant: 3),
            label.bottomAnchor.constraint(equalTo: bottomAnchor, constant: -3),
        ])
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }
}

/// A block of the severity ramp.
///
/// A graphic, never text: the condition colours are fills, and body text set in them would
/// fail contrast against half the surfaces it could land on. The number beside it carries the
/// meaning.
final class SeveritySwatchView: UIView {
    init(severity: Double) {
        super.init(frame: .zero)
        backgroundColor = ReefPalette.severity(severity)
        layer.cornerRadius = 4
        layer.cornerCurve = .continuous
        isAccessibilityElement = true
        accessibilityLabel = "Severity \(Int((severity * 100).rounded())) percent"
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: CGSize { CGSize(width: 18, height: 18) }
}

/// "just now", "12 minutes ago", "3 days ago".
///
/// Used for two different things, and the difference matters: the age of a *sighting*, and
/// the age of what the app *knows* about it. The second is what lets the interface say
/// "checked 20 minutes ago" instead of presenting a stale status as current.
enum RelativeTime {
    static func describe(_ date: Date, now: Date = Date()) -> String {
        let elapsed = now.timeIntervalSince(date)
        // A clock reading slightly ahead should not produce "in 3 seconds".
        guard elapsed > 60 else { return "just now" }

        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .full
        return formatter.localizedString(for: date, relativeTo: now)
    }
}
