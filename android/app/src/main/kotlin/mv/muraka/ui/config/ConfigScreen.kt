package mv.muraka.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import mv.muraka.core.designsystem.component.LoadingState
import mv.muraka.core.designsystem.component.Readout
import mv.muraka.core.designsystem.component.ReadoutRow
import mv.muraka.core.designsystem.component.SectionCard
import mv.muraka.core.designsystem.theme.MurakaTheme
import mv.muraka.core.designsystem.theme.ReefSpacing
import mv.muraka.core.model.Profile
import mv.muraka.core.model.ThemePreference

/**
 * The account and the app's settings.
 *
 * Four titled cards in a deliberate order: who you are, what you have contributed, how the
 * app looks, and - kept last and kept apart - the two actions that end something. Grouping
 * matters more here than anywhere else in the app, because "sign out" and "delete my account"
 * sitting in an undifferentiated column of controls is how somebody taps the wrong one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(viewModel: ConfigViewModel, modifier: Modifier = Modifier) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val theme by viewModel.themePreference.collectAsStateWithLifecycle()
    var confirmingDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Config") }) },
    ) { padding ->
        val current = profile
        if (current == null) {
            LoadingState(modifier = Modifier.padding(padding), message = "Loading your account")
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ReefSpacing.Lg)
                .padding(bottom = ReefSpacing.ListBottom),
            verticalArrangement = Arrangement.spacedBy(ReefSpacing.Md),
        ) {
            AccountCard(current)
            ContributionsCard(
                profile = current,
                refreshing = state.refreshing,
                message = state.message,
                onRefresh = viewModel::refresh,
            )
            AppearanceCard(selected = theme, onSelect = viewModel::onThemePreferenceChange)
            SessionCard(
                onSignOut = viewModel::signOut,
                onDelete = { confirmingDelete = true },
                deleting = state.deleting,
            )
        }
    }

    if (confirmingDelete) {
        DeleteAccountDialog(
            onDismiss = { confirmingDelete = false },
            onConfirm = {
                confirmingDelete = false
                viewModel.deleteAccount()
            },
        )
    }
}

@Composable
private fun AccountCard(profile: Profile) {
    SectionCard(title = "Account") {
        Text(profile.user.displayName, style = MaterialTheme.typography.titleLarge)
        Text(
            text = profile.user.email,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ContributionsCard(profile: Profile, refreshing: Boolean, message: String?, onRefresh: () -> Unit) {
    SectionCard(title = "Your contributions") {
        ReadoutRow {
            // Every one of these comes from GET /v1/me. Counting local rows would drift the
            // moment anything is verified or rejected (D21).
            Readout(label = "Total", value = "${profile.stats.total}")
            Readout(label = "Verified", value = "${profile.stats.verified}")
            Readout(label = "Pending", value = "${profile.stats.pending}")
            Readout(label = "Rejected", value = "${profile.stats.rejected}")
        }
        Text(
            text = "Counted by the server, not by this device.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MurakaTheme.reef.amber)
        }
        OutlinedButton(
            onClick = onRefresh,
            enabled = !refreshing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Refresh totals") }
    }
}

/**
 * The appearance toggle.
 *
 * A Material 3 single-choice segmented button row, which is the M3 component for a small set
 * of mutually exclusive options where all of them should be visible at once. "System" is the
 * default and stays first, since it is what most people should leave it on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceCard(selected: ThemePreference, onSelect: (ThemePreference) -> Unit) {
    SectionCard(title = "Appearance") {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThemePreference.entries.forEachIndexed { index, preference ->
                SegmentedButton(
                    selected = selected == preference,
                    onClick = { onSelect(preference) },
                    shape = SegmentedButtonDefaults.itemShape(
                        index = index,
                        count = ThemePreference.entries.size,
                    ),
                    icon = {
                        Icon(
                            imageVector = when (preference) {
                                ThemePreference.SYSTEM -> Icons.Outlined.Contrast
                                ThemePreference.LIGHT -> Icons.Outlined.LightMode
                                ThemePreference.DARK -> Icons.Outlined.DarkMode
                            },
                            contentDescription = null,
                            modifier = Modifier.size(SegmentedButtonDefaults.IconSize),
                        )
                    },
                    label = { Text(preference.label) },
                )
            }
        }
        Text(
            text = when (selected) {
                ThemePreference.SYSTEM -> "Following your device setting."
                ThemePreference.LIGHT -> "Always light, whatever your device is set to."
                ThemePreference.DARK -> "Always dark, whatever your device is set to."
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Signing out, and deleting the account.
 *
 * Deliberately the last card, and deliberately the only one holding actions that end
 * something. The two are separated by their own explanation rather than sitting side by side,
 * because they are not the same size of decision.
 */
@Composable
private fun SessionCard(onSignOut: () -> Unit, onDelete: () -> Unit, deleting: Boolean) {
    SectionCard(title = "Session") {
        OutlinedButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out")
        }
        Text(
            // Reassurance that matters on a shared boat phone.
            text = "Signing out keeps anything still waiting to upload. It will be sent when " +
                "you sign back in.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = onDelete, enabled = !deleting) {
                Text("Delete my account", color = MurakaTheme.reef.rust)
            }
        }
    }
}

@Composable
private fun DeleteAccountDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete your account?") },
        text = {
            // NFR15, stated BEFORE the confirmation rather than after it. The sightings are
            // scientific record; what is deleted is the link to the person, not the science.
            Text(
                "Your sightings will not be deleted. They stay in the scientific record " +
                    "under an anonymous contributor, so the reef data researchers have " +
                    "already used remains valid.\n\n" +
                    "What is removed is the link between those sightings and you: your " +
                    "name, your email, and your account. This cannot be undone.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete and anonymise", color = MurakaTheme.reef.rust)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep my account") } },
    )
}
