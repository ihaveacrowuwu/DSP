import UIKit

/// Which appearance the contributor has asked for.
///
/// ``system`` is the default and is what NFR14 is really about — an app that follows the
/// device is the correct behaviour, and most people never change it. The other two exist
/// because "follows the device" is not the same as "the contributor can choose", and on a
/// boat in bright sun the choice is a practical one rather than a taste.
///
/// The same three cases, in the same order, as `ThemePreference.kt`.
enum ThemePreference: String, CaseIterable, Sendable {
    case system
    case light
    case dark

    static let `default` = ThemePreference.system

    var label: String {
        switch self {
        case .system: "System"
        case .light: "Light"
        case .dark: "Dark"
        }
    }

    /// What the caption under the control says, so the choice is not just a highlighted pill.
    var explanation: String {
        switch self {
        case .system: "Following your device setting."
        case .light: "Always light, whatever your device is set to."
        case .dark: "Always dark, whatever your device is set to."
        }
    }

    /// How UIKit expresses it.
    ///
    /// `.unspecified` is the one that matters: it means "defer to the system", which is what
    /// makes `system` genuinely follow the device rather than snapshotting it once.
    var interfaceStyle: UIUserInterfaceStyle {
        switch self {
        case .system: .unspecified
        case .light: .light
        case .dark: .dark
        }
    }

    init(wire: String?) {
        self = ThemePreference(rawValue: wire ?? "") ?? .default
    }
}

/// Where the contributor's appearance choice lives.
///
/// `UserDefaults`, not the Keychain: it is a display preference, not a credential. It should
/// survive signing out, and it has nothing to hide. Storing it with the session would mean
/// signing out silently reset it.
///
/// `@MainActor` rather than `Sendable`: `UserDefaults` is not `Sendable`, and this is only
/// ever touched while applying or changing an appearance — both of which are main-actor work
/// by definition. Isolating it is the honest answer; `@unchecked Sendable` would have been
/// the quick one.
@MainActor
final class AppearanceStore {
    private let defaults: UserDefaults
    private let key = "muraka.theme"
    private let gridKey = "muraka.showPatchGrid"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    var preference: ThemePreference {
        get { ThemePreference(wire: defaults.string(forKey: key)) }
        set { defaults.set(newValue.rawValue, forKey: key) }
    }

    /// Whether the patch lattice is drawn over photographs.
    ///
    /// Defaults to **on**: it is the point of the detail screen, and a contributor who has
    /// never seen it cannot know to turn it on. Remembered rather than reset per screen,
    /// because somebody comparing several sightings against the raw photographs should not
    /// have to turn it off once per sighting.
    var showPatchGrid: Bool {
        // `object(forKey:)` rather than `bool(forKey:)`, which returns false for a missing
        // key and would make the default off.
        get { defaults.object(forKey: gridKey) as? Bool ?? true }
        set { defaults.set(newValue, forKey: gridKey) }
    }

    /// Returns both preferences to their defaults, for the UI tests' known-state launch
    /// argument. Debug-only: nothing in a shipping build has any business erasing a choice
    /// the contributor made.
    #if DEBUG
        func resetForUITests() {
            defaults.removeObject(forKey: key)
            defaults.removeObject(forKey: gridKey)
        }
    #endif
}

extension Notification.Name {
    /// Posted when the appearance choice changes.
    ///
    /// The scene owns the window, and the window is the only thing that can apply an
    /// interface style to the whole app — including screens the profile controller cannot
    /// reach. A notification is how a leaf controller reaches it without holding a reference
    /// to the window it happens to be in.
    static let murakaThemePreferenceChanged = Notification.Name("mv.muraka.themePreferenceChanged")
}
