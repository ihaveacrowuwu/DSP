package mv.muraka.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GridOff
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import mv.muraka.core.designsystem.component.LatticeMode
import mv.muraka.core.designsystem.component.LoadingState
import mv.muraka.core.designsystem.component.PatchLattice
import mv.muraka.core.designsystem.component.ProvenanceChip
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.component.ReadoutRow
import mv.muraka.core.designsystem.component.SectionCard
import mv.muraka.core.designsystem.component.SeveritySwatch
import mv.muraka.core.designsystem.component.StatusPill
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.designsystem.theme.ReefSpacing
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.Photo
import mv.muraka.core.model.Prediction
import mv.muraka.core.model.Verification
import mv.muraka.core.model.VerificationDecision
import mv.muraka.ui.common.authedPhotoUrl
import mv.muraka.ui.common.relativeAge
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One sighting.
 *
 * Built from titled cards rather than one long column: a photograph, what the model made of
 * it, where and when it was taken, and what an expert said. Grouping is the difference
 * between a screen a contributor can read at a glance and a list of facts they have to sort
 * out for themselves.
 *
 * Refreshes on open, which is the read-back that turns "Checking..." into whatever the server
 * actually says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingDetailScreen(viewModel: SightingDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val sighting by viewModel.sighting.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val showGrid by viewModel.showPatchGrid.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Sighting") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        val current = sighting
        if (current == null) {
            LoadingState(modifier = Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ReefSpacing.Lg)
                .padding(bottom = ReefSpacing.Xxl),
            verticalArrangement = Arrangement.spacedBy(ReefSpacing.Md),
        ) {
            StatusHeader(
                status = current.summary.displayStatus,
                checkedAt = current.summary.serverReadAt?.relativeAge(),
                message = state.message,
            )

            // Photographs still on the device, so a queued sighting is not a blank screen
            // while it waits for a connection. No lattice: the model has not seen these yet.
            current.pendingPhotoPaths.forEachIndexed { index, path ->
                SectionCard(title = "Waiting to upload") {
                    PhotoFrame {
                        AsyncImage(
                            model = path,
                            contentDescription = "Photograph ${index + 1}, waiting to upload",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            current.photos.forEachIndexed { index, photo ->
                PhotographCard(
                    photo = photo,
                    index = index,
                    total = current.photos.size,
                    showGrid = showGrid,
                    onShowGridChange = viewModel::onShowPatchGridChange,
                )
                photo.prediction?.let { prediction ->
                    AssessmentCard(
                        prediction = prediction,
                        verified = current.summary.server?.verified == true,
                    )
                }
            }

            WhereAndWhenCard(current)

            if (current.verifications.isNotEmpty()) {
                SectionCard(title = "Expert review") {
                    current.verifications.forEach { VerificationRow(it) }
                }
            }
        }
    }
}

/** Status, how fresh it is, and any refresh problem - the three things read first. */
@Composable
private fun StatusHeader(status: mv.muraka.core.model.SightingDisplayStatus, checkedAt: String?, message: String?) {
    Column(
        modifier = Modifier.padding(top = ReefSpacing.Sm),
        verticalArrangement = Arrangement.spacedBy(ReefSpacing.Xs),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ReefSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusPill(status)
            checkedAt?.let {
                Text(
                    // The age of the KNOWLEDGE, not of the sighting.
                    text = "checked $it",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MurakaTheme.reef.amber)
        }
    }
}

/**
 * A photograph, with the lattice over it and a toggle for the lattice.
 *
 * The toggle exists because the lattice is an annotation and an annotation you cannot
 * remove is an obstruction. Turning it off is how a contributor checks the model's reading
 * against the reef rather than against the model's own drawing of it - which is the whole
 * argument for drawing the grid in the first place.
 */
@Composable
private fun PhotographCard(
    photo: Photo,
    index: Int,
    total: Int,
    showGrid: Boolean,
    onShowGridChange: (Boolean) -> Unit,
) {
    val hasPrediction = photo.prediction != null

    SectionCard(
        // Always titled, even for a single photograph. The card carries the grid toggle in
        // its title row, and a header row holding nothing but a right-aligned button reads
        // as a gap rather than as a control belonging to something.
        title = if (total > 1) "Photograph ${index + 1} of $total" else "Photograph",
        trailing = if (hasPrediction) {
            {
                FilledIconToggleButton(
                    checked = showGrid,
                    onCheckedChange = onShowGridChange,
                    modifier = Modifier.semantics {
                        contentDescription = if (showGrid) {
                            "Hide the model's grid"
                        } else {
                            "Show the model's grid"
                        }
                    },
                ) {
                    Icon(
                        imageVector = if (showGrid) Icons.Filled.GridOn else Icons.Filled.GridOff,
                        contentDescription = null,
                    )
                }
            }
        } else {
            null
        },
    ) {
        PhotoFrame {
            AsyncImage(
                model = authedPhotoUrl(photo.id),
                contentDescription = "Reef photograph",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            photo.prediction?.takeIf { showGrid }?.let { prediction ->
                PatchLattice(
                    patches = prediction.patches,
                    grid = prediction.patchGrid,
                    mode = LatticeMode.OVERLAY,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (hasPrediction) {
            Text(
                text = if (showGrid) {
                    "The grid is the model's own reading, cell by cell. Turn it off to see the reef."
                } else {
                    "Showing the photograph as taken."
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What the model made of the photograph.
 *
 * Severity leads: "6% bleached" tells a contributor something "bleached" does not, and it is
 * the number researchers work with. The provenance chip sits beside it rather than below,
 * because the two are one claim and separating them is how a model label gets read as fact.
 */
@Composable
private fun AssessmentCard(prediction: Prediction, verified: Boolean) {
    SectionCard(title = "Assessment") {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ReefSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SeveritySwatch(prediction.severity)
            Text(
                text = "${(prediction.severity * 100).roundToInt()}% bleached",
                style = MaterialTheme.typography.titleLarge.merge(
                    mv.muraka.core.designsystem.theme.ReadoutStyle,
                ),
                modifier = Modifier.weight(1f, fill = false),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ProvenanceChip(verified = verified)
        }

        ReadoutRow {
            Readout(
                label = "Confidence",
                value = "${(prediction.confidence * 100).roundToInt()}%",
                style = MaterialTheme.typography.bodyMedium,
            )
            Readout(
                label = "Grid",
                value = "${prediction.patchGrid}×${prediction.patchGrid}",
                style = MaterialTheme.typography.bodyMedium,
            )
            prediction.inferenceMs?.let {
                Readout(
                    label = "Inference",
                    value = "$it ms",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Readout(
            // Provenance: `fake-0.0.0` means no trained model is loaded yet, and a reader of
            // the project needs to be able to tell which screenshots predate the real one.
            label = "Model",
            value = prediction.modelVersion,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun WhereAndWhenCard(detail: mv.muraka.core.domain.SightingWithDetail) {
    SectionCard(title = "Where and when") {
        ReadoutRow {
            Readout(
                label = if (detail.summary.locationSource == LocationSource.GPS) {
                    "GPS"
                } else {
                    "Dropped pin"
                },
                value = String.format(
                    Locale.UK,
                    "%.5f, %.5f",
                    detail.summary.position.lat,
                    detail.summary.position.lon,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            detail.summary.server?.depthM?.let {
                Readout(
                    label = "Depth",
                    value = "${it.roundToInt()} m",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Text(
            text = "Captured ${detail.summary.capturedAt.relativeAge()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        detail.summary.server?.siteName?.let {
            Readout(label = "Site", value = it, style = MaterialTheme.typography.bodyMedium)
        }

        detail.summary.server?.note?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun VerificationRow(verification: Verification) {
    Column(verticalArrangement = Arrangement.spacedBy(ReefSpacing.Xs)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(ReefSpacing.Sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProvenanceChip(verified = true)
            Text(
                text = when (verification.decision) {
                    VerificationDecision.CONFIRMED -> "Confirmed the model"
                    VerificationDecision.CORRECTED -> "Corrected the model"
                    VerificationDecision.REJECTED -> "Rejected this photograph"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // Rejected sightings vanish from research views but remain the contributor's own
        // record (FR11), so the reason is shown rather than hidden.
        verification.rejectReason?.let {
            Text(
                text = "Reason: ${it.wire.replace('_', ' ')}",
                style = MaterialTheme.typography.bodySmall,
                color = MurakaTheme.reef.rust,
            )
        }
        verification.comment?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            text = verification.createdAt.relativeAge(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One fixed square frame for every photograph.
 *
 * The dashboard learnt this the hard way (D24): sizing each frame to its source made the
 * 224 px dataset crops too small to judge, and made two photographs of the same reef look
 * like different sizes of thing. A square also matches the centre square the server tiles,
 * so the lattice lands where the model actually looked.
 */
@Composable
private fun PhotoFrame(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(ReefSpacing.Md),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}
