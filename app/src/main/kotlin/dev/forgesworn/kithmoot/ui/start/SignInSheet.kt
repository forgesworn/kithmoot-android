package dev.forgesworn.kithmoot.ui.start

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.StartState

/** "Already on Nostr? Sign in", from home's foot link (design-home-rooms.md
 *  section 4). Closes itself once signed in, same as the in-room account
 *  menu's own sign-in sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignInSheet(state: StartState, actions: AccountActions, onDismiss: () -> Unit) {
    LaunchedEffect(state.account?.pubkey) { if (state.account != null) onDismiss() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.94f).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onDismiss, enabled = !state.profileBusy) { Text("Done") }
            }
            Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
                AccountSection(state, actions, enabled = !state.busy)
            }
        }
    }
}
