package dev.forgesworn.kithmoot.service

import dev.forgesworn.kithmoot.notifications.CallRingMode
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.LinkRelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocket
import dev.forgesworn.kithmoot.relay.RelaySocketFactory
import dev.forgesworn.kithmoot.relay.RelaySocketListener
import dev.forgesworn.kithmoot.session.BackgroundInbox
import dev.forgesworn.kithmoot.session.CHAT_RETENTION_SECONDS
import dev.forgesworn.kithmoot.session.ChatInvite
import dev.forgesworn.kithmoot.session.ChatMessage
import dev.forgesworn.kithmoot.session.ChatReaction
import dev.forgesworn.kithmoot.session.KIND_CHAT
import dev.forgesworn.kithmoot.session.PendingChatOutbox
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.support.FakeRelay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The P4-01 B-J cases; see the background delivery ticket. */
class BackgroundDeliveryTest {
    private class MemoryStore : RoomStorage {
        var bytes: ByteArray? = null
        override fun read(): ByteArray? = bytes?.clone()
        override fun write(value: ByteArray) { bytes = value.clone() }
        override fun reset() { bytes = null }
    }

    private val self = "a".repeat(64)
    private val other = "b".repeat(64)
    private fun inbox(store: RoomStorage, device: String = "d".repeat(64)) = BackgroundInbox(store, "room", self, device)
    private fun message(id: String, from: String = other, sentAt: Long = 1_000, reaction: ChatReaction? = null,
        replaces: String? = null, retracts: String? = null, invite: ChatInvite? = null) =
        ChatMessage(id, from, "c".repeat(64), "text", sentAt, reaction = reaction, replaces = replaces, retracts = retracts, invite = invite)

    // B-J01
    @Test fun `a Link relay address never reaches the public socket factory`() {
        val node = "a".repeat(51) + "q"
        var publicCalls = 0
        val public = RelaySocketFactory { _, _ -> publicCalls += 1; NoSocket }
        val listener = object : RelaySocketListener {
            override fun onOpen() = Unit
            override fun onMessage(text: String) = Unit
            override fun onClosed(reason: String) = Unit
        }
        val withoutConsent = backgroundSockets(public, LinkRelaySocketFactory { _, _, _ -> NoSocket }, ActiveLinkRoute { null })
        assertFailsWith<IllegalStateException> { withoutConsent.open("ws://$node/events", listener) }
        assertFailsWith<IllegalArgumentException> { withoutConsent.open("wss://$node/events", listener) }
        var linkRoute: String? = null
        val consented = backgroundSockets(public, LinkRelaySocketFactory { _, route, _ -> linkRoute = route; NoSocket },
            ActiveLinkRoute { "route-1" })
        consented.open("ws://$node/events", listener)
        assertEquals("route-1", linkRoute)
        assertEquals(0, publicCalls)
    }

    private object NoSocket : RelaySocket {
        override fun send(text: String) = Unit
        override fun close() = Unit
    }

    // B-J02
    @Test fun `room selection names why a room is not watched`() {
        fun candidate(anonymous: Boolean = false, quiet: Boolean = false, ended: Boolean = false,
            epoch: String? = "epoch", bunker: Boolean = false) = DeliveryCandidate("room", anonymous, quiet, ended, epoch, bunker)
        val closed: (String) -> Boolean = { false }
        assertNull(deliveryExclusion(candidate(), closed))
        assertEquals(DeliveryExclusion.ANONYMOUS, deliveryExclusion(candidate(anonymous = true, quiet = true), closed))
        assertEquals(DeliveryExclusion.QUIET, deliveryExclusion(candidate(quiet = true), closed))
        assertEquals(DeliveryExclusion.ENDED, deliveryExclusion(candidate(ended = true), closed))
        assertEquals(DeliveryExclusion.NO_EPOCH, deliveryExclusion(candidate(epoch = null), closed))
        assertEquals(DeliveryExclusion.BUNKER, deliveryExclusion(candidate(bunker = true), closed))
        assertEquals(DeliveryExclusion.OPEN, deliveryExclusion(candidate()) { it == "room" })
    }

