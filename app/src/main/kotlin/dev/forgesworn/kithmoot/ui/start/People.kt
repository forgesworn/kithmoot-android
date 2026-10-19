package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState

/**
 * People: the signed-in account's contacts as a destination, where the
 * account menu's Contacts used to be (design-calm-shell 6.1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleDestination(
    state: StartState,
    contacts: SignetContactActions,
    insets: WindowInsets = TopAppBarDefaults.windowInsets,
    accountButton: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("People", style = dev.forgesworn.kithmoot.ui.theme.cappedTitleStyle(), maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() }) },
            actions = { accountButton?.invoke() },
            windowInsets = insets,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SignetContactsPage(state, contacts, inRoom = false) {}
        }
    }
}
