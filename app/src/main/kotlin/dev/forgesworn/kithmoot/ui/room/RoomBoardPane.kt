package dev.forgesworn.kithmoot.ui.room

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
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
 * Girnel's board on the room's document, which this phone keeps in step
 * (`board/RoomBoard.kt`). System Back returns to full chat.
 */
@Composable
fun RoomBoardPane(board: RoomBoard, onBack: () -> Unit, onInvite: () -> Unit, invites: SharedFlow<Unit>,
    /** A card's title on its way to the chat from Discuss in chat: the chat comes back to take it. */
    discussed: StateFlow<String?>) {
    BackHandler(onBack = onBack)
    LaunchedEffect(board) { invites.collect { onInvite() } }
    val discussing by discussed.collectAsState()
    LaunchedEffect(discussing) { if (discussing != null) onBack() }
    val view by board.view.collectAsState()
    val page = view
    if (page == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Opening the board…") }
    } else {
        // Keep the room's page when returning to chat, including any
        // unfinished text in its editor.
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