    @Test fun `the service runs for delivery alone, and never without notifications or rooms`() {
        val nothing: (String) -> CallRingMode = { CallRingMode.NOTHING }
        assertTrue(shouldRunBackgroundService(false, true, listOf("a"), nothing))
        assertFalse(shouldRunBackgroundService(false, false, listOf("a"), nothing))
        assertTrue(shouldRunBackgroundService(true, false, listOf("a"), { CallRingMode.RING }))
        assertFalse(shouldRunBackgroundService(false, true, emptyList(), nothing))
        assertFalse(shouldRunBackgroundService(true, true, listOf("a"), { CallRingMode.RING }, notificationsPermitted = false))
    }

    // B-J03
    @Test fun `the chat filter follows the epoch and resumes from the cursor within retention`() {
        val now = 10_000_000L
        val fresh = backgroundChatFilter("epoch-2", 0, now)
        assertEquals(listOf(KIND_CHAT), fresh.kinds)
        assertEquals(mapOf("#d" to listOf("epoch-2")), fresh.tags)
        assertEquals(now - CHAT_RETENTION_SECONDS, fresh.since)
        assertEquals(now - 60 - CHAT_CURSOR_SKEW_SECONDS, backgroundChatFilter("epoch-2", now - 60, now).since)
    }

    // B-J04
    @Test fun `one message over two relays or after a restart records once`() {
        val store = MemoryStore()
        assertTrue(inbox(store).record("event-1", 1_000, message("m1")))
        assertFalse(inbox(store).record("event-1", 1_000, message("m1")))
        // The same message re-sent as a new outer event is still one message.
        assertFalse(inbox(store).record("event-2", 1_001, message("m1")))
        val reloaded = inbox(store).state()
        assertEquals(listOf("m1"), reloaded.unread.map { it.id })
        assertEquals(1_001, reloaded.cursor)
    }

    @Test fun `another device's inbox is never read`() {
        val store = MemoryStore()
        inbox(store).record("event-1", 1_000, message("m1"))
        assertTrue(inbox(store, device = "e".repeat(64)).state().unread.isEmpty())
    }

    @Test fun `the seen set is bounded`() {
        val store = MemoryStore()
        val box = inbox(store)
        repeat(BackgroundInbox.MAX_SEEN) { box.record("event-$it", 1_000L + it, message("m$it")) }
        assertEquals(BackgroundInbox.MAX_SEEN, box.state().seen.size)
        assertEquals(BackgroundInbox.MAX_UNREAD, box.state().unread.size)
        assertTrue(store.bytes!!.size <= BackgroundInbox.MAX_BYTES)
    }

    // B-J05
    @Test fun `statements about messages and own messages never count as unread`() {
        val store = MemoryStore()
        val box = inbox(store)
        assertFalse(box.record("e1", 1_000, message("m1", from = self)))
        assertFalse(box.record("e2", 1_000, message("m2", reaction = ChatReaction("m0", other, "+", true, 1))))
        assertFalse(box.record("e3", 1_000, message("m3", replaces = "m0")))
        assertFalse(box.record("e4", 1_000, message("m4", retracts = "m0")))
        assertFalse(box.record("e5", 1_000, message("m5", invite = ChatInvite(self, "r".repeat(64), "sealed"))))
        assertTrue(box.state().unread.isEmpty())
        assertEquals(10, box.state().seen.size)
    }

    // B-J06
    @Test fun `opening a room reads everything so far and catch-up does not count it again`() {
        val store = MemoryStore()
        val box = inbox(store)
        box.record("e1", 1_000, message("m1", sentAt = 1_000))
        box.markRead(2_000)
        assertTrue(box.state().unread.isEmpty())
        assertEquals(2_000, box.state().cursor)
        // Shown live while the room was open, then returned by the resumed subscription.
        assertFalse(box.record("e2", 1_990, message("m2", sentAt = 1_990)))
        assertTrue(box.record("e3", 2_010, message("m3", sentAt = 2_010)))
        assertEquals(listOf("m3"), box.state().unread.map { it.id })
    }

