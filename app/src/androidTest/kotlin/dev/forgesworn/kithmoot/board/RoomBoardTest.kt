package dev.forgesworn.kithmoot.board

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.protocol.deriveRoom
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.ROOM_BOARD_CHANNEL
import dev.forgesworn.kithmoot.session.decodeChatEvent
import dev.forgesworn.kithmoot.storage.RoomBoardVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A room's board on this phone, end to end: the pinned page in a real WebView, the app's half of its port, this
 * phone's sealed copy, and a relay in memory. A card typed on one phone reaches the room as a member's board
 * event; a card written on another phone arrives in the view; the last to arrive reads the board after its writers
 * have gone; and a phone with no relay at all opens its board from its own copy.
 */
class RoomBoardTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val secret = Entropy.bytes(32)
    private val room = deriveRoom(secret)
    private val executors = CopyOnWriteArrayList<java.util.concurrent.ExecutorService>()
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()
    private val vaults = CopyOnWriteArrayList<RoomBoardVault>()

    @After fun tidy() {
        scopes.forEach { it.cancel() }
        executors.forEach { it.shutdownNow() }
        vaults.forEach { runCatching { it.reset() } }
    }

    /** A relay that keeps everything and replays it, then says it has, in the collector's own order. */
    private class MemoryRelay {
        val published = CopyOnWriteArrayList<NostrEvent>()
        private val live = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 256)
        var offline = false
        fun transport(): RoomTransport = object : RoomTransport {
            override fun publish(event: NostrEvent) { published += event; live.tryEmit(event) }
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
                if (offline) return false
                publish(event); return true
            }
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = subscribeReplayed(filters) {}
            override fun subscribeReplayed(filters: List<Filter>, onReplayComplete: () -> Unit): Flow<NostrEvent> = flow {
                if (offline) awaitCancellation()
                val d = filters.flatMap { it.tags["#d"].orEmpty() }.toSet()
                for (event in published.toList()) if (event.tagValue("d") in d) emit(event)
                onReplayComplete()
                emitAll(live.filter { it.tagValue("d") in d })
            }
        }
    }

    private class Member(val participantSk: ByteArray, val deviceSk: ByteArray, val credential: NostrEvent) {
        val participant: String = Schnorr.publicKeyHex(participantSk)
    }
    private fun member(): Member {
        val participantSk = Entropy.bytes(32)
        val deviceSk = Entropy.bytes(32)
        return Member(participantSk, deviceSk, createDeviceCredential(participantSk, Schnorr.publicKeyHex(deviceSk), room.roomId,
            expiresAt = System.currentTimeMillis() / 1000 + 3600))
    }

    private fun board(who: Member, transport: RoomTransport, vault: RoomBoardVault = RoomBoardVault(context, room.roomId, who.participant).also { vaults += it }): RoomBoard {
        val executor = Executors.newSingleThreadExecutor().also { executors += it }
        val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher()).also { scopes += it }
        val board = RoomBoard(context, scope) { send ->
            RoomBoardBridge(room.roomId, who.participant, who.deviceSk, { who.credential }, transport, vault, scope, send,
                EpochKeys(0, room.roomId, room.roomKey))
        }
        board.members(listOf(who.participant to "Member"))
        board.open()
        return board
    }

    private fun evaluate(board: RoomBoard, script: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            val view = board.view.value
            if (view == null) done.countDown() else view.evaluateJavascript(script) { result = it ?: ""; done.countDown() }
        }
        assertTrue("The page did not answer", done.await(10, TimeUnit.SECONDS))
        return result
    }

    private fun waitFor(what: String, seconds: Long = 30, check: () -> Boolean) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!check()) {
            assertTrue("Timed out: $what", System.nanoTime() < until)
            Thread.sleep(100)
        }
    }

    private val frame = "document.getElementById('view').contentDocument"
    /** The titles of the cards the view has drawn. A page that is not on screen has no size, so the browser leaves
     *  out of its rendered text cards that are not in focus; the cards are there all the same. */
    private fun cards(board: RoomBoard): List<String> = org.json.JSONArray(evaluate(board,
        "JSON.stringify([...($frame?.querySelectorAll('.card-title') ?? [])].map(e => e.textContent))")
        .let { runCatching { org.json.JSONTokener(it).nextValue() as String }.getOrDefault("[]") })
        .let { array -> List(array.length()) { array.getString(it) } }
    private fun shared(board: RoomBoard) = board.status.value == RoomBoardStatus(ready = true, pending = false)
    private fun addCard(board: RoomBoard, title: String) {
        waitFor("the view") { evaluate(board, "!!$frame?.getElementById('add-todo')") == "true" }
        evaluate(board, """(() => { const i = $frame.getElementById('add-todo'); i.value = ${org.json.JSONObject.quote(title)};
            i.dispatchEvent(new Event('input', { bubbles: true })); i.form.requestSubmit(); return true })()""")
    }
    private fun close(board: RoomBoard) = runBlocking { board.close() }

    @Test fun a_card_written_on_one_phone_reaches_the_room_and_the_last_to_arrive_reads_the_board_from_it() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val relay = MemoryRelay()
        val ada = member()
        val first = board(ada, relay.transport())
        waitFor("Ada's board to be read") { shared(first) }
        addCard(first, "Written on the phone")
        waitFor("the card to reach the room") {
            relay.published.any { decodeChatEvent(it, room.roomId, room.roomKey, System.currentTimeMillis() / 1000, channel = ROOM_BOARD_CHANNEL)?.participant == ada.participant }
        }
        waitFor("Ada's card to be shared") { shared(first) }

        // Bob, on another phone in the same room, writes one of his own while Ada is there.
        val bob = member()
        val second = board(bob, relay.transport())
        waitFor("Bob's board to be read, with Ada's card") { shared(second) && "Written on the phone" in cards(second) }
        addCard(second, "Book the hall")
        waitFor("Bob's card in Ada's view") { "Book the hall" in cards(first) }
        waitFor("both shared") { shared(first) && shared(second) }
        close(second)
        close(first)

        // Carol arrives after both have gone, and reads the board from the room alone.
        val carol = board(member(), relay.transport())
        waitFor("Carol's board to be read") { shared(carol) }
        waitFor("both cards for Carol") { cards(carol).containsAll(listOf("Written on the phone", "Book the hall")) }
        close(carol)

        // Ada, back with no relay at all, opens hers from this phone's copy.
        relay.offline = true
        val again = board(ada, relay.transport())
        waitFor("Ada's copy") { cards(again).containsAll(listOf("Written on the phone", "Book the hall")) }
        close(again)
    }

    @Test fun a_board_still_closing_after_its_room_was_removed_writes_nothing_back() {
        Assume.assumeTrue("Disposable emulator only", Build.HARDWARE in setOf("ranchu", "goldfish"))
        val who = member()
        val vault = RoomBoardVault.reopen(context, room.roomId, who.participant).also { vaults += it }
        val id = vault.add(byteArrayOf(1, 2, 3))
        assertTrue(vault.read().first.single().first == id)
        vault.reset()
        val refused = runCatching { vault.add(byteArrayOf(4, 5, 6)) }
        assertTrue("A removed room's copy takes nothing", refused.isFailure)
        assertTrue("and nothing is left", RoomBoardVault(context, room.roomId, who.participant).read().first.isEmpty())
        // Opened afresh, the room keeps its board again.
        val again = RoomBoardVault.reopen(context, room.roomId, who.participant)
        again.add(byteArrayOf(7, 8, 9))
        assertTrue(again.read().first.size == 1)
    }
}
