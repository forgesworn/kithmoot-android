package dev.forgesworn.kithmoot.ui.start

import dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState

/** Shared projects (account), as a full screen from the "Projects" button
 *  beside the Rooms heading (design-home-rooms.md Q7). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(state: StartState, actions: ProjectActions, onBack: () -> Unit,
    roomToAdd: String? = null, onSignIn: () -> Unit = {},
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Projects", style = cappedTitleStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { if (state.account != null) TextButton(actions.refresh, enabled = !state.projects.syncing && !state.projectsBusy) { Text("Sync") } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // A room-open error started here (an admitted-but-wrong room, a
            // stale directory entry) must be visible here too, not only on home.
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            ProjectsPanel(state, actions, showHeader = false, roomToAdd = roomToAdd, onSignIn = onSignIn)
        }
    }
}
