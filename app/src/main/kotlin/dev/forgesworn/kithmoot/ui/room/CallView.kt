package dev.forgesworn.kithmoot.ui.room

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.forgesworn.kithmoot.ui.RoomState
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.VideoTrack

/**
 * Whether anything on the call is a moving picture. The controls only get
 * out of the way of something worth looking at.
 */
internal fun videoShowing(state: RoomState, videos: Map<String, VideoTrack>): Boolean =
    state.tiles.any { tile -> tile.videos.any { videos["${it.device}|${it.role}"] != null } }

/** Who [ActiveSpeaker] has settled on, re-read a few times a second. */
@Composable
internal fun rememberActiveSpeaker(speaking: Set<String>, present: List<String>): String? {
    val tracker = remember { ActiveSpeaker() }
    val latestSpeaking by rememberUpdatedState(speaking)
    val latestPresent by rememberUpdatedState(present)
    var current by remember { mutableStateOf(tracker.update(speaking, present, android.os.SystemClock.elapsedRealtime())) }
    // Driven by changes to who is speaking. The hold is measured in time, so
    // it ticks as well, but only while a challenger is waiting: a quiet call
    // leaves nothing running and nothing to recompose.
    LaunchedEffect(speaking, present) {
        current = tracker.update(latestSpeaking, latestPresent, android.os.SystemClock.elapsedRealtime())
        while (tracker.pending) {
            delay(250)
            current = tracker.update(latestSpeaking, latestPresent, android.os.SystemClock.elapsedRealtime())
        }
    }
    return current?.takeIf { it in present }
}

/**
 * The call itself: the arrangement [arrangeCall] chose, drawn edge to edge,
 * with your own picture floating over it.
 *
 * @param alone the invitation, drawn over your own picture while nobody
 *   else is here.
 */
@Composable
internal fun CallView(
    state: RoomState,
    videos: Map<String, VideoTrack>,
    eglBase: EglBase?,
    chrome: CallChrome,
    preferGrid: Boolean,
    swapped: Boolean,
    onSwap: () -> Unit,
    hideSelf: Boolean,
    onExpandScreen: (SharedScreen) -> Unit,
    onSetVolume: (String, Float) -> Unit,
    alone: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = CallTileContext(
        videos = videos,
        eglBase = eglBase,
        profiles = if (state.profilesEnabled) state.profiles else emptyMap(),
        connectionStates = state.mediaConnections,
        mirrorSelf = state.mirrorSelf,
        selfDevice = state.selfDevice,
        speaking = state.speaking,
        shareMarks = state.shareMarks,
    )
    val present = state.tiles.filter { !it.isSelf }.map { it.participant }
    val speaker = rememberActiveSpeaker(state.speaking, present)
    // A strip tile tapped in a large call stays on the stage until tapped
    // again or they leave, whoever is talking.
    var pinned by remember { mutableStateOf<String?>(null) }
    val pin = pinned?.takeIf { it in present }
    val arrangement = arrangeCall(state.tiles, pin ?: speaker, preferGrid, swapped, hideSelf)
    val byId = state.tiles.associateBy { it.participant }
    // With the bars showing, the header and the controls already keep the
    // call clear of the system bars; with them gone, the call runs under the
    // status and navigation bars and only its labels and tiles step in.
    val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    val insets = if (chrome.visible) safe.only(WindowInsetsSides.Horizontal) else safe
    val none = WindowInsets(0, 0, 0, 0)

    @Composable
    fun Tile(item: CallItem, modifier: Modifier, large: Boolean = false, fullBleed: Boolean = false, onTap: (() -> Unit)? = chrome::toggle, tapLabel: String? = "Show or hide call controls") {
        val tile = byId[item.participant] ?: return
        CallTile(
            tile = tile,
            item = item,
            context = context,
            modifier = modifier,
            large = large,
            shape = if (fullBleed) RectangleShape else androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            onTap = onTap,
            onTapLabel = tapLabel,
            onExpandScreen = { track -> onExpandScreen(SharedScreen(tile.participant, track.device)) },
            onSetVolume = onSetVolume,
            insets = if (fullBleed) insets else none,
        )
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight
        when (arrangement.mode) {
            CallLayoutMode.ALONE -> Box(Modifier.fillMaxSize()) {
                arrangement.stage?.let { Tile(it, Modifier.fillMaxSize(), large = true, fullBleed = true) }
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(insets).verticalScroll(rememberScrollState()).padding(16.dp),
                    contentAlignment = Alignment.TopCenter,
                ) { Box(Modifier.widthIn(max = 560.dp)) { alone() } }
            }
            CallLayoutMode.ONE_TO_ONE ->
                arrangement.stage?.let { Tile(it, Modifier.fillMaxSize(), large = true, fullBleed = true) }
            CallLayoutMode.GRID -> EqualGrid(arrangement.grid, landscape, Modifier.fillMaxSize().windowInsetsPadding(insets).padding(6.dp)) { item, tileModifier ->
                Tile(item, tileModifier)
            }
            CallLayoutMode.SPEAKER, CallLayoutMode.SHARE -> {
                val stage = arrangement.stage
                // In a large call a strip tile is pinned to the stage by a
                // tap; beside a share, a tap is just a tap on the call.
                val stripTap: (CallItem) -> (() -> Unit) = { item ->
                    if (arrangement.mode == CallLayoutMode.SPEAKER) ({ pinned = item.participant }) else chrome::toggle
                }
                val stripLabel = if (arrangement.mode == CallLayoutMode.SPEAKER) "Show large" else "Show or hide call controls"
                val stageTap: () -> Unit = if (pin != null) ({ pinned = null; chrome.show() }) else chrome::toggle
                if (landscape) Row(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    stage?.let { Tile(it, Modifier.weight(1f).fillMaxHeight(), large = true, onTap = stageTap) }
                    Column(Modifier.width(STRIP_LONG).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        arrangement.strip.forEach { Tile(it, Modifier.size(STRIP_LONG, STRIP_SHORT), onTap = stripTap(it), tapLabel = stripLabel) }
                    }
                } else Column(Modifier.fillMaxSize().windowInsetsPadding(insets).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    stage?.let { Tile(it, Modifier.weight(1f).fillMaxWidth(), large = true, onTap = stageTap) }
                    if (arrangement.strip.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        arrangement.strip.forEach { Tile(it, Modifier.size(STRIP_SHORT, STRIP_LONG), onTap = stripTap(it), tapLabel = stripLabel) }
                    }
                }
            }
        }
        arrangement.floating?.let { item ->
            val tile = byId[item.participant] ?: return@let
            FloatingPicture(landscape, Modifier.windowInsetsPadding(insets).padding(bottom = 4.dp)) { placed ->
                CallTile(
                    tile = tile,
                    item = item,
                    context = context,
                    modifier = placed,
                    overlay = true,
                    // Only a one-to-one call has two pictures to swap.
                    onTap = if (arrangement.mode == CallLayoutMode.ONE_TO_ONE) onSwap else chrome::toggle,
                    onTapLabel = if (arrangement.mode == CallLayoutMode.ONE_TO_ONE) "Swap pictures" else "Show or hide call controls",
                    onSetVolume = onSetVolume,
                )
            }
        }
    }
}

