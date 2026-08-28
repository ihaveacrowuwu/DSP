package mv.muraka.ui.sightings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mv.muraka.core.model.Condition
import mv.muraka.core.model.LocationSource
import mv.muraka.core.model.SightingDisplayStatus
import mv.muraka.core.model.SightingFilter
import java.time.Instant

/**
 * Search and filtering for the contributor's own history.
 *
 * Everything here filters **local** data, so it works with no connection - see
 * `SightingFilter`. That is why there is no loading state and no debounce: filtering a few
 * hundred rows in memory is instant, and a debounce would only add lag to something that has
 * none.
 *
 * The filter row is collapsed by default. A contributor opening their history wants to see
 * their sightings, not a control panel; the chips are one tap away for the times they are
 * looking for something specific.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingFilterBar(
    filter: SightingFilter,
    expanded: Boolean,
    onQueryChange: (String) -> Unit,
    onToggleExpanded: () -> Unit,
    onConditionChange: (Condition?) -> Unit,
    onStatusToggle: (SightingDisplayStatus) -> Unit,
    onLocationSourceChange: (LocationSource?) -> Unit,
    onDateRangeChange: (Instant?, Instant?) -> Unit,
    onToggleSort: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDatePicker by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                // Short enough not to wrap at the largest font size. What is searchable is
                // explained by the results, not by the placeholder.
                placeholder = { Text("Search sightings") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear the search")
                        }
                    }
                },
                singleLine = true,
            )

            // The badge is what makes a collapsed filter honest: a contributor who has
            // filtered and scrolled away must still be able to tell that what they are
            // looking at is not everything.
            BadgedBox(
                badge = {
                    if (filter.activeCriteriaCount > 0) {
                        Badge { Text("${filter.activeCriteriaCount}") }
                    }
                },
            ) {
                IconButton(
                    onClick = onToggleExpanded,
                    modifier = Modifier.semantics {
                        contentDescription = if (filter.activeCriteriaCount > 0) {
                            "Filters, ${filter.activeCriteriaCount} active"
                        } else {
                            "Filters"
                        }
                    },
                ) {
                    Icon(Icons.Filled.FilterList, contentDescription = null)
                }
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterRow("Condition") {
                    Condition.entries.forEach { condition ->
                        FilterChip(
                            selected = filter.condition == condition,
                            onClick = {
                                onConditionChange(if (filter.condition == condition) null else condition)
                            },
                            label = {
                                Text(if (condition == Condition.HEALTHY) "Healthy" else "Bleached")
                            },
                        )
                    }
                }

                FilterRow("Status") {
                    // Filtering on what the contributor SEES, so the labels here are the
                    // same contract strings the rows show.
                    FILTERABLE_STATUSES.forEach { status ->
                        FilterChip(
                            selected = status in filter.statuses,
                            onClick = { onStatusToggle(status) },
                            label = { Text(status.label) },
                        )
                    }
                }

                FilterRow("Position") {
                    LocationSource.entries.forEach { source ->
                        FilterChip(
                            selected = filter.locationSource == source,
                            onClick = {
                                onLocationSourceChange(
                                    if (filter.locationSource == source) null else source,
                                )
                            },
                            label = {
                                Text(if (source == LocationSource.GPS) "GPS fix" else "Dropped pin")
                            },
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(
                        onClick = { showDatePicker = true },
                        label = { Text(dateRangeLabel(filter)) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.DateRange,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                    AssistChip(
                        onClick = onToggleSort,
                        label = { Text(filter.sort.label) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.SwapVert,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                    )
                    if (filter.isActive) {
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                }
            }
        }
    }

    if (showDatePicker) {
        DateRangePickerDialog(
            filter = filter,
            onDismiss = { showDatePicker = false },
            onConfirm = { from, to ->
                onDateRangeChange(from, to)
                showDatePicker = false
            },
        )
    }
}

/** A labelled, horizontally scrolling row of chips. */
@Composable
private fun FilterRow(label: String, chips: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            // Scrollable rather than wrapped: the chip count per row is fixed and known, and
            // wrapping would make the panel's height jump as chips are selected.
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            chips()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangePickerDialog(
    filter: SightingFilter,
    onDismiss: () -> Unit,
    onConfirm: (Instant?, Instant?) -> Unit,
) {
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filter.from?.toEpochMilli(),
        initialSelectedEndDateMillis = filter.to?.toEpochMilli(),
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        state.selectedStartDateMillis?.let(Instant::ofEpochMilli),
                        // The picker returns midnight; the bound is inclusive, so push it to
                        // the end of the chosen day or a sighting captured that afternoon
                        // falls outside its own date range.
                        state.selectedEndDateMillis
                            ?.let { Instant.ofEpochMilli(it + END_OF_DAY_MILLIS) },
                    )
                },
            ) { Text("Apply") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onConfirm(null, null) }) { Text("Any date") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    ) {
        DateRangePicker(state = state, title = { Text("Captured between", Modifier.padding(16.dp)) })
    }
}

private fun dateRangeLabel(filter: SightingFilter): String {
    // Bound locally: `filter.from` is a property of a class in another module, so Kotlin
    // cannot smart-cast it after a null check.
    val from = filter.from
    val to = filter.to

    return when {
        from == null && to == null -> "Any date"
        from != null && to != null -> "${from.toLocalDate()} - ${to.toLocalDate()}"
        from != null -> "From ${from.toLocalDate()}"
        else -> "Until ${to?.toLocalDate()}"
    }
}

private fun Instant.toLocalDate() = java.time.LocalDate.ofInstant(this, java.time.ZoneId.systemDefault()).toString()

/**
 * One day, minus a millisecond.
 *
 * The range picker returns midnight for the end date. Used as-is, a sighting captured that
 * afternoon falls outside its own selected day - which reads as the filter being broken.
 */
private const val END_OF_DAY_MILLIS = 24 * 60 * 60 * 1000L - 1

/**
 * The statuses worth offering as filters.
 *
 * `CHECKING` is deliberately absent: it is a transient "we do not know yet" that resolves
 * within a request, so a chip for it would be a filter that empties itself while the
 * contributor is looking at it.
 */
private val FILTERABLE_STATUSES = listOf(
    SightingDisplayStatus.WAITING_TO_UPLOAD,
    SightingDisplayStatus.PHOTOS_PENDING,
    SightingDisplayStatus.ANALYSING,
    SightingDisplayStatus.AWAITING_REVIEW,
    SightingDisplayStatus.VERIFIED_BY_EXPERT,
    SightingDisplayStatus.NOT_USABLE,
    SightingDisplayStatus.FAILED,
)
