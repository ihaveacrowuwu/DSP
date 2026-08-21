import UIKit

/// The spacing scale.
///
/// Every gap and inset in the app comes from here. Before this existed the screens each
/// invented their own values — 8 here, 12 there, 20 in one row and 16 in the next — and the
/// result reads as unplanned even when each individual number is defensible. A scale is what
/// makes a layout look designed rather than assembled.
///
/// The same six steps as `ReefSpacing.kt`, because a spacing scale is layout, not chrome, and
/// there is no reason for the two apps to disagree about how far apart two related things sit.
enum Spacing {
    /// Between a label and the value it labels.
    static let xs: CGFloat = 4
    /// Between related rows inside a card.
    static let sm: CGFloat = 8
    /// Between a card's own sections.
    static let md: CGFloat = 12
    /// A card's internal padding, and the screen's outer margin.
    static let lg: CGFloat = 16
    /// Between cards, and above a section heading.
    static let xl: CGFloat = 20
    /// Around an empty state, and below the last card.
    static let xxl: CGFloat = 32

    /// Bottom inset for a scrolling view under the floating tab bar and capture button.
    static let listBottom: CGFloat = 96
}
