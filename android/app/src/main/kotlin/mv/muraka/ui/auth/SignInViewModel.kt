package mv.muraka.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mv.muraka.core.common.ApiError
import mv.muraka.core.domain.AuthRepository
import mv.muraka.core.domain.SyncScheduler
import javax.inject.Inject

/**
 * One state type for the screen, so impossible combinations cannot be represented.
 *
 * There is no separate `isLoading` / `error` / `success` triple: a screen that is
 * submitting cannot also be showing a field error, and modelling it as one object is what
 * stops the two drifting apart.
 */
data class SignInUiState(
    val mode: Mode = Mode.SIGN_IN,
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val submitting: Boolean = false,
    /** Field name to problem, straight from the server's `422`. */
    val fieldErrors: Map<String, String> = emptyMap(),
    val message: String? = null,
) {
    enum class Mode { SIGN_IN, REGISTER }

    val canSubmit: Boolean
        get() = !submitting &&
            email.isNotBlank() &&
            password.isNotBlank() &&
            (mode == Mode.SIGN_IN || displayName.isNotBlank())
}

/**
 * The only screen in the app that needs connectivity (NFR7).
 *
 * Everything else works on a boat; this cannot, and says so plainly rather than queueing
 * a sign-in that could never succeed.
 */
@HiltViewModel
class SignInViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, message = null) }

    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, message = null) }

    fun onDisplayNameChange(value: String) = _uiState.update { it.copy(displayName = value, message = null) }

    fun toggleMode() = _uiState.update {
        it.copy(
            mode = if (it.mode == SignInUiState.Mode.SIGN_IN) {
                SignInUiState.Mode.REGISTER
            } else {
                SignInUiState.Mode.SIGN_IN
            },
            fieldErrors = emptyMap(),
            message = null,
        )
    }

    fun submit() {
        val state = _uiState.value
        if (!state.canSubmit) return

        _uiState.update { it.copy(submitting = true, fieldErrors = emptyMap(), message = null) }

        viewModelScope.launch {
            val result = when (state.mode) {
                SignInUiState.Mode.SIGN_IN -> authRepository.signIn(state.email, state.password)
                SignInUiState.Mode.REGISTER ->
                    authRepository.register(state.email, state.password, state.displayName)
            }

            result.fold(
                onSuccess = {
                    _uiState.update { it.copy(submitting = false) }
                    // Anything queued by this contributor before they were signed out is
                    // now sendable again. Scenario 12: sign back in as diver A and A's
                    // sightings upload, attributed to A.
                    syncScheduler.requestSync(expedited = true)
                },
                onFailure = { error -> _uiState.update { it.withError(error) } },
            )
        }
    }

    private fun SignInUiState.withError(error: Throwable): SignInUiState = when (error) {
        is ApiError.Validation -> copy(submitting = false, fieldErrors = error.fields)

        is ApiError.InvalidCredentials ->
            copy(submitting = false, message = "Email or password is incorrect.")

        is ApiError.EmailTaken ->
            copy(submitting = false, message = "An account with that email already exists.")

        is ApiError.AccountDisabled ->
            copy(submitting = false, message = "This account has been suspended. Contact an administrator.")

        // Named explicitly rather than folded into a generic failure: "you are offline" is
        // the one message that tells a contributor to stop retrying and wait, and this is
        // the only screen where being offline is genuinely a problem.
        is ApiError.Offline -> copy(
            submitting = false,
            message = "No connection. Signing in is the only thing Muraka needs the network for — " +
                "everything else works offline.",
        )

        else -> copy(submitting = false, message = "Could not reach Muraka. Try again in a moment.")
    }
}