/** Equal tiles that share the space, or scroll once there are too many to share it. */
@Composable
private fun EqualGrid(
    items: List<CallItem>,
    landscape: Boolean,
    modifier: Modifier,
    tile: @Composable (CallItem, Modifier) -> Unit,
) {
    val (columns, rows) = gridShape(items.size, landscape)
    if (items.size > MAX_SHARED_GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns.coerceAtMost(if (landscape) 4 else 2)),
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.participant }) { tile(it, Modifier.fillMaxWidth().aspectRatio(if (landscape) 16f / 10f else 3f / 4f)) }
        }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (row in 0 until rows) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (column in 0 until columns) {
                    val index = row * columns + column
                    if (index < items.size) tile(items[index], Modifier.weight(1f).fillMaxHeight())
                    else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * What picture-in-picture shows: somebody else's shared screen, else whoever
 * is talking, else the one other person. Never yourself unless you are alone,
 * which is the one thing you do not need a window to see.
 */
@Composable
internal fun PipCall(state: RoomState, videos: Map<String, VideoTrack>, eglBase: EglBase?, modifier: Modifier = Modifier) {
    val present = state.tiles.filter { !it.isSelf }.map { it.participant }
    val speaker = rememberActiveSpeaker(state.speaking, present)
    val share = state.tiles.filter { !it.isSelf }.firstNotNullOfOrNull { tile ->
        tile.videos.firstOrNull { it.role == dev.forgesworn.kithmoot.session.Roles.SCREEN }?.let { CallItem(tile.participant, it) }
    }
    val item = share ?: (speaker ?: present.firstOrNull() ?: state.self?.participant)?.let { CallItem(it) }
    val tile = item?.let { chosen -> state.tiles.firstOrNull { it.participant == chosen.participant } }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        if (item != null && tile != null) CallTile(
            tile = tile,
            item = item,
            context = CallTileContext(videos, eglBase, if (state.profilesEnabled) state.profiles else emptyMap(), state.mediaConnections,
                state.mirrorSelf, state.selfDevice, state.speaking, state.shareMarks),
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            showLabel = false,
            showExpand = false,
        )
    }
}

private val STRIP_SHORT = 104.dp
private val STRIP_LONG = 140.dp

/** Past this many equal tiles, they get a fixed shape and scroll. */
private const val MAX_SHARED_GRID = 6
