package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.node.NodeFollowWords
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/** A device that knows its room moved and has not followed keeps what its person
 * sends for the node, and nothing more goes on the old route. */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveHoldTest {
    private class Store : RoomStorage {
        private var bytes: ByteArray? = null
        override fun read() = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    @Test fun `a held room keeps messages unsent and the session after the move sends them`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val store = Store()
        fun outbox() = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey, io = EmptyCoroutineContext)
        val oldRoute = FakeRelay(); val offered = mutableListOf<NostrEvent>()
        val base = oldRoute.transport()
        val counting = object : RoomTransport by base {
            override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
                offered += event; return base.publishConfirmedGuarded(event, generation, stillAllowed, timeoutMs)
            }
            override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean { offered += event; return base.publishConfirmed(event, timeoutMs) }
            override fun publish(event: NostrEvent) { offered += event; base.publish(event) }
        }
        val held = RoomSession(room, owner, counting, backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox())
        held.holdChatForMove()
        held.join(); advanceTimeBy(1_000); runCurrent()
        assertTrue(held.chatHeldForMove)

        assertFalse(held.sendChatDurable("kept for the node"))
        assertFalse(held.retryPendingChat())
        assertFalse(held.retryPendingChat(pressed = true))
        assertEquals(PendingChatState.WAITING, outbox().items().single().state)
        assertEquals(PendingChatState.WAITING, held.pendingChats.value.single().state)
        val refused = assertFailsWith<IllegalStateException> { held.sendChatConfirmed("not kept") }
        assertEquals(NodeFollowWords.NOT_SENT_WHILE_MOVING, refused.message)
        assertTrue(offered.none { it.kind == KIND_CHAT })
        assertEquals(0, oldRoute.countOfKind(KIND_CHAT))
        held.leave()

        // The journal outlives the session and the app; the session that follows the move sends it.
        val kept = outbox().items().single()
        val node = FakeRelay()
        val moved = RoomSession(room, owner, node.transport(), backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox())
        moved.join(); advanceTimeBy(1_000); runCurrent()
        assertFalse(moved.chatHeldForMove)
        assertTrue(moved.retryPendingChat())
        assertTrue(outbox().items().isEmpty())
        assertEquals(1, node.countOfKind(KIND_CHAT))
        assertTrue(offered.none { it.kind == KIND_CHAT })
        assertTrue(kept.event.id.isNotEmpty())
        moved.leave()
    }

    @Test fun `a device let in at the node but kept here by a call sends on this route as normal`() = runTest {
        val room = Fixtures.room(); val owner = Fixtures.primary(room, 1, 2)
        val store = Store()
        fun outbox() = PendingChatOutbox(store, room.roomId, owner.participant, owner.devicePubkey, io = EmptyCoroutineContext)
        val relay = FakeRelay()
        val live = RoomSession(room, owner, relay.transport(), backgroundScope, timing = Fixtures.QUIET, now = { 1L }, chatOutbox = outbox())
        live.holdChatForMove(); live.join(); advanceTimeBy(1_000); runCurrent()
        assertFalse(live.sendChatDurable("held while waiting to be let in"))
        live.releaseChatHold()
        assertFalse(live.chatHeldForMove)
        assertTrue(live.retryPendingChat())
        assertTrue(live.sendChatDurable("sent during the call"))
        assertTrue(outbox().items().isEmpty())
        assertEquals(2, relay.countOfKind(KIND_CHAT))
        live.leave()
    }
}
