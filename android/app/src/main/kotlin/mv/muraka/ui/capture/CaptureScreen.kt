package mv.muraka.ui.capture

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.model.CaptureLimits
import mv.muraka.core.model.Condition
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.Position
import java.util.Locale

/**
 * Capturing a sighting.
 *
 * Everything is arranged around NFR6 - under 60 seconds and at most 8 taps:
 *
 * ```
 * 1  the FAB              2  "Take a photograph"    3  the shutter
 * 4  close the camera     5  Queue this sighting
 * ```
 *
 * Five taps for the required path. Position is requested when the screen opens rather
 * than being a step, and depth, note and self-assessment are optional and out of the way.
 * The remaining headroom is what a contributor spends on those if they want to.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(viewModel: CaptureViewModel, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    var showSourceSheet by remember { mutableStateOf(false) }
    var showCamera by remember { mutableStateOf(false) }
    var showPinEntry by remember { mutableStateOf(false) }

    // Permission is requested in context, at the moment of capture, never on launch
    // mobile-shared/README.md non-negotiable 6.
    val locationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.requestPosition() }

    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> showCamera = granted }

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(CaptureLimits.MAX_PHOTOS),
    ) { uris -> uris.forEach(viewModel::addPhoto) }

    LaunchedEffect(Unit) {
        locationPermission.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    // The sighting is on disk and the drain loop owns it now. Leaving immediately is
    // correct: waiting for an upload would be waiting for a network the contributor may
    // not have for hours.
    LaunchedEffect(state.queued) { if (state.queued) onDone() }

    if (showCamera) {
        CameraCapture(
            onCaptured = { bytes ->
                viewModel.addPhoto(bytes)
                showCamera = false
            },
            onDismiss = { showCamera = false },
            remaining = CaptureLimits.MAX_PHOTOS - state.photos.size,
        )
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("New sighting") },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.Filled.Close, contentDescription = "Discard this sighting")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            PhotoStrip(
                photos = state.photos,
                canAdd = state.canAddPhoto,
                onAdd = { showSourceSheet = true },
                onRemove = viewModel::removePhoto,
                error = state.fieldErrors["photos"],
            )

            PositionSection(
                state = state,
                onRetryFix = viewModel::requestPosition,
                onDropPin = { showPinEntry = true },
            )

            OutlinedTextField(
                value = state.depthText,
                onValueChange = viewModel::onDepthChange,
                label = { Text("Depth in metres (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Next,
                ),
                isError = state.fieldErrors.containsKey("depthM"),
                supportingText = state.fieldErrors["depthM"]?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.note,
                onValueChange = viewModel::onNoteChange,
                label = { Text("Note (optional)") },
                supportingText = { Text("${state.remainingNoteCharacters} characters left") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            SelfAssessment(
                selected = state.selfAssessment,
                onSelect = viewModel::onSelfAssessmentChange,
            )

            state.message?.let {
                Text(text = it, color = MurakaTheme.reef.amber, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Text("Queue this sighting")
            }
        }
    }

    if (showSourceSheet) {
        ModalBottomSheet(onDismissRequest = { showSourceSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
                TextButton(
                    onClick = {
                        showSourceSheet = false
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.Filled.AddAPhoto, contentDescription = null)
                    Text("  Take a photograph")
                }
                TextButton(
                    onClick = {
                        showSourceSheet = false
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Icon(Icons.Filled.PhotoLibrary, contentDescription = null)
                    // The path for action-camera footage already on the phone.
                    Text("  Import from the gallery")
                }
            }
        }
    }

    if (showPinEntry) {
        DropPinDialog(
            onDismiss = { showPinEntry = false },
            onConfirm = { position ->
                viewModel.dropPin(position)
                showPinEntry = false
            },
        )
    }
}

@Composable
private fun PhotoStrip(
    photos: List<DraftPhoto>,
    canAdd: Boolean,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    error: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Photographs  ${photos.size} of ${CaptureLimits.MAX_PHOTOS}",
            style = MaterialTheme.typography.titleMedium,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(photos.size) { index ->
                val photo = photos[index]
                Surface(shape = RoundedCornerShape(12.dp), modifier = Modifier.size(96.dp)) {
                    AsyncImage(
                        model = photo.localPath,
                        contentDescription = "Photograph ${index + 1}",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    IconButton(onClick = { onRemove(photo.id) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove photograph ${index + 1}")
                    }
                }
            }
            if (canAdd) {
                item {
                    OutlinedButton(onClick = onAdd, modifier = Modifier.size(96.dp)) {
                        Icon(Icons.Filled.AddAPhoto, contentDescription = "Add a photograph")
                    }
                }
            }
        }
        error?.let { Text(it, color = MurakaTheme.reef.rust, style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * Position is required; GPS is not.
 *
 * A diver under cloud, or on a hull that blocks the sky, may have no fix at all. Dropping
 * a pin records `manual_pin`, which researchers filter on - so the two are shown as
 * genuinely different things rather than one silently standing in for the other.
 */
