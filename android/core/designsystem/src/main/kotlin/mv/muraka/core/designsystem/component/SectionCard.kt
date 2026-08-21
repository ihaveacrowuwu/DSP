package mv.muraka.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mv.muraka.core.designsystem.theme.ReefSpacing

/**
 * One titled group of related information.
 *
 * The unit the sighting and config screens are built from. A screen made of these reads as
 * a small number of labelled things; the same content laid out as one long column of
 * headings and rows reads as a list of facts the reader has to group themselves.
 *
 * The title is optional because the first card on a screen is often self-evident — a
 * photograph does not need to be labelled "Photograph".
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    /** Shown at the end of the title row: a count, a toggle, a timestamp. */
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(ReefSpacing.Lg),
            verticalArrangement = Arrangement.spacedBy(ReefSpacing.Md),
        ) {
            if (title != null || trailing != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (title != null) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    }
                    trailing?.invoke()
                }
            }
            content()
        }
    }
}

/**
 * A row of readouts that share a baseline.
 *
 * Measurements belong in a row, evenly spaced, so the eye can compare them — which is the
 * whole reason they are monospaced in the first place. Stacked one per line they stop being
 * a set of related numbers and become a list.
 */
@Composable
fun ReadoutRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ReefSpacing.Xl),
        verticalAlignment = Alignment.Top,
    ) {
        content()
    }
}
