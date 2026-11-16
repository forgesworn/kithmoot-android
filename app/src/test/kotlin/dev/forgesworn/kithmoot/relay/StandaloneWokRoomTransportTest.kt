package dev.forgesworn.kithmoot.relay

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StandaloneWokRoomTransportTest {
    private val room = "11".repeat(32)
    private val key = ByteArray(32) { 1 }

    @Test fun `unsupported writes never reach the relay`() = runBlocking {
        val inner = RecordingTransport()
        val transport = StandaloneWokRoomTransport(inner, room)
        assertFailsWith<PublicationNotOfferedException> { transport.publish(event(20471)) }
        assertFailsWith<PublicationNotOfferedException> { transport.publishConfirmed(event(1460, "22".repeat(32))) }
        assertTrue(inner.published.isEmpty())
        Unit
    }

    @Test fun `text and presence retain guarded publication semantics`() = runBlocking {
        val inner = RecordingTransport()
        val transport = StandaloneWokRoomTransport(inner, room)
        val event = event(1460)
        assertTrue(transport.publishConfirmedGuarded(event, 4, { true }, 100))
        assertEquals(listOf(event), inner.published)
        assertEquals(4, inner.generation)
        Unit
    }

    @Test fun `stored history must be entirely inside the granted kinds and room`() = runBlocking {
        val inner = RecordingTransport()
        val transport = StandaloneWokRoomTransport(inner, room)
        val supported = Filter(kinds = listOf(1460), tags = mapOf("#d" to listOf(room)))
        transport.queryStored(listOf(supported))
        assertEquals(listOf(supported), inner.queries.single())
        assertFailsWith<StoredHistoryUnavailableException> {
            transport.queryStored(listOf(supported.copy(kinds = listOf(1460, 20471))))
        }
        assertFailsWith<StoredHistoryUnavailableException> {
            transport.queryStored(listOf(supported.copy(tags = mapOf("#d" to listOf(room, "22".repeat(32))))))
        }
        Unit
    }

    @Test fun `unsupported streams stay silent without claiming replay completion`() = runBlocking {
        val inner = RecordingTransport()
        val transport = StandaloneWokRoomTransport(inner, room)
        var replayed = false
        val unsupported = Filter(kinds = listOf(20471), tags = mapOf("#d" to listOf(room)))
        transport.subscribeReplayed(listOf(unsupported)) { replayed = true }
        assertTrue(inner.subscriptions.isEmpty())
        assertTrue(!replayed)

        transport.subscribe(listOf(Filter(kinds = listOf(1460, 20471), tags = mapOf("#d" to listOf(room)))))
        assertEquals(listOf(1460), inner.subscriptions.single().single().kinds)
        Unit
    }

    private fun event(kind: Int, scope: String = room): NostrEvent =
        Events.sign(key, kind, 1_800_000_000, listOf(listOf("d", scope)), "fixture")

    private class RecordingTransport : RoomTransport {
        val published = mutableListOf<NostrEvent>()
        val queries = mutableListOf<List<Filter>>()
        val subscriptions = mutableListOf<List<Filter>>()
        var generation = 0L
        override fun publish(event: NostrEvent) { published += event }
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            published += event
            this.generation = generation
            return true
        }
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            queries += filters
            return emptyList()
        }
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> {
            subscriptions += filters
            return emptyFlow()
        }
    }
}
