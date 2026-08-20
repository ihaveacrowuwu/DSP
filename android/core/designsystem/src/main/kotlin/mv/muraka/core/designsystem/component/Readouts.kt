package mv.muraka.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.designsystem.theme.ReadoutStyle
import mv.muraka.core.model.Condition
import mv.muraka.core.model.Prediction
import mv.muraka.core.model.SightingDisplayStatus
import kotlin.math.roundToInt

/**
 * A measured quantity, monospaced with tabular figures.
 *
 * Every number in this app goes through here: coordinates, depths, severities, counts,
 * model versions. It is the cheapest piece of family resemblance the three clients have
 * and the strongest — columns line up, and the interface reads like an instrument.
 *
 * Only the family is fixed. The size comes from [style], so it still scales with the
 * user's font-size setting.
 */
@Composable
fun Readout(
    value: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
) {
    Column(modifier = modifier) {
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = style.merge(ReadoutStyle),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * What the model made of a photograph.
 *
 * **Severity leads, not the label.** "62% bleached" tells a contributor something that
 * "bleached" does not — it is the difference between a reef in trouble and a reef with a
 * few pale colonies, and it is the number researchers actually work with.
 *
 * The model version is shown because it is provenance: `fake-0.0.0` means no trained
 * model is loaded yet, and a reader of the project needs to be able to tell which
 * screenshots predate the real one.
 */
@Composable
fun PredictionReadout(prediction: Prediction, verified: Boolean, modifier: Modifier = Modifier) {
    val extent = (prediction.severity * 100).roundToInt()
    val confidence = (prediction.confidence * 100).roundToInt()

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SeveritySwatch(prediction.severity)
            Text(
                text = "$extent% bleached",
                style = MaterialTheme.typography.titleMedium.merge(ReadoutStyle),
            )
            ProvenanceChip(verified = verified)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Readout(label = "Confidence", value = "$confidence%")
            Readout(label = "Grid", value = "${prediction.patchGrid}×${prediction.patchGrid}")
            prediction.inferenceMs?.let { Readout(label = "Inference", value = "$it ms") }
        }

        Readout(
            label = "Model",
            value = prediction.modelVersion,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * A block of the severity ramp.
 *
 * A graphic, never text: the condition colours are fills, and body text set in them would
 * fail contrast against half the surfaces it could land on. The number beside it carries
 * the meaning.
 */
@Composable
fun SeveritySwatch(severity: Double, modifier: Modifier = Modifier) {
    val reef = MurakaTheme.reef
    Surface(
        modifier = modifier
            .clearAndSetSemantics { },
        shape = RoundedCornerShape(4.dp),
        color = reef.severity(severity),
    ) {
        androidx.compose.foundation.layout.Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp))
    }
}

/**
 * The contributor-facing status of a sighting.
 *
 * The vocabulary is [SightingDisplayStatus] and nothing else — there is no "Synced",
 * because a local flag claiming the upload worked is a claim rather than a fact. The
 * colour is decoration; the word is the information, which is what makes this legible in
 * a greyscale screenshot.
 */
@Composable
fun StatusPill(status: SightingDisplayStatus, modifier: Modifier = Modifier) {
    val reef = MurakaTheme.reef
    val colour = when (status) {
        SightingDisplayStatus.VERIFIED_BY_EXPERT -> reef.verified
        SightingDisplayStatus.NOT_USABLE -> reef.rust
        SightingDisplayStatus.FAILED -> reef.rust
        SightingDisplayStatus.WAITING_TO_UPLOAD,
        SightingDisplayStatus.UPLOADING,
        SightingDisplayStatus.CHECKING,
        SightingDisplayStatus.PHOTOS_PENDING,
        -> reef.amber

        SightingDisplayStatus.ANALYSING,
        SightingDisplayStatus.AWAITING_REVIEW,
        -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "Healthy" or "Bleached", as a word, for places where only the label is available. */
@Composable
fun ConditionLabel(condition: Condition?, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier,
        text = when (condition) {
            Condition.HEALTHY -> "Healthy"
            Condition.BLEACHED -> "Bleached"
            null -> "Not yet assessed"
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
