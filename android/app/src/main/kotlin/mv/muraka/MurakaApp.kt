package mv.muraka

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mv.muraka.core.designsystem.component.LoadingState
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.model.SessionState
import mv.muraka.core.model.ThemePreference
import mv.muraka.ui.auth.SignInScreen
import mv.muraka.ui.navigation.MurakaNavHost

/**
 * Root composable.
 *
 * One decision only: signed in or not. Everything else is inside [MurakaNavHost].
 *
 * Note what happens on sign-out - the app returns here and shows sign-in, and **the
 * outbox is untouched**. Queued sightings belong to the account that captured them and
 * wait for that account to come back, which is what stops one diver's reef data uploading
 * under whoever borrows the phone next.
 */
@Composable
fun MurakaApp(viewModel: AppViewModel = hiltViewModel()) {
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val theme by viewModel.themePreference.collectAsStateWithLifecycle()

    // Drain on foreground: one of the five triggers the sync protocol asks for.
    LaunchedEffect(session) {
        if (session is SessionState.SignedIn) viewModel.requestSync()
    }

    MurakaTheme(
        // The contributor's choice wins; SYSTEM defers to the device, which is the default
        // and what NFR14 is really about.
        darkTheme = when (theme) {
            ThemePreference.SYSTEM -> isSystemInDarkTheme()
            ThemePreference.LIGHT -> false
            ThemePreference.DARK -> true
        },
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (session) {
                SessionState.Unknown -> LoadingState(message = "Opening Muraka")
                SessionState.SignedOut -> SignInScreen(viewModel = hiltViewModel())
                is SessionState.SignedIn -> MurakaNavHost(pendingCount = pendingCount)
            }
        }
    }
}
