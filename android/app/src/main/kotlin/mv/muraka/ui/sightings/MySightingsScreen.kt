package mv.muraka.ui.sightings

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mv.muraka.core.designsystem.component.EmptyState
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.component.StatusPill
import mv.muraka.core.designsystem.component.UnassessedLatticePlaceholder
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.model.ContributorSighting
import mv.muraka.ui.common.relativeAge
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MySightingsScreen(
    viewModel: MySightingsViewModel,
    onOpenSighting: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sightings by viewModel.sightings.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("My sightings") }) },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.padding(padding).fillMaxSize(),
        ) {
            // Two different empty states, because they mean different things. Telling a
            // contributor with ninety sightings that they have none, because a chip is
            // selected off-screen, is the kind of small lie that makes an app feel broken.
            if (sightings.isEmpty() && !state.refreshing) {
                Column(modifier = Modifier.padding(16.dp)) {
                    SightingFilterBar(
                        filter = state.filter,
                        expanded = state.filtersExpanded,
                        onQueryChange = viewModel::onQueryChange,
                        onToggleExpanded = viewModel::toggleFilters,
                        onConditionChange = viewModel::onConditionChange,
                        onStatusToggle = viewModel::onStatusToggle,
                        onLocationSourceChange = viewModel::onLocationSourceChange,
                        onDateRangeChange = viewModel::onDateRangeChange,
                        onToggleSort = viewModel::toggleSort,
                        onClear = viewModel::clearFilter,
                    )

                    if (state.filter.isActive) {
                        EmptyState(
                            title = "Nothing matches",
                            body = "None of your $totalCount sighting${if (totalCount == 1) "" else "s"} " +
                                "matches this search. Clear the filter to see them all again.",
                            icon = Icons.Outlined.SearchOff,
                            actionLabel = "Clear the filter",
                            onAction = viewModel::clearFilter,
                        )
                    } else {
                        EmptyState(
                            title = "No sightings yet",
                            body = "Photograph a reef and Muraka will queue it. It uploads by itself " +
                                "when you have a connection — you can capture all day with no signal.",
                            icon = Icons.Outlined.Waves,
                        )
                    }
                }
                return@PullToRefreshBox
            }

            LazyColumn(
                // Extra room at the bottom so the floating action button does not cover
                // the last sighting - which on a short history is every sighting.
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    SightingFilterBar(
                        filter = state.filter,
                        expanded = state.filtersExpanded,
                        onQueryChange = viewModel::onQueryChange,
                        onToggleExpanded = viewModel::toggleFilters,
                        onConditionChange = viewModel::onConditionChange,
                        onStatusToggle = viewModel::onStatusToggle,
                        onLocationSourceChange = viewModel::onLocationSourceChange,
                        onDateRangeChange = viewModel::onDateRangeChange,
                        onToggleSort = viewModel::toggleSort,
                        onClear = viewModel::clearFilter,
                    )
                }

                if (state.filter.isActive) {
                    item {
                        Text(
                            text = "${sightings.size} of $totalCount",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.refreshError?.let { banner ->
                    item {
                        // A banner, not a dialogue: the cached list underneath is still
                        // perfectly useful and blocking it would be the wrong trade.
                        Text(
                            text = banner,
                            style = MaterialTheme.typography.bodySmall,
                            color = MurakaTheme.reef.amber,
                        )
                    }
                }

                items(sightings, key = { it.id }) { sighting ->
                    SightingRow(sighting = sighting, onClick = { onOpenSighting(sighting.id) })
                }
            }
        }
    }
}

@Composable
private fun SightingRow(sighting: ContributorSighting, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The lattice glyph: even at 34dp it says where the model thinks the bleaching
            // is, which a percentage alone does not.
            val prediction = sighting.server?.severity
            if (prediction != null) {
                LatticeGlyph(sighting)
            } else {
                UnassessedLatticePlaceholder()
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatusPill(sighting.displayStatus)
                    if (sighting.photosPending > 0) {
                        val plural = if (sighting.photosPending == 1) "" else "s"
                        Text(
                            text = "${sighting.photosPending} photograph$plural left",
                            style = MaterialTheme.typography.labelSmall,
                            color = MurakaTheme.reef.amber,
                        )
                    }
                }

                Readout(
                    value = String.format(
                        Locale.UK,
                        "%.4f, %.4f",
                        sighting.position.lat,
                        sighting.position.lon,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )

                Text(
                    text = buildString {
                        append(sighting.capturedAt.relativeAge())
                        // The age of the KNOWLEDGE, not of the sighting. A stale truth
                        // labelled stale is fine; a stale truth presented as current is
                        // the bug this whole design exists to prevent.
                        sighting.serverReadAt?.let { append(" · checked ${it.relativeAge()}") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                sighting.failureReason?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MurakaTheme.reef.rust,
                    )
                }
            }
        }
    }
}

/** The first photograph's lattice, or nothing when there is no prediction yet. */
@Composable
private fun LatticeGlyph(sighting: ContributorSighting) {
    val severity = sighting.server?.severity ?: return
    val description = "Severity ${(severity * 100).toInt()} percent"
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        // The list row has no patches to hand - those come with the detail response - so
        // this is the severity swatch standing in for the lattice at glyph size. The real
        // lattice is one tap away.
        mv.muraka.core.designsystem.component.SeveritySwatch(severity)
    }
}
