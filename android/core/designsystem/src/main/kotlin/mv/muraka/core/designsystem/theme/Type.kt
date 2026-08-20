package mv.muraka.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * The Material 3 type ramp, unmodified.
 *
 * Sizes are deliberately **not** overridden. They scale with the user's font-size setting,
 * and a fixed 14sp body is a bug rather than a design — `mobile-shared/design-tokens.json`
 * says so in as many words, and it is an accessibility requirement rather than polish.
 * What is overridden is the *family*, and only for readouts.
 */
val MurakaTypography = Typography()

/**
 * Monospaced, tabular figures, for every measured quantity — a coordinate, a depth, a
 * severity, a count, a model version.
 *
 * This is the cheapest and strongest piece of family resemblance the three clients have:
 * columns of numbers line up, and the interface reads like an instrument rather than a
 * feed. It costs one font family on each platform.
 *
 * Only the family is set. The size comes from whichever M3 style it is merged into, so it
 * still scales with the user's setting.
 */
val ReadoutStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
)

/** A right-aligned readout, for numbers in a column. */
val ReadoutColumnStyle = ReadoutStyle.copy(textAlign = TextAlign.End)

/**
 * The one place a size is hardcoded: the patch lattice's own caption, which sits inside a
 * fixed-size glyph and would overflow it if it scaled.
 *
 * The information is not lost when it cannot grow — the same numbers appear as ordinary
 * scaling text in the assessment panel beside it, which is what a contributor using a
 * large font size will actually read.
 */
val LatticeCaptionSize = 10.sp
