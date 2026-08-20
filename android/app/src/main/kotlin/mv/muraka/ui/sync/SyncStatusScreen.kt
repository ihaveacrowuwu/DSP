package mv.muraka.ui.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mv.muraka.core.designsystem.component.EmptyState
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.domain.QueuedItem
import mv.muraka.core.model.OutboxState
import mv.muraka.ui.common.relativeAge

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusScreen(viewModel: SyncStatusViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Sync") }) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = viewModel::syncNow,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            if (state.items.isEmpty()) {
                EmptyState(
                    title = "Everything is delivered",
                    // Careful wording: this says the QUEUE is empty, which the client
                    // does know, rather than that everything synced, which only the
                    // server can say. See D21.
                    body = "Nothing is waiting to upload. Statuses on each sighting come " +
                        "from the server, not from this device.",
                    icon = Icons.Outlined.CloudDone,
                )
                return@PullToRefreshBox
            }

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!state.online) {
                    item {
                        Text(
                            text = "Offline. Everything below is safe on this device and will " +
                                "upload by itself when you have a connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MurakaTheme.reef.amber,
                        )
                    }
                }

                items(state.items, key = { it.sightingId }) { item ->
                    QueueRow(
                        item = item,
                        onRetry = { viewModel.retry(item.sightingId) },
                        onRetrySmaller = { viewModel.retrySmaller(item.sightingId) },
                        onDiscard = { viewModel.discard(item.sightingId) },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: QueuedItem, onRetry: () -> Unit, onRetrySmaller: () -> Unit, onDiscard: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Captured ${item.capturedAt.relativeAge()}",
                style = MaterialTheme.typography.titleSmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Readout(
                    label = "Photographs",
                    value = "${item.photosSent}/${item.photosTotal}",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (item.attempts > 0) {
                    Readout(
                        label = "Attempts",
                        value = "${item.attempts}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (item.state == OutboxState.SENDING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            item.lastError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MurakaTheme.reef.rust,
                )
            }

            // A failed row must never be a dead end. `sync-protocol.md` is explicit: never
            // retry silently forever, and always give the contributor something to do.
            if (item.state == OutboxState.FAILED) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onRetry) { Text("Retry") }
                    // The only thing that helps a 413, which retrying unchanged cannot.
                    TextButton(onClick = onRetrySmaller) { Text("Retry smaller") }
                    TextButton(onClick = onDiscard) { Text("Discard") }
                }
            } else {
                item.nextAttemptAt?.let {
                    Text(
                        text = "Next attempt ${it.relativeAge()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
