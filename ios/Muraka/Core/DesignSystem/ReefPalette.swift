import UIKit

/// The **data** palette.
///
/// Every colour here carries scientific meaning, which is why it comes from a named asset
/// catalogue entry rather than from the system palette or the app's tint. iOS semantic
/// colours shift with appearance, Increase Contrast and Increased Legibility — correct and
/// desirable for chrome, and catastrophic here. A system setting deciding what "bleached"
/// looks like would corrupt the reading, and the same screenshot in the project would be a
/// different colour on a different device.
///
/// Keeping them apart is not a style preference. It is the mechanism that makes the
/// separation enforceable: there is no path from `UIColor.label` into this type.
///
/// Values live in `Resources/Assets.xcassets` and come verbatim from
/// `mobile-shared/design-tokens.json`. Each colourset carries a README explaining why.
enum ReefPalette {
    /// Living tissue. The 0.0 end of the scale.
    static let healthy = UIColor(named: "ConditionHealthy") ?? .systemTeal
    static let healthyDim = UIColor(named: "ConditionHealthyDim") ?? .systemTeal
    /// The 0.35 stop.
    static let mid = UIColor(named: "ConditionMid") ?? .systemTeal
    /// Bare skeleton. The 0.7 stop.
    static let bleached = UIColor(named: "ConditionBleached") ?? .systemGray
    /// The 1.0 stop.
    static let bleachedDim = UIColor(named: "ConditionBleachedDim") ?? .systemGray
    /// No prediction yet. Deliberately outside the teal-to-bone scale.
    static let unassessed = UIColor(named: "ConditionUnassessed") ?? .systemGray

    /// Destructive actions and failures only.
    static let rust = UIColor(named: "SignalRust") ?? .systemRed
    /// Warnings.
    static let amber = UIColor(named: "SignalAmber") ?? .systemOrange
    /// Expert-reviewed provenance. Always paired with a shape and a word (NFR13).
    static let verified = UIColor(named: "SignalVerified") ?? .systemBlue

    /// The app's tint — the one colour shared with the dashboard's chrome.
    static let accent = UIColor(named: "AccentColor") ?? .systemTeal

    /// The severity ramp, 0 to 1, interpolated in sRGB.
    ///
    /// Matches the dashboard legend and the map markers exactly — same numbers, same
    /// colours, all three clients. That, and bone-white bleaching, is most of what makes
    /// them read as one product.
    static func severity(_ value: Double) -> UIColor {
        let t = min(max(value, 0), 1)
        switch t {
        case ..<midStop:
            return lerp(healthy, mid, t / midStop)
        case ..<bleachedStop:
            return lerp(mid, bleached, (t - midStop) / (bleachedStop - midStop))
        default:
            return lerp(bleached, bleachedDim, (t - bleachedStop) / (1 - bleachedStop))
        }
    }

    /// The fill for a patch cell or a whole-photo label.
    static func condition(_ condition: Condition?) -> UIColor {
        switch condition {
        case .healthy: healthy
        case .bleached: bleached
        case nil: unassessed
        }
    }

    private static let midStop = 0.35
    private static let bleachedStop = 0.7

    /// Interpolates in the current trait collection's resolved colours, so a dark-mode ramp
    /// interpolates dark-mode stops rather than light ones.
    private static func lerp(_ from: UIColor, _ to: UIColor, _ t: Double) -> UIColor {
        UIColor { traits in
            let start = from.resolvedColor(with: traits)
            let end = to.resolvedColor(with: traits)

            var (r1, g1, b1, a1): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
            var (r2, g2, b2, a2): (CGFloat, CGFloat, CGFloat, CGFloat) = (0, 0, 0, 0)
            start.getRed(&r1, green: &g1, blue: &b1, alpha: &a1)
            end.getRed(&r2, green: &g2, blue: &b2, alpha: &a2)

            let fraction = CGFloat(min(max(t, 0), 1))
            return UIColor(
                red: r1 + (r2 - r1) * fraction,
                green: g1 + (g2 - g1) * fraction,
                blue: b1 + (b2 - b1) * fraction,
                alpha: a1 + (a2 - a1) * fraction
            )
        }
    }
}
