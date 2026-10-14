package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContactInboxTest {
    private val a = LocalSigner(ByteArray(32) { 1 })
    private val b = LocalSigner(ByteArray(32) { 2 })
    private val f = javaClass.getResourceAsStream("/contact-invitations-web.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
    private val now = f.getValue("now").jsonPrimitive.long
    private val room = f.getValue("room").jsonPrimitive.content
    private val link = f.getValue("link").jsonPrimitive.content
    private val relays = listOf("wss://relay.example")
    private class Store : ProjectStorage {
        var text: String? = null; var held = false; var refuse = false
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun load() = text
        override suspend fun save(value: String) { check(held && !refuse); text = value }
        override suspend fun release() { held = false }
    }
    private class Network : RoomTransport {
        val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val sent = mutableListOf<NostrEvent>()
        var ack = false
        var dispatch: suspend () -> Unit = {}
        override fun publish(event: NostrEvent) = error("Must use guarded delivery")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long, stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            dispatch(); check(stillAllowed()); sent.add(event); return ack
        }
    }
    private fun TestScope.inbox(store: Store, net: Network, actor: ParticipantSigner = a, allowed: () -> Boolean = { true }, current: () -> Boolean = { true }) =
        ContactInbox(actor, store, backgroundScope, ContactsRelay(net) {}, { ContactsRelay(net) {} }, current, { allowed() }, { false }, { now })

    @Test fun exactPendingEnvelopesSurviveRestartAndClearOnlyOnAcknowledgement() = runTest {
        val store = Store(); val net = Network()
        val first = inbox(store, net); first.open(); first.enqueue(b.pubkey, room, link, relays, relays)
        first.retry(); assertEquals(2, first.state.value.pending)
        val firstId = net.sent.single().id
        first.close(); assertFalse(store.held)
        val second = inbox(store, net); second.open(); net.ack = true; second.retry()
        assertEquals(firstId, net.sent[1].id); assertEquals(0, second.state.value.pending)
        assertEquals(1, second.state.value.invitations.size)
        second.close()
    }
    @Test fun acceptsOfflineBrowserInvitationOnceAndRemembersDismissal() = runTest {
        val store = Store(); val net = Network(); val event = NostrEvent.fromJson(f.getValue("other"))
        val inbox = inbox(store, net, b); inbox.open(); runCurrent()
        net.events.emit(event); runCurrent(); net.events.emit(event); runCurrent()
        assertEquals(1, inbox.state.value.invitations.size)
        inbox.dismiss(inbox.state.value.invitations.single().id); inbox.close()
        val next = inbox(store, net, b); next.open(); runCurrent(); net.events.emit(event); runCurrent()
        assertTrue(next.state.value.invitations.isEmpty()); next.close()
    }
    @Test fun withdrewGrantOrForgotRoomBeforeDelayedTransportDispatch() = runTest {
        val store = Store(); val net = Network(); var allowed = true
        val inbox = inbox(store, net, allowed = { allowed }); inbox.open(); inbox.enqueue(b.pubkey, room, link, relays, relays)
        net.dispatch = { allowed = false }; inbox.retry(); assertTrue(net.sent.isEmpty())
        assertEquals(2, inbox.state.value.pending)
        allowed = true; net.dispatch = { inbox.forgetRoom(room) }; inbox.retry()
        assertTrue(net.sent.isEmpty()); assertEquals(0, inbox.state.value.pending); assertTrue(inbox.state.value.invitations.isEmpty())
        inbox.close()
    }
    @Test fun storageRefusalPreventsOfferingAnyInvitationAndWrongAccountCacheFailsClosed() = runTest {
        val store = Store(); val net = Network(); val inbox = inbox(store, net); inbox.open(); store.refuse = true
        assertFails { inbox.enqueue(b.pubkey, room, link, relays, relays) }
        assertFalse(inbox.state.value.ready); assertTrue(net.sent.isEmpty()); inbox.close()
        store.refuse = false
        val wrong = inbox(store, net, b); wrong.open(); assertFalse(wrong.state.value.ready); wrong.close()
    }
}
