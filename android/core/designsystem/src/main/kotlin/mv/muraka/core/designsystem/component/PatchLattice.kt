package mv.muraka.core.designsystem.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.model.Condition
import mv.muraka.core.model.Patch

/**
 * The patch lattice - the model's reasoning, drawn.
 *
 * The classifier tiles a photograph into a `patchGrid x patchGrid` grid and judges each
 * cell, so drawing that grid is not decoration: it is the only way a contributor or a
 * researcher can see *where* the model thinks the bleaching is, and disagree with it.
 * It is the element all three clients share.
 *
 * Two things about it are easy to get wrong and both make it lie:
 *
 * **The geometry.** Cells cover the **centre square** of the photograph, because that is
 * how the server tiled it. Stretching the lattice across a non-square frame puts cell
 * (0,0) over pixels the model never saw.
 *
 * **The opacity.** There are two formulas, not one, and the difference is deliberate
 * see [LatticeMode].
 */
enum class LatticeMode {
    /**
     * Drawn on top of a photograph: `0.28 + confidence x 0.42`.
     *
     * The range stops well short of solid on purpose. Past roughly 0.7 the cells stop
     * annotating the reef and start replacing it, and a contributor cannot check a
     * judgement against coral they can no longer see.
     */
    OVERLAY,

    /**
     * The small standalone glyph in a list row, with no photograph behind it:
     * `0.45 + confidence x 0.55`.
     *
     * Nothing is being obscured here, so the full range is available and a hesitant model
     * can look properly hesitant.
     */
    GLYPH,
    ;

    fun opacity(confidence: Double): Float = when (this) {
        OVERLAY -> (0.28 + confidence.coerceIn(0.0, 1.0) * 0.42).toFloat()
        GLYPH -> (0.45 + confidence.coerceIn(0.0, 1.0) * 0.55).toFloat()
    }
}

/** The default grid the server uses; the real value always comes from the prediction. */
const val DEFAULT_PATCH_GRID = 5

private val CellGap = 1.dp

/** The glyph's fixed size, from `design-tokens.json`. */
val LatticeGlyphSize: Dp = 34.dp

/**
 * Draws the lattice.
 *
 * [modifier] must size this to the photograph it annotates when [mode] is
 * [LatticeMode.OVERLAY]; the centre square is computed from whatever size it is given.
 */
@Composable
fun PatchLattice(patches: List<Patch>, grid: Int, mode: LatticeMode, modifier: Modifier = Modifier) {
    if (patches.isEmpty() || grid <= 0) return

    val reef = MurakaTheme.reef
    val gapPx = with(LocalDensity.current) { CellGap.toPx() }
    val description = latticeDescription(patches, grid)

    Box(
        modifier = modifier
            .then(if (mode == LatticeMode.GLYPH) Modifier.size(LatticeGlyphSize) else Modifier)
            // A screen reader gets the tally, because the lattice's meaning is a
            // proportion and that survives being read aloud. Announcing 25 cells would
            // not.
            .semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // The centre square, matching how the server tiled the image. Anything else
            // misaligns the cells with the pixels that were actually classified.
            val side = minOf(size.width, size.height)
            val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
            val cell = side / grid

            patches.forEach { patch ->
                if (patch.row !in 0 until grid || patch.col !in 0 until grid) return@forEach

                val fill = when (patch.label) {
                    Condition.HEALTHY -> reef.healthy
                    Condition.BLEACHED -> reef.bleached
                }

                drawRect(
                    color = fill.copy(alpha = mode.opacity(patch.confidence)),
                    topLeft = Offset(
                        origin.x + patch.col * cell + gapPx / 2f,
                        origin.y + patch.row * cell + gapPx / 2f,
                    ),
                    size = Size((cell - gapPx).coerceAtLeast(0f), (cell - gapPx).coerceAtLeast(0f)),
                    // Hard-light keeps the reef's own texture visible through the tint
                    // instead of flooding it. Where a platform cannot blend cheaply the
                    // fallback is normal compositing at the SAME opacity - never a
                    // higher one to compensate, which would defeat the point.
                    blendMode = if (mode == LatticeMode.OVERLAY) BlendMode.Hardlight else BlendMode.SrcOver,
                )
            }
        }
    }
}

/**
 * "14 of 25 patches classified bleached".
 *
 * A proportion rather than a cell-by-cell reading, because the proportion is what the
 * lattice is for.
 */
fun latticeDescription(patches: List<Patch>, grid: Int): String {
    val bleached = patches.count { it.label == Condition.BLEACHED }
    val total = if (patches.isNotEmpty()) patches.size else grid * grid
    return "$bleached of $total patches classified bleached"
}

/** A flat swatch for a photograph with no prediction yet. Absent is not an error. */
@Composable
fun UnassessedLatticePlaceholder(modifier: Modifier = Modifier) {
    val reef = MurakaTheme.reef
    Box(
        modifier = modifier
            .size(LatticeGlyphSize)
            .semantics { contentDescription = "Not yet assessed" },
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            drawRect(color = reef.unassessed.copy(alpha = 0.35f), size = size)
        }
    }
}