    @Test fun `a room first watched now does not count its retained history`() {
        val box = inbox(MemoryStore())
        // What the service does on first watching a room: start counting from now.
        box.markRead(5_000)
        assertFalse(box.record("old", 4_000, message("m-old", sentAt = 4_000)))
        assertTrue(box.record("new", 5_010, message("m-new", sentAt = 5_010)))
        assertEquals(listOf("m-new"), box.state().unread.map { it.id })
    }

    @Test fun `the open-room registry is counted and announces only the first open and last close`() {
        val heard = mutableListOf<String>()
        val listener: (String) -> Unit = { heard += it }
        val registry = dev.forgesworn.kithmoot.notifications.ActiveRoomRegistry
        registry.listen(listener)
        try {
            registry.mark("reg-room"); registry.mark("reg-room")
            registry.unmark("reg-room")
            assertTrue(registry.isOpen("reg-room"))
            registry.unmark("reg-room")
            assertFalse(registry.isOpen("reg-room"))
            registry.unmark("reg-room")
            assertEquals(listOf("reg-room", "reg-room"), heard)
        } finally { registry.unlisten(listener) }
    }

    // B-J07
    @Test fun `a pending message flushes only on its own epoch and clears only on confirmation`() = runBlocking {
        val store = MemoryStore()
        val event = Events.sign(ByteArray(32) { 3 }, KIND_CHAT, 1_000, listOf(listOf("d", "epoch-1")), "ciphertext", ByteArray(32))
        val outbox = PendingChatOutbox(store, "room", self, event.pubkey)
        val relay = FakeRelay()
        assertEquals(FlushOutcome.NOTHING, flushPending(outbox, "epoch-1", relay.transport()))
        outbox.retain("epoch-1", event)

        assertEquals(FlushOutcome.EPOCH_CHANGED, flushPending(outbox, "epoch-2", relay.transport()))
        assertTrue(relay.published.isEmpty())
        assertNotNull(outbox.pending())

        relay.confirmsPublications = false
        assertEquals(FlushOutcome.NOT_CONFIRMED, flushPending(outbox, "epoch-1", relay.transport()))
        assertNotNull(outbox.pending())

        relay.confirmsPublications = true
        assertEquals(FlushOutcome.SENT, flushPending(outbox, "epoch-1", relay.transport()))
        assertEquals(listOf(event), relay.published)
        assertNull(outbox.pending())
    }

    // B-J08
    @Test fun `a room with a relay up is receiving, otherwise the most fundamental reason shows`() {
        fun one(up: Int, signer: Boolean, network: Boolean = true, restricted: Boolean = false) =
            roomDeliveryState(RoomLink(up, signer), network, restricted)
        assertEquals(DeliveryState.LIVE, one(1, signer = true, network = false, restricted = true))
        assertEquals(DeliveryState.RESTRICTED, one(0, signer = true, network = false, restricted = true))
        assertEquals(DeliveryState.WAITING_FOR_NETWORK, one(0, signer = true, network = false))
        assertEquals(DeliveryState.NEEDS_SIGNER, one(0, signer = true))
        assertEquals(DeliveryState.RECONNECTING, one(0, signer = false))
    }

    @Test fun `one connected room cannot hide another that is stuck`() {
        assertEquals(DeliveryState.OFF, deriveDeliveryState(emptyList(), network = true, restricted = false))
        assertEquals(DeliveryState.LIVE, deriveDeliveryState(listOf(RoomLink(1, false), RoomLink(2, false)), true, false))
        assertEquals(DeliveryState.NEEDS_SIGNER, deriveDeliveryState(listOf(RoomLink(1, false), RoomLink(0, true)), true, false))
        assertEquals(DeliveryState.RECONNECTING, deriveDeliveryState(listOf(RoomLink(0, false), RoomLink(1, false)), true, false))
        // A loopback or otherwise unvalidated route that still carries traffic is not "waiting".
        assertEquals(DeliveryState.LIVE, deriveDeliveryState(listOf(RoomLink(1, false)), network = false, restricted = false))
    }
}
