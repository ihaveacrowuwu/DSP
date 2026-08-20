package mv.muraka.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mv.muraka.core.designsystem.component.LoadingState
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.theme.MurakaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(viewModel: ProfileViewModel, modifier: Modifier = Modifier) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Profile") }) },
    ) { padding ->
        val current = profile
        if (current == null) {
            LoadingState(modifier = Modifier.padding(padding), message = "Loading your profile")
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(current.user.displayName, style = MaterialTheme.typography.headlineSmall)
            Text(
                current.user.email,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Your contributions", style = MaterialTheme.typography.titleMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        // Every one of these comes from GET /v1/me. Counting local rows
                        // would drift the moment anything is verified or rejected.
                        Readout(label = "Total", value = "${current.stats.total}")
                        Readout(label = "Verified", value = "${current.stats.verified}")
                        Readout(label = "Pending", value = "${current.stats.pending}")
                        Readout(label = "Rejected", value = "${current.stats.rejected}")
                    }
                    Text(
                        text = "Counted by the server, not by this device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            state.message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MurakaTheme.reef.amber)
            }

            OutlinedButton(
                onClick = viewModel::refresh,
                enabled = !state.refreshing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Refresh totals") }

            HorizontalDivider()

            OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text("Sign out")
            }
            Text(
                // Reassurance that matters on a shared boat phone: signing out does not
                // throw away work that has not been delivered.
                text = "Signing out keeps anything still waiting to upload. It will be sent " +
                    "when you sign back in.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = { confirmingDelete = true },
                enabled = !state.deleting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Delete my account", color = MurakaTheme.reef.rust)
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("Delete your account?") },
            text = {
                // NFR15, stated BEFORE the confirmation rather than after it. The
                // sightings are scientific record; what is deleted is the link to the
                // person, not the science.
                Text(
                    "Your sightings will not be deleted. They stay in the scientific record " +
                        "under an anonymous contributor, so the reef data researchers have " +
                        "already used remains valid.\n\n" +
                        "What is removed is the link between those sightings and you: your " +
                        "name, your email, and your account. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingDelete = false
                        viewModel.deleteAccount()
                    },
                ) { Text("Delete and anonymise", color = MurakaTheme.reef.rust) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text("Keep my account") }
            },
        )
    }
}
