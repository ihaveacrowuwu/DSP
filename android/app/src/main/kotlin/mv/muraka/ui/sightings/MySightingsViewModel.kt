package mv.muraka.ui.sightings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mv.muraka.core.common.ApiError
import mv.muraka.core.domain.SightingRepository
import mv.muraka.core.model.Condition
import mv.muraka.core.model.ContributorSighting
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.SightingDisplayStatus
import mv.muraka.core.model.SightingFilter
import java.time.Instant
import javax.inject.Inject

data class MySightingsUiState(
    val refreshing: Boolean = false,
    /** Shown as a non-blocking banner: the cached list stays on screen underneath. */
    val refreshError: String? = null,
    val filter: SightingFilter = SightingFilter(),
    /** True when the filter panel is open. */
    val filtersExpanded: Boolean = false,
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

    private val _uiState = MutableStateFlow(MySightingsUiState())
    val uiState: StateFlow<MySightingsUiState> = _uiState.asStateFlow()

    /** Everything the device knows about, unfiltered. */
    private val allSightings: StateFlow<List<ContributorSighting>> =
        sightingRepository.observeMySightings()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * What the list shows: the merged history with the filter applied.
     *
     * Filtered here rather than in the query, so it keeps working with no connection — this
     * screen's whole purpose is to work offline (NFR7). See `SightingFilter`.
     */
    val sightings: StateFlow<List<ContributorSighting>> =
        combine(allSightings, _uiState.map { it.filter }.distinctUntilChanged()) { all, filter ->
            filter.apply(all)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** How many the contributor has in total, so the interface can distinguish
     *  "nothing matches your filter" from "you have no sightings". */
    val totalCount: StateFlow<Int> = allSightings
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    init {
        refresh()
    }

    // ── Search and filtering ────────────────────────────────────────────────

    fun onQueryChange(query: String) = _uiState.update { it.copy(filter = it.filter.copy(query = query)) }

    fun toggleFilters() = _uiState.update { it.copy(filtersExpanded = !it.filtersExpanded) }

    fun onConditionChange(condition: Condition?) =
        _uiState.update { it.copy(filter = it.filter.copy(condition = condition)) }

    fun onStatusToggle(status: SightingDisplayStatus) = _uiState.update { it.copy(filter = it.filter.toggling(status)) }

    fun onLocationSourceChange(source: LocationSource?) =
        _uiState.update { it.copy(filter = it.filter.copy(locationSource = source)) }

    fun onDateRangeChange(from: Instant?, to: Instant?) =
        _uiState.update { it.copy(filter = it.filter.copy(from = from, to = to)) }

    fun toggleSort() = _uiState.update { it.copy(filter = it.filter.copy(sort = it.filter.sort.next())) }

    fun clearFilter() = _uiState.update { it.copy(filter = it.filter.cleared()) }

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
