package mv.muraka.core.model

/**
 * Which appearance the contributor has asked for.
 *
 * [SYSTEM] is the default and is what NFR14 is really about - an app that follows the device
 * is the correct behaviour, and most people never change it. The other two exist because
 * "follows the device" is not the same as "the contributor can choose", and on a boat in
 * bright sun the choice is a practical one rather than a taste.
 *
 * The same three cases, in the same order, as `ThemePreference.swift`.
 */
enum class ThemePreference(val wire: String, val label: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark"),
    ;

    companion object {
        val DEFAULT = SYSTEM

        fun fromWire(value: String?): ThemePreference = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}
