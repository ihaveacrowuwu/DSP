import UIKit

/// **The only place in the app that calls a Liquid Glass API.**
///
/// Two rules matter more than the API details, and both are the reason this is one file
/// rather than a call scattered through six view controllers:
///
/// 1. **Glass belongs on chrome, never on content.** Bars, toolbars, floating controls and
///    sheets, yes. Photographs, the patch lattice and the sightings list are content — a reef
///    photograph behind a glass panel is a reef photograph nobody can assess.
/// 2. **It must degrade.** Liquid Glass is a visual layer, not a structural dependency. When
///    the effect is unavailable the surface falls back to a standard `UIBlurEffect`, and no
///    layout depends on which one it got.
///
/// The degradation path is not hypothetical even on iOS 26: **Reduce Transparency** turns the
/// effect off at the system level, and a reviewer can switch it on in Settings to see the
/// fallback. That is what makes it a real code path rather than a claim.
///
/// Every API name below was verified against the iPhoneSimulator26.5 SDK before use:
/// `UIGlassEffect(style:)`, `UIGlassContainerEffect`, `UICornerConfiguration.corners(radius:)`
/// and `UICornerRadius.containerConcentric(minimum:)` — the last of which the Swift importer
/// renames from the header's `containerConcentricRadiusWithMinimum:`, so it has to be checked
/// rather than guessed.
enum GlassSurface {
    /// What a glass surface is being used for. Adding a case here is how a new glass
    /// surface is introduced — never by calling `UIGlassEffect` somewhere else.
    enum Role {
        /// A floating panel over content: the capture summary, a sheet header.
        case panel
        /// A small floating control: a badge, a pill.
        case control
    }

    /// A visual effect view carrying glass, or the closest standard material when it is
    /// unavailable.
    static func makeView(role: Role, tint: UIColor? = nil) -> UIVisualEffectView {
        guard !UIAccessibility.isReduceTransparencyEnabled else {
            // Reduce Transparency asks for exactly that. An opaque surface honours it; a
            // blur with a lower alpha would be ignoring the setting politely.
            let view = UIVisualEffectView(effect: nil)
            view.backgroundColor = .secondarySystemBackground
            view.layer.cornerCurve = .continuous
            view.layer.cornerRadius = radius(for: role)
            return view
        }

        let effect = UIGlassEffect(style: .regular)
        effect.isInteractive = role == .control
        if let tint { effect.tintColor = tint }

        let view = UIVisualEffectView(effect: effect)
        // Concentric corners rather than a hardcoded radius: the shape follows whatever it
        // is nested inside, which is what the HIG asks for and what stops a rounded panel
        // looking wrong inside a rounded sheet.
        view.cornerConfiguration = .corners(radius: .containerConcentric(minimum: radius(for: role)))
        return view
    }

    /// Groups nearby glass elements so they merge as they approach one another.
    ///
    /// Without a container, two glass views sitting close together read as two panes of
    /// glass stacked on glass — which the HIG explicitly warns against.
    static func makeContainer(spacing: CGFloat = 12) -> UIVisualEffectView {
        guard !UIAccessibility.isReduceTransparencyEnabled else {
            return UIVisualEffectView(effect: nil)
        }
        let effect = UIGlassContainerEffect()
        effect.spacing = spacing
        return UIVisualEffectView(effect: effect)
    }

    /// What a control is for, which decides how much weight it gets.
    enum Emphasis {
        /// The one action the screen exists for: Sign in, Queue this sighting.
        case primary
        /// Everything else: Use GPS, Refresh totals, Retry.
        case secondary
        /// A text action rather than a button: "Create a contributor account", "Delete my
        /// account". Deliberately **not** glass — Apple gives these no material either, and
        /// a capsule around a link reads as a third button competing with the two real ones.
        case quiet
    }

    /// A **native** glass button configuration.
    ///
    /// `prominentGlass()`, `glass()` and `clearGlass()` are UIKit's own iOS 26
    /// configurations — this is not a hand-rolled imitation of them, which is the point.
    /// Using `.filled()` and `.bordered()` here instead, as this app originally did, gives
    /// buttons that are perfectly functional and visibly pre-iOS-26.
    ///
    /// The tint stays ours: reef teal, so the accent survives the change of material.
    static func makeButtonConfiguration(_ emphasis: Emphasis) -> UIButton.Configuration {
        var configuration: UIButton.Configuration

        if UIAccessibility.isReduceTransparencyEnabled {
            // Reduce Transparency asks for exactly that, so fall back to opaque
            // configurations rather than glass with a lower alpha.
            configuration = switch emphasis {
            case .primary: .filled()
            case .secondary: .gray()
            case .quiet: .plain()
            }
        } else {
            configuration = switch emphasis {
            case .primary: .prominentGlass()
            case .secondary: .glass()
            case .quiet: .plain()
            }
        }

        if emphasis == .primary {
            configuration.baseBackgroundColor = ReefPalette.accent
            // Without this the label inherits the tint and "Sign in" is teal on teal.
            configuration.baseForegroundColor = ReefPalette.onAccent
        }
        configuration.cornerStyle = .capsule
        // Buttons in a form are a full-width tap target, and 50pt clears the 44pt minimum
        // with room for a scaled Dynamic Type label.
        configuration.contentInsets = NSDirectionalEdgeInsets(
            top: 14, leading: 20, bottom: 14, trailing: 20
        )
        return configuration
    }

    /// A destructive glass button, tinted with the signal colour rather than the accent.
    static func makeDestructiveButtonConfiguration() -> UIButton.Configuration {
        var configuration = makeButtonConfiguration(.quiet)
        configuration.baseForegroundColor = ReefPalette.rust
        return configuration
    }

    /// A text field presented the way iOS 26 presents one.
    ///
    /// UIKit has **no glass border style**: `UITextBorderStyle` is still
    /// `none | line | bezel | roundedRect`, and `roundedRect` is the pre-26 look. The native
    /// composition is therefore the field with no border of its own, inside a glass
    /// container — which is what the system's own search field is.
    ///
    /// Returns the container; the caller lays that out and the field fills it.
    static func wrapTextField(_ field: UITextField) -> UIView {
        // No border of its own: the container provides the material, and a rounded-rect
        // border inside a glass panel reads as a control inside a control.
        field.borderStyle = .none
        field.translatesAutoresizingMaskIntoConstraints = false

        let container = makeView(role: .control)
        container.translatesAutoresizingMaskIntoConstraints = false
        container.contentView.addSubview(field)

        NSLayoutConstraint.activate([
            field.leadingAnchor.constraint(equalTo: container.contentView.leadingAnchor, constant: 14),
            field.trailingAnchor.constraint(equalTo: container.contentView.trailingAnchor, constant: -14),
            field.topAnchor.constraint(equalTo: container.contentView.topAnchor, constant: 14),
            field.bottomAnchor.constraint(equalTo: container.contentView.bottomAnchor, constant: -14),
        ])
        return container
    }

    private static func radius(for role: Role) -> CGFloat {
        switch role {
        case .panel: 20
        case .control: 12
        }
    }
}
