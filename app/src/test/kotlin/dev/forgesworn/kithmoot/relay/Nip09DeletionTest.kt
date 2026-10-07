package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Nip09DeletionTest {
    private val device = ByteArray(32) { 3 }
    private val devicePub = Schnorr.publicKeyHex(device)
    private val other = ByteArray(32) { 4 }
    private val now = 1_800_000_000L

    private fun event(key: ByteArray, kind: Int, at: Long, tags: List<List<String>> = emptyList()) =
        Events.sign(key, kind, at, tags, "x", ByteArray(32))

    /** A relay that answers by author and honours a deletion by its `e` tags. */
    private class Relay(val held: MutableList<NostrEvent>) : RoomTransport {
        val published = mutableListOf<NostrEvent>()
        val filters = mutableListOf<Filter>()
        var accept = true
        override fun publish(event: NostrEvent) = error("deletions must be confirmed")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = emptyFlow()
        override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            this.filters += filters
            val f = filters.single()
            return held.filter { it.pubkey in f.authors.orEmpty() }.sortedByDescending { it.createdAt }.take(f.limit ?: Int.MAX_VALUE)
        }
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            published += event
            if (!accept) return false
            val gone = event.tags.filter { it[0] == "e" }.map { it[1] }.toSet()
            held.removeAll { it.id in gone }
            held += event
            return true
        }
    }

    @Test fun `a request names each event by id, each addressable one by address too, and each kind`() {
        val chat = event(device, 1, now)
        val roster = event(device, 30_078, now + 1, listOf(listOf("d", "kithmoot.roster")))
        val bare = event(device, 30_001, now + 2)
        val request = Nip09Deletion.requests(device, listOf(roster, chat, bare, chat), now + 10).single()
        assertEquals(5, request.kind)
        assertEquals(devicePub, request.pubkey)
        assertTrue(Events.verify(request))
        assertEquals(now + 10, request.createdAt)
        assertEquals(Nip09Deletion.CONTENT, request.content)
        assertEquals(
            listOf(
                listOf("e", chat.id), listOf("e", roster.id), listOf("e", bare.id),
                listOf("a", "30078:$devicePub:kithmoot.roster"), listOf("a", "30001:$devicePub:"),
                listOf("k", "1"), listOf("k", "30001"), listOf("k", "30078"),
            ),
            request.tags,
        )
    }

    @Test fun `never names a deletion request, nor anything another key signed`() {
        val mine = event(device, 1, now)
        val deletion = event(device, 5, now + 1, listOf(listOf("e", mine.id)))
        val theirs = event(other, 1, now + 2)
        val request = Nip09Deletion.requests(device, listOf(mine, deletion, theirs), now).single()
        assertEquals(listOf(listOf("e", mine.id), listOf("k", "1")), request.tags)
        assertTrue(Nip09Deletion.requests(device, listOf(deletion, theirs), now).isEmpty())
    }

    @Test fun `ids go three hundred to a request`() {
        val events = (0 until 650).map { event(device, 1, now + it) }
        val requests = Nip09Deletion.requests(device, events, now + 1_000)
        assertEquals(listOf(300, 300, 50), requests.map { r -> r.tags.count { it[0] == "e" } })
        assertEquals(events.map { it.id }, requests.flatMap { r -> r.tags.filter { it[0] == "e" }.map { it[1] } })
    }

    @Test fun `asks the relays by author and deletes in rounds until a page comes back short`() = runTest {
        val mine = (0 until 1_250).map { event(device, if (it % 2 == 0) 1 else 20_000, now + it) }
        val theirs = event(other, 1, now)
        val relay = Relay((mine + theirs).toMutableList())
        val report = Nip09Deletion.deleteOwnEvents(relay, device, { now + 5_000 })
        assertEquals(1_250, report.found)
        assertEquals(1_250, report.requested)
        assertEquals(0, report.failed)
        assertTrue(report.complete)
        assertTrue(report.reached)
        assertTrue(relay.filters.all { it.authors == listOf(devicePub) && it.limit == Nip09Deletion.QUERY_LIMIT && it.kinds == null })
        assertTrue(relay.published.all { it.kind == 5 && it.pubkey == devicePub && it.tags.count { t -> t[0] == "e" } <= 300 })
        assertEquals(listOf(theirs), relay.held.filter { it.kind != 5 })
    }

    @Test fun `a relay that refuses is counted, and the run still finishes`() = runTest {
        val relay = Relay(mutableListOf(event(device, 1, now), event(device, 1, now + 1)))
        relay.accept = false
        val report = Nip09Deletion.deleteOwnEvents(relay, device, { now })
        assertEquals(2, report.found)
        assertEquals(0, report.requested)
        assertEquals(2, report.failed)
        assertFalse(report.complete)
    }

    @Test fun `a transport that cannot answer leaves the run incomplete, never thrown`() = runTest {
        val silent = object : RoomTransport {
            override fun publish(event: NostrEvent) = Unit
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = emptyFlow()
        }
        val report = Nip09Deletion.deleteOwnEvents(silent, device, { now })
        assertEquals(0, report.found)
        assertFalse(report.complete)
        assertFalse(report.reached)
    }
}
