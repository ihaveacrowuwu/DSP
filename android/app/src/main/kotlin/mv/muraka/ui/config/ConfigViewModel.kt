package mv.muraka.ui.config

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
import mv.muraka.core.domain.AuthRepository
import mv.muraka.core.model.Profile
import mv.muraka.core.model.ThemePreference
import javax.inject.Inject

data class ConfigUiState(val refreshing: Boolean = false, val message: String? = null, val deleting: Boolean = false)

/**
 * The account, the contributor's totals, and the app's settings.
 *
 * The totals come from `GET /v1/me` and are never computed from local rows. A client-side
 * tally drifts the moment anything is rejected, verified or anonymised, and the number
 * the contributor sees would then disagree with the dashboard — D21 again.
 */
@HiltViewModel
class ConfigViewModel @Inject constructor(
    private val appearanceRepository: AppearanceRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** The current appearance choice, so the control can show which one is on. */
    val themePreference: StateFlow<ThemePreference> = appearanceRepository.themePreference
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ThemePreference.DEFAULT)

    fun onThemePreferenceChange(preference: ThemePreference) {
        viewModelScope.launch { appearanceRepository.setThemePreference(preference) }
    }

    val profile: StateFlow<Profile?> = authRepository.observeProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _uiState = MutableStateFlow(ConfigUiState())
    val uiState: StateFlow<ConfigUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_uiState.value.refreshing) return
        _uiState.update { it.copy(refreshing = true, message = null) }

        viewModelScope.launch {
            authRepository.refreshProfile().fold(
                onSuccess = { _uiState.update { it.copy(refreshing = false) } },
                onFailure = {
                    _uiState.update {
                        it.copy(
                            refreshing = false,
                            // Totals from the last successful read stay on screen; they
                            // are simply labelled as what they are.
                            message = "Could not refresh your totals. Showing the last known figures.",
                        )
                    }
                },
            )
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }

    /**
     * Anonymises the account.
     *
     * NFR15: the app must disclose that sightings survive as scientific record under an
     * anonymous owner **before** the contributor confirms — which the dialogue in
     * `ProfileScreen` does, in those words.
     */
    fun deleteAccount() {
        _uiState.update { it.copy(deleting = true) }
        viewModelScope.launch {
            authRepository.deleteAccount().fold(
                onSuccess = { _uiState.update { it.copy(deleting = false) } },
                onFailure = {
                    _uiState.update {
                        it.copy(deleting = false, message = "Could not delete the account. Try again.")
                    }
                },
            )
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