@Composable
private fun PositionSection(state: CaptureUiState, onRetryFix: () -> Unit, onDropPin: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Position", style = MaterialTheme.typography.titleMedium)

        when {
            state.fix != null -> {
                val fix = state.fix
                Row(
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Readout(
                        label = if (fix.source == LocationSource.GPS) "GPS" else "Dropped pin",
                        value = String.format(
                            Locale.UK,
                            "%.5f, %.5f",
                            fix.position.lat,
                            fix.position.lon,
                        ),
                    )
                    fix.accuracyM?.let {
                        Readout(label = "Accuracy", value = "±${it.toInt()} m")
                    }
                }
            }

            state.locatingPosition -> Text(
                "Finding your position…",
                style = MaterialTheme.typography.bodyMedium,
            )

            else -> Text(
                "No position yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MurakaTheme.reef.amber,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRetryFix, enabled = !state.locatingPosition) {
                Text("Use GPS")
            }
            OutlinedButton(onClick = onDropPin) { Text("Drop a pin") }
        }
    }
}

/**
 * The diver's own impression.
 *
 * Recorded for comparison with the model and never mixed into the authoritative
 * condition - which is why it is optional and visually quiet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfAssessment(selected: Condition?, onSelect: (Condition?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Your impression (optional)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Condition.entries.forEach { condition ->
                FilterChip(
                    selected = selected == condition,
                    onClick = { onSelect(if (selected == condition) null else condition) },
                    label = {
                        Text(if (condition == Condition.HEALTHY) "Healthy" else "Bleached")
                    },
                )
            }
        }
    }
}

/**
 * Entering a coordinate by hand.
 *
 * A map picker would be nicer, but the dashboard owns the map and this app deliberately
 * does not carry one - a basemap is 67 KB of geometry and a MapLibre-equivalent
 * dependency for a screen used only when GPS has failed. Typed coordinates keep the
 * fallback available with no new dependency, and the researcher sees `manual_pin` either
 * way.
 */
@Composable
private fun DropPinDialog(onDismiss: () -> Unit, onConfirm: (Position) -> Unit) {
    var lat by remember { mutableStateOf("") }
    var lon by remember { mutableStateOf("") }
    val position = remember(lat, lon) {
        val parsedLat = lat.toDoubleOrNull()
        val parsedLon = lon.toDoubleOrNull()
        if (parsedLat != null && parsedLon != null) Position(parsedLat, parsedLon) else null
    }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Drop a pin") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Recorded as a manual pin so researchers can tell it apart from a GPS fix.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = lat,
                    onValueChange = { lat = it },
                    label = { Text("Latitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = lon,
                    onValueChange = { lon = it },
                    label = { Text("Longitude") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { position?.let(onConfirm) },
                enabled = position?.isValid == true,
            ) { Text("Use this position") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
