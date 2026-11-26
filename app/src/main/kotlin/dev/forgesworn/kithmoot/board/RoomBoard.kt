package dev.forgesworn.kithmoot.board

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import dev.forgesworn.kithmoot.protocol.EpochKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One room's board on this phone, for as long as the room is open, shown or not: the page that runs it
 * ([RoomBoardPage]) and the app's half of its port ([RoomBoardBridge]), kept together. On the web the board runs
 * for the whole time a room is open, so an edit made offline goes once the network is back and the board is
 * handed to a new epoch's stream; this does the same.
 *
 * Should the page's renderer go, a new page opens from this phone's copy, a few times at most. [bridge] makes
 * the bridge for each page, from the room as it is then.
 */
class RoomBoard(
    private val context: Context,
    private val scope: CoroutineScope,
    private val bridge: (send: (String) -> Unit) -> RoomBoardBridge,
) {
    private val main = Handler(Looper.getMainLooper())
    private var page: RoomBoardPage? = null
    @Volatile private var current: RoomBoardBridge? = null
    private var watching: Job? = null
    private var members: List<Pair<String, String>> = emptyList()
    private var restarts = 0
    @Volatile private var closed = false
    private val _status = MutableStateFlow<RoomBoardStatus?>(null)
    private val _view = MutableStateFlow<WebView?>(null)

    /** Where the board stands, as its page last said; null until it has. */
    val status: StateFlow<RoomBoardStatus?> = _status
    /** The page to show when the board is on screen, or null while there is none. Main thread. */
    val view: StateFlow<WebView?> = _view

    fun open() = main.post { start() }

    /** The room has moved to [epoch]. */
    fun rekey(epoch: EpochKeys) { current?.rekey(epoch) }

    fun members(list: List<Pair<String, String>>) {
        main.post { members = list }
        current?.members(list)
    }

    /** Let the page finish with its copy, then take it away. */
    suspend fun close() {
        if (closed) return
        closed = true
        val last = current
        last?.close()
        if (last != null) withTimeoutOrNull(5_000) { last.awaitClosed() }
        last?.abandon()
        main.post { stop() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun start() {
        if (closed || page != null) return
        lateinit var made: RoomBoardPage
        val half = bridge { raw -> main.post { if (page === made) made.post(raw) } }
        half.members(members)
        made = RoomBoardPage(context, object : RoomBoardPage.Listener {
            override fun onMessage(message: String) { if (page === made) half.receive(message) }
            override fun onGone() {
                if (page !== made) return
                half.abandon()
                page = null
                current = null
                _view.value = null
                if (!closed && restarts++ < MAX_RESTARTS) main.postDelayed({ start() }, 1_000L * restarts)
            }
        })
        page = made
        current = half
        watching?.cancel()
        watching = scope.launch { half.status.collect { if (it != null) _status.value = it } }
        _view.value = made.view
        made.open()
    }

    private fun stop() {
        watching?.cancel()
        page?.destroy()
        page = null
        current = null
        _view.value = null
    }

    private companion object { const val MAX_RESTARTS = 3 }
}
