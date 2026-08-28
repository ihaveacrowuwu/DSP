package mv.muraka.ui.capture

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mv.muraka.core.common.ApiError
import mv.muraka.core.common.Uuid7
import mv.muraka.core.data.photo.PhotoStore
import mv.muraka.core.domain.LocationProvider
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.domain.SyncScheduler
import mv.muraka.core.model.CaptureLimits
import mv.muraka.core.model.Condition
import mv.muraka.core.model.LocationFix
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.PhotoDraft
import mv.muraka.core.model.Position
import mv.muraka.core.model.SightingDraft
import java.time.Instant
import javax.inject.Inject

/** One photograph in the draft, already copied into app-private storage. */
data class DraftPhoto(val id: String, val localPath: String)

data class CaptureUiState(
    val photos: List<DraftPhoto> = emptyList(),
    val fix: LocationFix? = null,
    val locatingPosition: Boolean = false,
    val depthText: String = "",
    val note: String = "",
    val selfAssessment: Condition? = null,
    val submitting: Boolean = false,
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
    val queued: Boolean = false,
) {
    val canAddPhoto: Boolean get() = photos.size < CaptureLimits.MAX_PHOTOS

    /**
     * A position is required; GPS is not.
     *
     * If there is no fix the contributor drops a pin, and the sighting records
     * `manual_pin` - researchers filter on that difference, so it is stored rather than
     * quietly treated as equivalent.
     */
    val canSubmit: Boolean
        get() = !submitting && photos.size >= CaptureLimits.MIN_PHOTOS && fix != null

    val remainingNoteCharacters: Int get() = CaptureLimits.MAX_NOTE_LENGTH - note.length
}

/**
 * Capturing a sighting.
 *
 * The whole flow must complete in under 60 seconds and 8 taps (NFR6), which is why the
 * position is requested the moment the screen opens rather than being a step, and why
 * depth, note and self-assessment are all optional and collapsed by default.
 *
 * Nothing here waits for the network. [submit] returns as soon as the row and its photo
 * files are durably on disk, and the drain loop does the rest - which is what makes
 * capture work in aeroplane mode (NFR7, FR3).
 */
@HiltViewModel
class CaptureViewModel @Inject constructor(
    private val sightingRepository: SightingRepository,
    private val locationProvider: LocationProvider,
    private val photoStore: PhotoStore,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /** The sighting's own id, minted once so retries and photo rows share it. */
    private val sightingId: String = Uuid7.generateString()

    /** Device capture time, taken when the screen opens rather than when submit is tapped. */
    private val capturedAt: Instant = Instant.now()

    fun requestPosition() {
        if (_uiState.value.locatingPosition) return
        _uiState.update { it.copy(locatingPosition = true) }

        viewModelScope.launch {
            val fix = locationProvider.currentFix()
            _uiState.update { state ->
                state.copy(
                    locatingPosition = false,
                    // Never overwrite a pin the contributor placed deliberately with a
                    // fix that arrived late.
                    fix = if (state.fix?.source == LocationSource.MANUAL_PIN) state.fix else fix,
                    message = if (fix == null && state.fix == null) {
                        "No position fix yet. You can drop a pin instead."
                    } else {
                        state.message
                    },
                )
            }
        }
    }

    /** The fallback when there is no fix: a coordinate the contributor chose. */
    fun dropPin(position: Position) = _uiState.update {
        it.copy(
            fix = LocationFix(position = position, source = LocationSource.MANUAL_PIN),
            message = null,
        )
    }

    fun addPhoto(source: Uri) {
        if (!_uiState.value.canAddPhoto) return

        viewModelScope.launch {
            val photoId = Uuid7.generateString()
            // Copied into app-private storage NOW: a gallery URI can be revoked, and the
            // file behind it deleted, long before the outbox drains.
            val file = photoStore.store(photoId, source)
            if (file == null) {
                _uiState.update { it.copy(message = "That image could not be read.") }
                return@launch
            }
            _uiState.update {
                it.copy(photos = it.photos + DraftPhoto(photoId, file.absolutePath), message = null)
            }
        }
    }

    /** The CameraX path, where the bytes are already in memory. */
    fun addPhoto(bytes: ByteArray) {
        if (!_uiState.value.canAddPhoto) return

        viewModelScope.launch {
            val photoId = Uuid7.generateString()
            val file = photoStore.store(photoId, bytes)
            if (file == null) {
                _uiState.update { it.copy(message = "That photograph could not be saved.") }
                return@launch
            }
            _uiState.update {
                it.copy(photos = it.photos + DraftPhoto(photoId, file.absolutePath), message = null)
            }
        }
    }

    fun removePhoto(photoId: String) {
        viewModelScope.launch {
            photoStore.delete(photoId)
            _uiState.update { it.copy(photos = it.photos.filterNot { photo -> photo.id == photoId }) }
        }
    }

    fun onDepthChange(value: String) {
        // Digits and one decimal point only: a text field that accepts letters produces a
        // 422 the contributor cannot understand.
        if (value.isNotEmpty() && value.toDoubleOrNull() == null) return
        _uiState.update { it.copy(depthText = value) }
    }

    fun onNoteChange(value: String) {
        if (value.length > CaptureLimits.MAX_NOTE_LENGTH) return
        _uiState.update { it.copy(note = value) }
    }

    fun onSelfAssessmentChange(condition: Condition?) = _uiState.update { it.copy(selfAssessment = condition) }

    fun submit() {
        val state = _uiState.value
        val fix = state.fix ?: return
        if (!state.canSubmit) return

        _uiState.update { it.copy(submitting = true, fieldErrors = emptyMap(), message = null) }

        viewModelScope.launch {
            val draft = SightingDraft(
                id = sightingId,
                fix = fix,
                capturedAt = capturedAt,
                depthM = state.depthText.toDoubleOrNull(),
                note = state.note.takeIf { it.isNotBlank() },
                selfAssessedCondition = state.selfAssessment,
                photos = state.photos.map { PhotoDraft(it.id, it.localPath) },
            )

            sightingRepository.capture(draft).fold(
                onSuccess = {
                    _uiState.update { it.copy(submitting = false, queued = true) }
                    // Expedited: the contributor is watching and the work is short. If
                    // there is no connection this simply waits, which is the point.
                    syncScheduler.requestSync(expedited = true)
                },
                onFailure = { error ->
                    _uiState.update { current ->
                        when (error) {
                            is ApiError.Validation ->
                                current.copy(submitting = false, fieldErrors = error.fields)

                            else -> current.copy(
                                submitting = false,
                                message = "Could not save this sighting to the device.",
                            )
                        }
                    }
                },
            )
        }
    }
}
