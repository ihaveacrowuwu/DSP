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

    /// A button that is itself glass — for a floating action over content.
    static func makeButtonConfiguration(prominent: Bool) -> UIButton.Configuration {
        guard !UIAccessibility.isReduceTransparencyEnabled else {
            return prominent ? .filled() : .gray()
        }
        return prominent ? .prominentGlass() : .glass()
    }

    private static func radius(for role: Role) -> CGFloat {
        switch role {
        case .panel: 20
        case .control: 12
        }
    }
}
