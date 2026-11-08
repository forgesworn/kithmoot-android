package dev.forgesworn.kithmoot.node

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.Fixtures
import dev.forgesworn.kithmoot.session.encodeChatEvent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ExistingNodeHistoryTest {
    private val now = 1_000L
    private val room = Fixtures.room()
    private val identity = Fixtures.primary(room, 2, 3)
    private val original = encodeChatEvent("Before moving", identity.participant, identity.credential,
        room.roomId, room.roomKey, identity.deviceSecretKey, now)

    private class Target : RoomTransport {
        val kept = linkedMapOf<String, NostrEvent>()
        var omitReadback = false
        var writes = 0
        override fun publish(event: NostrEvent) = error("Use confirmed publication")
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            writes++; kept[event.id] = event; return true
        }
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long) =
            if (omitReadback) emptyList() else kept.values.toList()
        override fun subscribe(filters: List<Filter>) = emptyFlow<NostrEvent>()
    }

    @Test fun `copies exact signed originals and requires their private relay readback`(): Unit = runBlocking {
        val target = Target()
        copyExistingNodeHistory(listOf(original, original), target, { true }, { now })
        assertEquals(listOf(original), target.kept.values.toList())
        assertEquals(1, target.writes)
        target.omitReadback = true
        assertFailsWith<IllegalStateException> { copyExistingNodeHistory(listOf(original), target, { true }, { now }) }
    }

    @Test fun `a changed room or corrupt original never writes history`(): Unit = runBlocking {
        val target = Target()
        assertFailsWith<IllegalStateException> { copyExistingNodeHistory(listOf(original), target, { false }, { now }) }
        assertFailsWith<IllegalArgumentException> { copyExistingNodeHistory(listOf(original.copy(content = "changed")), target, { true }, { now }) }
        assertEquals(0, target.writes)
    }
}
