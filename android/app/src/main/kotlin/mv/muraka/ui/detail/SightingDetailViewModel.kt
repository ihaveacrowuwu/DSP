package mv.muraka.ui.detail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mv.muraka.core.domain.AppearanceRepository
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.domain.SightingWithDetail
import mv.muraka.ui.navigation.Destination
import javax.inject.Inject

data class DetailUiState(val refreshing: Boolean = false, val message: String? = null)

/**
 * One sighting: the photographs, the model's reading of each, and any expert verdict.
 *
 * Refreshes on open, which is the read-back that turns "Checking…" into whatever the
 * server actually says.
 */
@HiltViewModel
class SightingDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val appearanceRepository: AppearanceRepository,
    private val sightingRepository: SightingRepository,
) : ViewModel() {

    private val sightingId: String =
        checkNotNull(savedStateHandle[Destination.SightingDetail.ARG_SIGHTING_ID]) {
            "SightingDetail was opened without an id"
        }

    /**
     * Whether the patch lattice is drawn over the photograph.
     *
     * A remembered preference rather than per-screen state: somebody comparing several
     * sightings against the raw photographs should not have to turn it off once per sighting.
     */
    val showPatchGrid: StateFlow<Boolean> = appearanceRepository.showPatchGrid
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), true)

    fun onShowPatchGridChange(visible: Boolean) {
        viewModelScope.launch { appearanceRepository.setShowPatchGrid(visible) }
    }

    val sighting: StateFlow<SightingWithDetail?> = sightingRepository.observeSighting(sightingId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _uiState = MutableStateFlow(DetailUiState())
    val uiState: StateFlow<DetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true, message = null) }

        viewModelScope.launch {
            sightingRepository.refreshSighting(sightingId).fold(
                onSuccess = { _uiState.update { it.copy(refreshing = false) } },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            message = "Could not reach Muraka. Showing what was last read.",
                        )
                    }
                },
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
