package dev.forgesworn.kithmoot.ui.room

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.forgesworn.kithmoot.board.RoomBoard
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The room's own board, in place of the chat: Girnel's whole board screen, on the room's document, which this
 * phone keeps in step (`board/RoomBoard.kt`). The board says where it stands itself; this adds the way back to
 * the chat, which a phone has no rail to offer, as the web's does.
 */
@Composable
fun RoomBoardPane(board: RoomBoard, roomName: String, onBack: () -> Unit, onInvite: () -> Unit, invites: SharedFlow<Unit>,
    /** A card's title on its way to the chat from Discuss in chat: the chat comes back to take it. */
    discussed: StateFlow<String?>) {
    BackHandler(onBack = onBack)
    LaunchedEffect(board) { invites.collect { onInvite() } }
    val discussing by discussed.collectAsState()
    LaunchedEffect(discussing) { if (discussing != null) onBack() }
    val view by board.view.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Board · $roomName", style = MaterialTheme.typography.titleSmall, maxLines = 1)
            TextButton(onClick = onBack, modifier = Modifier.semantics { testTag = "roomBoardBack" }) { Text("Back to chat") }
        }
        HorizontalDivider()
        val page = view
        if (page == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening the board…") }
        } else {
            // The page outlives the pane: it is the room's for as long as the room is open, so it is moved in and
            // out of this frame rather than made again.
            AndroidView(modifier = Modifier.fillMaxSize().semantics { testTag = "roomBoard" },
                factory = { context -> FrameLayout(context) },
                update = { frame ->
                    if (page.parent !== frame) {
                        (page.parent as? ViewGroup)?.removeView(page)
                        frame.removeAllViews()
                        frame.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                    }
                },
                onRelease = { frame -> frame.removeAllViews() })
        }
    }
}
