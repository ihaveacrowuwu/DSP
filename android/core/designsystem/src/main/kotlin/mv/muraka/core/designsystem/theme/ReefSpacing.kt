package mv.muraka.core.designsystem.theme

import androidx.compose.ui.unit.dp

/**
 * The spacing scale.
 *
 * Every gap and inset in the app comes from here. Before this existed the screens each
 * invented their own values - 8 here, 12 there, 20 in one row and 16 in the next - and the
 * result reads as unplanned even when each individual number is defensible. A scale is what
 * makes a layout look designed rather than assembled.
 *
 * Material 3's own spacing guidance is a 4dp grid, so these are all multiples of 4.
 */
object ReefSpacing {
    /** Between a label and the value it labels. */
    val Xs = 4.dp

    /** Between related rows inside a card. */
    val Sm = 8.dp

    /** Between a card's own sections. */
    val Md = 12.dp

    /** A card's internal padding, and the screen's outer margin. */
    val Lg = 16.dp

    /** Between cards, and above a section heading. */
    val Xl = 20.dp

    /** Around an empty state, and below the last card so a FAB cannot cover it. */
    val Xxl = 32.dp

    /**
     * Bottom padding for a scrolling list under the floating action button.
     *
     * The FAB is 56dp plus its 16dp margin; this clears both with room to spare, so the
     * last row is reachable rather than half-hidden.
     */
    val ListBottom = 96.dp
}
