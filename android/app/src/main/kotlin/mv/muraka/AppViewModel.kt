package mv.muraka

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import mv.muraka.core.common.SessionEvent
import mv.muraka.core.common.SessionEvents
import mv.muraka.core.domain.AuthRepository
import mv.muraka.core.domain.NetworkMonitor
import mv.muraka.core.domain.OutboxRepository
import mv.muraka.core.domain.SyncScheduler
import mv.muraka.core.model.SessionState
import javax.inject.Inject

/**
 * Application-level state: who is signed in, and how much is still owed to the server.
 *
 * It also owns two of the five sync triggers. Connectivity returning is here rather than
 * in a screen because it must fire whichever screen the contributor happens to be on —
 * and, more to the point, whether or not they are looking.
 */
@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    outboxRepository: OutboxRepository,
    networkMonitor: NetworkMonitor,
    sessionEvents: SessionEvents,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val sessionState: StateFlow<SessionState> = authRepository.sessionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SessionState.Unknown)

    /**
     * How many sightings are still undelivered.
     *
     * Shown as a badge that never goes away while work is outstanding. This is the one
     * count in the app that comes from local rows — legitimately, because it counts what
     * has *not* reached the server. Contribution totals are a different question and come
     * from `GET /v1/me` (D21).
     */
    val pendingCount: StateFlow<Int> = outboxRepository.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    init {
        // Trigger: connectivity becoming available.
        combine(networkMonitor.isOnline, sessionState) { online, session ->
            online && session is SessionState.SignedIn
        }
            .distinctUntilChanged()
            .filter { it }
            .onEach { syncScheduler.requestSync() }
            .launchIn(viewModelScope)

        // A refresh that failed for good ends the session. The queue survives it — see
        // SessionEvents.RefreshFailed.
        sessionEvents.events
            .onEach { event ->
                when (event) {
                    SessionEvent.RefreshFailed, SessionEvent.AccountDisabled -> Unit
                }
            }
            .launchIn(viewModelScope)
    }

    fun requestSync() = syncScheduler.requestSync()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
