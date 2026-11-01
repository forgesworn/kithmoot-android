package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.session.CHAT_CATCH_UP_SKEW_SECONDS
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.QuietTransport
import dev.forgesworn.kithmoot.session.UNSENT_AUTO_RETRY_LIMIT_SECONDS
import dev.forgesworn.kithmoot.support.FakeSocket
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A relay that reconnects is asked for chat again from the newest chat it
 * delivered, less the catch-up skew, rather than the whole 30 days, and
 * nothing is lost by it: not a message published late, not one a relay held
 * that another did not, not one cut off by a drop mid-replay.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayPoolCatchUpTest {
    private val one = "wss://one.example"
    private val two = "wss://two.example"
    private val start = 1_800_000_000L
    private val room = "ee".repeat(32)
    private var serial = 0

    private fun TestScope.nowSeconds() = start + currentTime / 1_000

    private fun chat(createdAt: Long, kind: Int = KIND_CHAT) = NostrEvent(
        kind = kind, createdAt = createdAt, tags = listOf(listOf("d", room)), content = "opaque",
        pubkey = "aa".repeat(32), id = (++serial).toString(16).padStart(64, '0'), sig = "cc".repeat(64),
    )

    private fun TestScope.pool(urls: List<String>, sockets: FakeSocketFactory) =
        RelayPool(urls, sockets, backgroundScope, now = { start * 1_000 + currentTime }, random = Random(1)).also { it.start(); runCurrent() }

    private fun TestScope.chatFilters(): () -> List<Filter> =
        { listOf(Filter(kinds = listOf(KIND_CHAT), tags = mapOf("#d" to listOf(room)), since = nowSeconds() - CHAT_RETENTION_SECONDS)) }

    /** The filters of the last REQ [socket] was sent for [id]. */
    private fun FakeSocket.lastRequest(id: String): List<JsonObject> = Json.parseToJsonElement(
        sent.last { it.startsWith("[\"REQ\",\"$id\"") }).jsonArray.drop(2).map { it.jsonObject }

    private fun JsonObject.since(): Long? = this["since"]?.jsonPrimitive?.long
    private fun JsonObject.limit(): Int? = this["limit"]?.jsonPrimitive?.int

    /** What a relay applies to a stored or live event: `since` against its created_at. */
    private fun List<JsonObject>.admits(event: NostrEvent) = any { (it.since() ?: Long.MIN_VALUE) <= event.createdAt }

    private fun TestScope.reconnect(sockets: FakeSocketFactory, url: String): FakeSocket {
        sockets.forUrl(url).last().drop()
        advanceTimeBy(5_000); runCurrent()
        return sockets.forUrl(url).last().also { it.open(); runCurrent() }
    }

    @Test fun `the chat skew covers the unsent cap, a relay's hold and clock skew, with margin`() {
        val needed = UNSENT_AUTO_RETRY_LIMIT_SECONDS + RelayGate.MAX_HOLD_MS / 1000 + 5 * 60
        assertEquals(24L * 3600 + 35 * 60, needed)
        assertEquals(26L * 3600, CHAT_CATCH_UP_SKEW_SECONDS)
        assertTrue(CHAT_CATCH_UP_SKEW_SECONDS - needed >= 3600, "At least an hour over")
    }

    @Test fun `a relay read to its end is asked again from its newest chat less the skew, with a live guard`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        val received = mutableListOf<NostrEvent>()
        backgroundScope.launch { pool.subscribeCatchingUp(chatFilters(), CHAT_CATCH_UP_SKEW_SECONDS).collect { received += it } }
        runCurrent()
        val first = sockets.opened.single()
        val id = first.requestedSubscriptions().single()
        val full = first.lastRequest(id).single()
        assertEquals(nowSeconds() - CHAT_RETENTION_SECONDS, full.since(), "The first REQ asks for the whole window")
        assertNull(full.limit())

        val newest = chat(nowSeconds() - 60)
        first.deliverEvent(id, chat(nowSeconds() - 20L * 24 * 3600))
        first.deliverEvent(id, newest)
        first.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()

        val again = reconnect(sockets, one).lastRequest(id)
        assertEquals(2, again.size)
        assertEquals(newest.createdAt - CHAT_CATCH_UP_SKEW_SECONDS, again[0].since())
        assertEquals(newest.createdAt - 26L * 3600, again[0].since(), "26 hours behind the newest chat")
        assertNull(again[0].limit(), "The gap since the drop is fetched whole")
        assertEquals(nowSeconds() - CHAT_RETENTION_SECONDS, again[1].since())
        assertEquals(1, again[1].limit(), "The full filter only guards live delivery")

        // Within the skew: published by the relay after the reconnect, it is still asked for.
        val late = chat(newest.createdAt - CHAT_CATCH_UP_SKEW_SECONDS + 3600)
        assertTrue(listOf(again[0]).admits(late))
        // Later than the skew: the guard still lets a relay push it live.
        val veryLate = chat(newest.createdAt - CHAT_CATCH_UP_SKEW_SECONDS - 3600)
        assertTrue(again.admits(veryLate))
        val socket = sockets.forUrl(one).last()
        socket.deliverEvent(id, late)
        socket.deliverEvent(id, veryLate)
        runCurrent()
        assertTrue(received.containsAll(listOf(late, veryLate)))
    }

    @Test fun `a drop before EOSE leaves the relay's cursor where it was`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        backgroundScope.launch { pool.subscribeCatchingUp(chatFilters(), CHAT_CATCH_UP_SKEW_SECONDS).collect { } }
        runCurrent()
        val id = sockets.opened.single().requestedSubscriptions().single()
        // A relay replays newest first: the drop may cut off everything older.
        sockets.opened.single().deliverEvent(id, chat(nowSeconds() - 60))
        runCurrent()
        val again = reconnect(sockets, one).lastRequest(id).single()
        assertEquals(nowSeconds() - CHAT_RETENTION_SECONDS, again.since())

        // Read to its end this time, then a live event after EOSE moves the cursor at once.
        val socket = sockets.forUrl(one).last()
        socket.deliverRaw("[\"EOSE\",\"$id\"]")
        val live = chat(nowSeconds() - 5)
        socket.deliverEvent(id, live)
        runCurrent()
        assertEquals(live.createdAt - CHAT_CATCH_UP_SKEW_SECONDS, reconnect(sockets, one).lastRequest(id).first().since())
    }

    @Test fun `our own unacknowledged send does not move a relay's cursor`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        backgroundScope.launch { pool.subscribeCatchingUp(chatFilters(), CHAT_CATCH_UP_SKEW_SECONDS).collect { } }
        runCurrent()
        val socket = sockets.opened.single()
        val id = socket.requestedSubscriptions().single()
        val theirs = chat(nowSeconds() - 3_600)
        socket.deliverEvent(id, theirs)
        socket.deliverRaw("[\"EOSE\",\"$id\"]")
        pool.publish(chat(nowSeconds()))
        runCurrent()
        assertTrue(socket.publishedFrames().isNotEmpty())
        assertEquals(theirs.createdAt - CHAT_CATCH_UP_SKEW_SECONDS, reconnect(sockets, one).lastRequest(id).first().since())
    }

    @Test fun `a relay first met after another has delivered is asked for the whole window`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one, two), sockets)
        sockets.forUrl(one).single().open(); runCurrent()
        backgroundScope.launch { pool.subscribeCatchingUp(chatFilters(), CHAT_CATCH_UP_SKEW_SECONDS).collect { } }
        runCurrent()
        val first = sockets.forUrl(one).single()
        val id = first.requestedSubscriptions().single()
        val newest = chat(nowSeconds() - 60)
        first.deliverEvent(id, newest)
        first.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()
        // A relay is not a mirror: what only it holds must still be fetched in full.
        val late = sockets.forUrl(two).single().also { it.open(); runCurrent() }
        assertEquals(nowSeconds() - CHAT_RETENTION_SECONDS, late.lastRequest(id).single().since())
        // Nor does the first relay's cursor move for what the second delivered.
        late.deliverEvent(id, chat(nowSeconds()))
        late.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()
        assertEquals(newest.createdAt - CHAT_CATCH_UP_SKEW_SECONDS, reconnect(sockets, one).lastRequest(id).first().since())
    }

    @Test fun `a future-dated event cannot push a cursor past this phone's clock`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        backgroundScope.launch { pool.subscribeCatchingUp(chatFilters(), CHAT_CATCH_UP_SKEW_SECONDS).collect { } }
        runCurrent()
        val socket = sockets.opened.single()
        val id = socket.requestedSubscriptions().single()
        val at = nowSeconds()
        socket.deliverEvent(id, chat(at + 365L * 24 * 3600))
        socket.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()
        assertEquals(at - CHAT_CATCH_UP_SKEW_SECONDS, reconnect(sockets, one).lastRequest(id).first().since())
    }

    @Test fun `a plain subscription still re-sends exactly what it sent`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        backgroundScope.launch { pool.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT), since = 5))).collect { } }
        runCurrent()
        val socket = sockets.opened.single()
        val id = socket.requestedSubscriptions().single()
        socket.deliverEvent(id, chat(nowSeconds()))
        socket.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()
        val before = socket.sent.single { it.startsWith("[\"REQ\"") }
        assertEquals(before, reconnect(sockets, one).sent.single { it.startsWith("[\"REQ\"") })
    }

    @Test fun `a quiet room's wrap stream is asked again from the newest wrap less the jitter skew`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = pool(listOf(one), sockets)
        sockets.openAll()
        val member = Schnorr.publicKeyHex(ByteArray(32) { 3 })
        val quiet = QuietTransport(pool, ByteArray(32) { 9 }, member, listOf(member), 0, backgroundScope,
            now = { nowSeconds() }, ticking = false, slotOffset = { 0 })
        backgroundScope.launch { quiet.subscribe(listOf(Filter(kinds = listOf(KIND_CHAT)))).collect { } }
        runCurrent()
        val socket = sockets.opened.single()
        // The paged backfill is a stored query of its own; the live stream is the subscription.
        val id = socket.requestedSubscriptions().first { !it.startsWith("km-stored-") }
        val lookback = nowSeconds() - QuietTransport.HISTORY_SECONDS - RoomDrops.CREATED_AT_JITTER
        assertEquals(lookback, socket.lastRequest(id).single().since())
        val wrap = chat(nowSeconds() - 3_600, RoomDrops.GIFT_WRAP_KIND)
        socket.deliverEvent(id, wrap)
        socket.deliverRaw("[\"EOSE\",\"$id\"]")
        runCurrent()

        val again = reconnect(sockets, one).lastRequest(id)
        assertEquals(wrap.createdAt - QuietTransport.CATCH_UP_SKEW_SECONDS, again[0].since())
        assertEquals(lookback, again[1].since())
        assertEquals(1, again[1].limit())
        quiet.stop()
    }
}
