package mv.muraka.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import mv.muraka.core.designsystem.theme.MurakaTheme

/**
 * Says whether a label came from the model or from an expert.
 *
 * NFR13, and the single most important rule in the whole interface: **a model label must
 * never be mistaken for an expert verdict.** The distinction is carried three ways over,
 * so that losing any one of them still leaves it legible:
 *
 * 1. **Shape** - a dashed outline for the model, a solid filled surface for an expert.
 * 2. **A marker** - hollow for the model, filled for an expert.
 * 3. **A word** - literally "model" or "expert".
 *
 * That redundancy is the requirement. It survives greyscale, it survives colour
 * blindness, and it survives a screenshot pasted into a document at 60% scale.
 *
 * *Standard component rejected:* M3's chips (`AssistChip`, `FilterChip`, `SuggestionChip`)
 * are all interactive by definition - they take an `onClick` and carry the affordances of
 * something you can press. This is a static provenance label, and dressing a button up as
 * one would invite a tap that does nothing. `Surface` plus the M3 shape and label styles
 * gets the same visual language without the false affordance.
 */
@Composable
fun ProvenanceChip(verified: Boolean, modifier: Modifier = Modifier) {
    val reef = MurakaTheme.reef
    val accent = if (verified) reef.verified else MaterialTheme.colorScheme.onSurfaceVariant
    val description = if (verified) {
        "Verified by an expert"
    } else {
        "Automatic assessment by the model, not yet reviewed"
    }

    Surface(
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        shape = RoundedCornerShape(8.dp),
        // Filled for an expert, transparent for the model: the weight of the thing on the
        // screen matches the weight of the claim.
        color = if (verified) accent.copy(alpha = 0.16f) else Color.Transparent,
        contentColor = accent,
    ) {
        Row(
            modifier = Modifier
                .dashedBorderWhen(!verified, accent)
                .solidBorderWhen(verified, accent)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProvenanceMarker(filled = verified, color = accent)
            Text(
                text = if (verified) "expert" else "model",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/** Filled disc for an expert, hollow ring for the model. */
@Composable
private fun ProvenanceMarker(filled: Boolean, color: Color) {
    val strokeWidth = with(LocalDensity.current) { 1.5.dp.toPx() }
    androidx.compose.foundation.Canvas(modifier = Modifier.size(8.dp)) {
        if (filled) {
            drawCircle(color = color)
        } else {
            drawCircle(color = color, style = Stroke(width = strokeWidth))
        }
    }
}

/**
 * A dashed outline.
 *
 * Compose has no dashed-border modifier, and `BorderStroke` cannot express one, so this
 * draws it - which is the only reason there is custom drawing in a component whose whole
 * argument is to prefer the platform's own.
 */
private fun Modifier.dashedBorderWhen(enabled: Boolean, color: Color): Modifier = if (!enabled) {
    this
} else {
    drawBehind {
        val stroke = 1.dp.toPx()
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(width = stroke, pathEffect = dash),
        )
    }
}

private fun Modifier.solidBorderWhen(enabled: Boolean, color: Color): Modifier = if (!enabled) {
    this
} else {
    drawBehind {
        val stroke = 1.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(width = stroke),
        )
    }
}
