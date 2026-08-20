package mv.muraka.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import mv.muraka.core.domain.NetworkMonitor
import mv.muraka.core.domain.OutboxRepository
import mv.muraka.core.domain.QueuedItem
import mv.muraka.core.domain.SyncScheduler
import javax.inject.Inject

data class SyncUiState(
    val items: List<QueuedItem> = emptyList(),
    val online: Boolean = true,
    val syncing: Boolean = false,
)

/**
 * The queue, made visible.
 *
 * This screen exists because `sync-protocol.md` insists pending work must be visible: a
 * silent queue is how a contributor's reef data goes missing without anyone noticing. It
 * is also where a terminally failed row gets its way out — retry, retry smaller, or
 * discard — rather than sitting in a failure the contributor can see but not act on.
 */
@HiltViewModel
class SyncStatusViewModel @Inject constructor(
    private val outboxRepository: OutboxRepository,
    private val syncScheduler: SyncScheduler,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    val uiState: StateFlow<SyncUiState> = combine(
        outboxRepository.observeQueue(),
        networkMonitor.isOnline,
        syncScheduler.isSyncing,
    ) { items, online, syncing ->
        SyncUiState(items = items, online = online, syncing = syncing)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncUiState())

    /** Pull-to-refresh: one of the five triggers the protocol asks for. */
    fun syncNow() = syncScheduler.requestSync(expedited = true)

    fun retry(sightingId: String) {
        viewModelScope.launch { outboxRepository.retry(sightingId) }
    }

    /** The way out of a `413`, which retrying unchanged can never fix. */
    fun retrySmaller(sightingId: String) {
        viewModelScope.launch { outboxRepository.retryWithSmallerPhotos(sightingId) }
    }

    fun discard(sightingId: String) {
        viewModelScope.launch { outboxRepository.discard(sightingId) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
