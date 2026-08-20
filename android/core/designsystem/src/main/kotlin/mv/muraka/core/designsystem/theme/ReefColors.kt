package mv.muraka.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The **data** palette.
 *
 * Every colour here carries scientific meaning, which is why it lives in its own type and
 * not in `MaterialTheme.colorScheme`. Material 3 dynamic colour re-tints the whole scheme
 * from the user's wallpaper — correct and desirable for chrome, and catastrophic here. A
 * wallpaper deciding what "bleached" looks like would corrupt the reading, and the same
 * screenshot in the project would be a different colour on a different phone.
 *
 * Keeping them apart is not a style preference. It is the mechanism that makes the
 * separation enforceable: there is no path from `dynamicDarkColorScheme()` into this
 * object, so the mistake cannot be made by accident.
 *
 * Values are copied verbatim from `mobile-shared/design-tokens.json`. When that file
 * changes, this one changes in the same commit.
 */
@Immutable
data class ReefColors(
    /** Living tissue. The 0.0 end of the scale. */
    val healthy: Color,
    val healthyDim: Color,
    /** The 0.35 stop. */
    val mid: Color,
    /** Bare skeleton. The 0.7 stop. */
    val bleached: Color,
    /** The 1.0 stop. */
    val bleachedDim: Color,
    /** No prediction yet. Deliberately outside the teal-to-bone scale. */
    val unassessed: Color,
    /** Destructive actions and failures only. */
    val rust: Color,
    /** Warnings. */
    val amber: Color,
    /** Expert-reviewed provenance. Always paired with a shape and a word (NFR13). */
    val verified: Color,
) {
    /**
     * The severity ramp, 0 to 1, interpolated in sRGB.
     *
     * Matches the dashboard legend and the map markers exactly — same numbers, same
     * colours, all three clients. That, and bone-white bleaching, is most of what makes
     * them read as one product.
     */
    fun severity(value: Double): Color {
        val t = value.coerceIn(0.0, 1.0).toFloat()
        return when {
            t <= MID_STOP -> lerp(healthy, mid, t / MID_STOP)
            t <= BLEACHED_STOP -> lerp(mid, bleached, (t - MID_STOP) / (BLEACHED_STOP - MID_STOP))
            else -> lerp(bleached, bleachedDim, (t - BLEACHED_STOP) / (1f - BLEACHED_STOP))
        }
    }

    /** The fill for a single patch cell or a whole-photo label. */
    fun condition(condition: mv.muraka.core.model.Condition?): Color = when (condition) {
        mv.muraka.core.model.Condition.HEALTHY -> healthy
        mv.muraka.core.model.Condition.BLEACHED -> bleached
        null -> unassessed
    }

    private companion object {
        const val MID_STOP = 0.35f
        const val BLEACHED_STOP = 0.7f
    }
}

/**
 * The dark scheme.
 *
 * The bleached end is bone-white, because that is literally what a bleached reef looks
 * like — the coral's skeleton showing through. A red/green scale would be the wrong
 * metaphor and would fail for roughly one man in twelve.
 */
val DarkReefColors = ReefColors(
    healthy = Color(0xFF2EC8A2),
    healthyDim = Color(0xFF17705D),
    mid = Color(0xFF8ADCC5),
    bleached = Color(0xFFF0E7D9),
    bleachedDim = Color(0xFFBDB0A0),
    unassessed = Color(0xFF5F7C86),
    rust = Color(0xFFE2643D),
    amber = Color(0xFFE3AD4D),
    verified = Color(0xFF67BCE4),
)

/**
 * The light scheme.
 *
 * Not the dark one lightened. On a light surface bone-white is invisible, so the bleached
 * end becomes parched sand; what is preserved is the *direction* of the scale —
 * saturated life to drained skeleton — rather than the hue.
 */
val LightReefColors = ReefColors(
    healthy = Color(0xFF0F8168),
    healthyDim = Color(0xFF0A5B49),
    mid = Color(0xFF47A289),
    bleached = Color(0xFFA4855C),
    bleachedDim = Color(0xFFC8B795),
    unassessed = Color(0xFF8AA0A8),
    rust = Color(0xFFB4441F),
    amber = Color(0xFFA3741B),
    verified = Color(0xFF2A6D93),
)

/**
 * Reached as `MurakaTheme.reef`, never through `MaterialTheme`.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf`: the value changes only
 * when the whole theme does, so the extra invalidation tracking would be paid for
 * nothing.
 */
val LocalReefColors: ProvidableCompositionLocal<ReefColors> =
    staticCompositionLocalOf { DarkReefColors }
