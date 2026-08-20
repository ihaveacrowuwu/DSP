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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import mv.muraka.core.designsystem.component.LatticeMode
import mv.muraka.core.designsystem.component.LoadingState
import mv.muraka.core.designsystem.component.PatchLattice
import mv.muraka.core.designsystem.component.PredictionReadout
import mv.muraka.core.designsystem.component.ProvenanceChip
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.component.StatusPill
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.Photo
import mv.muraka.core.model.Verification
import mv.muraka.core.model.VerificationDecision
import mv.muraka.ui.common.authedPhotoUrl
import mv.muraka.ui.common.relativeAge
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingDetailScreen(viewModel: SightingDetailViewModel, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val sighting by viewModel.sighting.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(current.summary.displayStatus)
                current.summary.serverReadAt?.let {
                    Text(
                        text = "checked ${it.relativeAge()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MurakaTheme.reef.amber)
            }

            // Photographs still on the device, shown from their local files so a queued
            // sighting is not a blank screen while it waits for a connection.
            current.pendingPhotoPaths.forEach { path ->
                PhotoFrame {
                    AsyncImage(
                        model = path,
                        contentDescription = "A photograph waiting to upload",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            current.photos.forEach { photo ->
                PhotographWithAssessment(photo = photo, verified = current.summary.server?.verified == true)
            }

            HorizontalDivider()

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Where and when", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Readout(
                        label = if (current.summary.locationSource == LocationSource.GPS) {
                            "GPS"
                        } else {
                            "Dropped pin"
                        },
                        value = String.format(
                            Locale.UK,
                            "%.5f, %.5f",
                            current.summary.position.lat,
                            current.summary.position.lon,
                        ),
                    )
                    current.summary.server?.depthM?.let {
                        Readout(label = "Depth", value = "${it.toInt()} m")
                    }
                }
                Text(
                    text = "Captured ${current.summary.capturedAt.relativeAge()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                current.summary.server?.note?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (current.verifications.isNotEmpty()) {
                HorizontalDivider()
                Text("Expert review", style = MaterialTheme.typography.titleMedium)
                current.verifications.forEach { VerificationCard(it) }
            }
        }
    }
}

/**
 * A photograph with the model's reading of it.
 *
 * The lattice sits **over** the photograph and the numbers sit **beside** it, mirroring
 * the dashboard's layout decision (D24) for the same reason: the reef has to stay visible
 * while the judgement is being checked against it.
 */
@Composable
private fun PhotographWithAssessment(photo: Photo, verified: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhotoFrame {
            AsyncImage(
                model = authedPhotoUrl(photo.id),
                contentDescription = "Reef photograph",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )

            photo.prediction?.let { prediction ->
                PatchLattice(
                    patches = prediction.patches,
                    grid = prediction.patchGrid,
                    mode = LatticeMode.OVERLAY,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        val prediction = photo.prediction
        if (prediction == null) {
            // Absent is not an error. Say what is happening rather than showing nothing.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Not yet assessed by the model.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            PredictionReadout(prediction = prediction, verified = verified)
        }
    }
}

/**
 * One fixed frame for every photograph.
 *
 * The dashboard learnt this the hard way (D24): sizing each frame to its source made the
 * 224 px dataset crops too small to judge, and made two photographs of the same reef look
 * like different sizes of thing. A square frame also matches the centre square the server
 * tiles, so the lattice lands where the model actually looked.
 */
@Composable
private fun PhotoFrame(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }
    }
}

@Composable
private fun VerificationCard(verification: Verification) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

            // Rejected sightings vanish from research views but remain the contributor's
            // own record (FR11), so the reason is shown rather than hidden.
            verification.rejectReason?.let {
                Text(
                    text = "Reason: ${it.wire.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MurakaTheme.reef.rust,
                )
            }
            verification.comment?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = verification.createdAt.relativeAge(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
