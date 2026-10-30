package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.protocol.KIND_CALL_BELL
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.RelayGate
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundFeedTest {
    private val now = 1_800_000_000L

    private fun event(kind: Int, d: String, createdAt: Long = now, pubkey: String = "aa".repeat(32), id: String = "%064x".format(kind.toLong() * 7 + d.hashCode())) =
        NostrEvent(kind = kind, createdAt = createdAt, tags = listOf(listOf("d", d)), content = "x", pubkey = pubkey, id = id, sig = "cc".repeat(64))

    private fun room(i: Int, bell: Boolean = true, chat: Boolean = true, rekey: Boolean = true, since: Long = now - 300) = FeedInterest(
        roomId = "room$i",
        bellTags = if (bell) setOf("bell$i-a", "bell$i-b") else emptySet(),
        chatTags = if (chat) setOf("epoch$i", "past$i") else emptySet(),
        chatSince = since,
        rekeyAuthor = if (rekey) "author$i" else null,
        rekeyTag = if (rekey) "room$i" else null,
    )

    @Test fun `every room's bell, chat and rekey asks become one filter of each kind`() {
        val filters = feedFilters(listOf(room(1, since = now - 100), room(2, rekey = false, since = now - 900), room(3, chat = false)), now)
        assertEquals(3, filters.size)
        val (bell, chat, rekey) = filters
        assertEquals(listOf(KIND_CALL_BELL), bell.kinds)
        assertEquals(setOf("bell1-a", "bell1-b", "bell2-a", "bell2-b", "bell3-a", "bell3-b"), bell.tags.getValue("#d").toSet())
        assertEquals(now - 120, bell.since)
        assertEquals(listOf(KIND_CHAT), chat.kinds)
        assertEquals(setOf("epoch1", "past1", "epoch2", "past2"), chat.tags.getValue("#d").toSet())
        assertEquals(now - 900, chat.since, "From the room furthest behind")
        assertEquals(listOf(KIND_ROOM_REKEY), rekey.kinds)
        assertEquals(setOf("author1", "author3"), rekey.authors!!.toSet())
        assertEquals(setOf("room1", "room3"), rekey.tags.getValue("#d").toSet())
    }

    @Test fun `a room that only rings asks only for bells`() {
        val filters = feedFilters(listOf(room(1, chat = false, rekey = false)), now)
        assertEquals(listOf(listOf(KIND_CALL_BELL)), filters.map { it.kinds })
    }

    @Test fun `events go to the room they belong to and no other`() {
        val rooms = listOf(room(1, since = now - 100), room(2, since = now - 900))
        assertEquals(listOf("room2" to FeedKind.BELL), feedRoute(event(KIND_CALL_BELL, "bell2-b"), rooms))
        assertEquals(listOf("room1" to FeedKind.CHAT), feedRoute(event(KIND_CHAT, "EPOCH1"), rooms), "Epoch ids compare without case, as the decoder does")
        assertEquals(listOf("room2" to FeedKind.REKEY), feedRoute(event(KIND_ROOM_REKEY, "room2", pubkey = "author2"), rooms))
        assertTrue(feedRoute(event(KIND_CALL_BELL, "bell9-a"), rooms).isEmpty())
    }

    @Test fun `chat older than a room's own cursor is not handed to it, even though the merged REQ asked for it`() {
        val rooms = listOf(room(1, since = now - 100), room(2, since = now - 900))
        assertTrue(feedRoute(event(KIND_CHAT, "epoch1", createdAt = now - 500), rooms).isEmpty())
        assertEquals(listOf("room2" to FeedKind.CHAT), feedRoute(event(KIND_CHAT, "epoch2", createdAt = now - 500), rooms))
    }

    @Test fun `a rekey signed by another room's authority is not followed`() {
        val rooms = listOf(room(1), room(2))
        assertTrue(feedRoute(event(KIND_ROOM_REKEY, "room1", pubkey = "author2"), rooms).isEmpty())
    }

    @Test fun `thirty saved rooms hold two subscriptions on the background socket, and events reach their room`() = runTest {
        val sockets = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://one.example"), sockets, backgroundScope, now = { currentTime }, random = Random(1), gate = RelayGate())
        pool.start(); runCurrent(); sockets.openAll(); runCurrent()
        val feed = BackgroundFeed(pool, backgroundScope, { now })
        val received = mutableListOf<Pair<String, FeedKind>>()
        val tokens = (1..30).map { i -> feed.join(BackgroundFeed.Member({ room(i) }) { kind, _ -> received += "room$i" to kind }) }
        advanceTimeBy(1_000); runCurrent()
        val socket = sockets.opened.single()
        val requests = socket.sent.filter { it.startsWith("[\"REQ\"") }
        assertEquals(2, requests.size, "One REQ per twenty rooms, not three per room")
        assertEquals(2, feed.subscriptionCount())
        for (request in requests) assertTrue(Json.parseToJsonElement(request).jsonArray.size - 2 <= 3, "At most a bell, a chat and a rekey filter")
        val ids = socket.requestedSubscriptions()
        val second = ids[1]
        assertTrue(Json.parseToJsonElement(requests[1]).jsonArray[2].jsonObject.toString().contains("bell25-a"))
        socket.deliverEvent(second, event(KIND_CALL_BELL, "bell25-a"))
        runCurrent()
        assertEquals(listOf("room25" to FeedKind.BELL), received)

        // A room leaving is one resubscription, after the set settles.
        feed.leave(tokens.first())
        advanceTimeBy(1_000); runCurrent()
        assertEquals(4, socket.requestedSubscriptions().size)
        assertEquals(ids.toSet(), socket.sent.filter { it.startsWith("[\"CLOSE\"") }.map { it.substringAfter("[\"CLOSE\",\"").substringBefore("\"") }.toSet())
        feed.end(); pool.stop()
    }

    @Test fun `the midnight rebuild is spread over minutes, never before midnight`() {
        val random = Random(7)
        val delays = List(200) { midnightRebuildDelayMs(1_000, random) }
        assertTrue(delays.all { it in 1_000 until 1_000 + MIDNIGHT_SPREAD_MS })
        assertTrue(delays.max() - delays.min() > MIDNIGHT_SPREAD_MS / 2, "Spread, not one instant")
        assertTrue(MIDNIGHT_SPREAD_MS >= 5 * 60 * 1000)
    }
}
