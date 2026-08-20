package mv.muraka.ui.sightings

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
import mv.muraka.core.common.ApiError
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.model.ContributorSighting
import javax.inject.Inject

data class MySightingsUiState(
    val refreshing: Boolean = false,
    /** Shown as a non-blocking banner: the cached list stays on screen underneath. */
    val refreshError: String? = null,
)

/**
 * The contributor's own history.
 *
 * Offline-first: the list comes from local state and is shown immediately, cached or not.
 * A refresh that fails leaves it exactly as it was and says so in a banner — never a
 * blank screen and never a spinner over data the app already has.
 */
@HiltViewModel
class MySightingsViewModel @Inject constructor(private val sightingRepository: SightingRepository) : ViewModel() {

    val sightings: StateFlow<List<ContributorSighting>> = sightingRepository.observeMySightings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _uiState = MutableStateFlow(MySightingsUiState())
    val uiState: StateFlow<MySightingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    /**
     * Also runs reconciliation implicitly: opening "My sightings" is one of the moments
     * the sync protocol asks the client to ask the server what it really has.
     */
    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true, refreshError = null) }

        viewModelScope.launch {
            sightingRepository.refreshMySightings().fold(
                onSuccess = { _uiState.update { it.copy(refreshing = false) } },
                onFailure = { error ->
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            refreshError = when (error) {
                                is ApiError.Offline ->
                                    "Offline. Showing what was last read from the server."

                                else -> "Could not reach Muraka. Showing the last known state."
                            },
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
