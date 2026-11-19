package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.NodeExistingRoomPolicy
import dev.forgesworn.kithmoot.protocol.NodeRooms
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.session.dmPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NostrDirectMessagesTest {
    private val sender = LocalSigner(ByteArray(32) { 1 })
    private val recipient = LocalSigner(ByteArray(32) { 2 })
    private val now = 1_790_000_000L

    @Test fun opensDistinctCopiesAsTheSameConversationMessage() = runTest {
        val (message, copies) = NostrDirectMessages.prepare(sender, recipient.pubkey, "Hello <b>there</b>", null, now) { true }
        val (theirs, ours) = copies
        assertNotEquals(theirs.pubkey, ours.pubkey)
        assertNotEquals(theirs.id, ours.id)
        assertEquals(message, NostrDirectMessages.open(ours, sender.pubkey, now, { true }, { false }, sender::nip44Decrypt))
        assertEquals(message.copy(peer = sender.pubkey),
            NostrDirectMessages.open(theirs, recipient.pubkey, now, { true }, { false }, recipient::nip44Decrypt))
        assertNull(NostrDirectMessages.open(theirs, sender.pubkey, now, { true }, { false }, sender::nip44Decrypt))
        assertNull(NostrDirectMessages.open(theirs, recipient.pubkey, now, { true }, { it == sender.pubkey }, recipient::nip44Decrypt))
        assertNull(NostrDirectMessages.open(theirs.copy(sig = "0".repeat(128)), recipient.pubkey, now, { true }, { false }, recipient::nip44Decrypt))
    }

    @Test fun handsOffOnlyAValidNodeInvitationFromAnOpenedNostrMessage() = runTest {
        val room = "42".repeat(32)
        val relay = LocalSigner(ByteArray(32) { 4 })
        val policy = dmPolicy(sender.pubkey, recipient.pubkey).toJson()
        val record = NodeRooms.sign(ByteArray(32) { 3 }, room, "https://node.example", relay.pubkey, now,
            existing = NodeExistingRoomPolicy(false, policy))
        val link = NodeRooms.encodeLink("https://kithmoot.example/j/", record.toJson(), now)
        val rumor = ContactInvitations.rumor(sender.pubkey, recipient.pubkey, room, link, now)
        val copy = ContactInvitations.wrap(rumor, sender, recipient.pubkey, now) { true }
        var kept: JsonObject? = null
        val text = NostrDirectMessages.open(copy, recipient.pubkey, now, { true }, { false }, recipient::nip44Decrypt) { kept = it }
        assertNull(text)
        assertEquals(rumor, kept)
        assertTrue(ContactInvitations.read(requireNotNull(kept), recipient.pubkey, now)?.node == true)
        assertEquals(listOf(listOf("p", recipient.pubkey)), copy.tags)
        assertFailsWith<IllegalArgumentException> {
            ContactInvitations.rumor(sender.pubkey, relay.pubkey, room, link, now)
        }
    }

    @Test fun anApprovedSendSurvivesTheSignerScreenWhileIncomingReadsPause() = runTest {
        val signerOpened = CompletableDeferred<Unit>()
        val signerApproved = CompletableDeferred<Unit>()
        val actor = object : ParticipantSigner by sender {
            override suspend fun sealInboxText(peer: String, plaintext: String, current: () -> Boolean): String {
                signerOpened.complete(Unit)
                signerApproved.await()
                return sender.sealInboxText(peer, plaintext, current)
            }
        }
        val vault = object : ProjectStorage {
            private var text: String? = null
            override suspend fun acquire() = Unit
            override suspend fun load() = text
            override suspend fun save(value: String) { text = value }
            override suspend fun release() = Unit
        }
        val inbox = NostrPeopleInbox(actor, vault, { true }, { false }, { now })
        val relays = listOf("wss://public.example/")
        val published = mutableListOf<NostrEvent>()
        val incoming = NostrDirectMessages.prepare(recipient, sender.pubkey, "While the signer is open", null, now) { true }.second.first()
        var foreground = true
        inbox.open()
        try {
            val sending = async {
                inbox.send(recipient.pubkey, "Reply from People", null, { authors -> authors.associateWith { relays } }) { event, to, allowed ->
                    assertTrue(allowed())
                    published += event
                    to
                }
            }
            signerOpened.await()
            foreground = false
            inbox.receive(incoming) { foreground }
            assertEquals(0, inbox.state.value.unread)
            signerApproved.complete(Unit)
            sending.await()
            assertEquals(2, published.size)
            assertEquals("Reply from People", inbox.state.value.threads.single().messages.single().text)
            foreground = true
            inbox.receive(incoming) { foreground }
            assertEquals(1, inbox.state.value.unread)
        } finally { inbox.close() }
    }

    @Test fun aSignerRefusalPausesFurtherIncomingDecryptions() = runTest {
        var attempts = 0
        val actor = object : ParticipantSigner by recipient {
            override suspend fun openInboxCiphertext(peer: String, ciphertext: String, current: () -> Boolean): String? {
                attempts++
                return null
            }
        }
        val vault = object : ProjectStorage {
            override suspend fun acquire() = Unit
            override suspend fun load(): String? = null
            override suspend fun save(value: String) = Unit
            override suspend fun release() = Unit
        }
        val inbox = NostrPeopleInbox(actor, vault, { true }, { false }, { now })
        inbox.open()
        try {
            for (text in listOf("First incoming message", "Second incoming message")) {
                val copy = NostrDirectMessages.prepare(sender, recipient.pubkey, text, null, now) { true }.second.first()
                inbox.receive(copy) { true }
            }
            assertEquals(1, attempts)
            assertEquals(0, inbox.state.value.unread)
            assertTrue(inbox.state.value.status.contains("then refresh"))
        } finally { inbox.close() }
    }

    @Test fun unreadThreadsComeFirstThenThePeopleIMessageMost() = runTest {
        val newest = LocalSigner(ByteArray(32) { 5 })
        val request = LocalSigner(ByteArray(32) { 6 })
        val vault = object : ProjectStorage {
            override suspend fun acquire() = Unit
            override suspend fun load(): String? = null
            override suspend fun save(value: String) = Unit
            override suspend fun release() = Unit
        }
        val inbox = NostrPeopleInbox(sender, vault, { true }, { false }, { now })
        inbox.open()
        try {
            suspend fun sentTo(peer: String, time: Long) {
                val copy = NostrDirectMessages.prepare(sender, peer, "Outgoing message", null, time) { true }.second.last()
                inbox.receive(copy) { true }
            }
            sentTo(recipient.pubkey, now - 60)
            sentTo(recipient.pubkey, now - 50)
            sentTo(newest.pubkey, now - 30)
            val unread = NostrDirectMessages.prepare(request, sender.pubkey, "Unread message", null, now - 40) { true }.second.first()
            inbox.receive(unread) { true }
            assertEquals(listOf(request.pubkey, recipient.pubkey, newest.pubkey), inbox.state.value.threads.map { it.peer })
            val message = inbox.state.value.threads.first().messages.single()
            inbox.markRead(setOf(message.id))
            assertEquals(listOf(recipient.pubkey, newest.pubkey, request.pubkey), inbox.state.value.threads.map { it.peer })
        } finally { inbox.close() }
    }

    private fun memoryVault() = object : ProjectStorage {
        private var text: String? = null
        override suspend fun acquire() = Unit
        override suspend fun load() = text
        override suspend fun save(value: String) { text = value }
        override suspend fun release() = Unit
    }

    private fun routes(theirs: List<String>, mine: List<String> = listOf("wss://mine.example/")): suspend (List<String>) -> Map<String, List<String>> =
        { authors -> authors.associateWith { if (it == recipient.pubkey) theirs else mine }.filterValues { it.isNotEmpty() } }

    @Test fun aReconnectAfterTheSignerScreenDoesNotHideTheSendResult() = runTest {
        val inbox = NostrPeopleInbox(sender, memoryVault(), { true }, { false }, { now })
        inbox.open()
        try {
            // An inbox relay that wants NIP-42 refused; the other accepted.
            inbox.send(recipient.pubkey, "Hello", null, routes(listOf("wss://auth.example/", "wss://open.example/"))) { _, to, _ ->
                to - "wss://auth.example/"
            }
            val result = "${NostrPeopleInbox.SENT} auth.example did not accept it."
            assertEquals(result, inbox.state.value.sendStatus)
            assertNotEquals(0, inbox.state.value.sent)
            inbox.connectionStatus("Connecting to your Nostr inbox…")
            inbox.connectionStatus("Some inbox relays did not confirm their history. Check the relay or signer and refresh.")
            assertEquals(result, inbox.state.value.sendStatus)
            assertEquals("Hello", inbox.state.value.threads.single().messages.single().text)
        } finally { inbox.close() }
    }

    @Test fun saysWhichAccountHasNoPublishedInbox() = runTest {
        val inbox = NostrPeopleInbox(sender, memoryVault(), { true }, { false }, { now })
        inbox.open()
        try {
            inbox.send(recipient.pubkey, "Hello", null, routes(emptyList())) { _, to, _ -> to }
            assertEquals(NostrPeopleInbox.NO_RECIPIENT_INBOX, inbox.state.value.sendStatus)
            inbox.send(recipient.pubkey, "Hello", null, routes(listOf("wss://theirs.example/"), mine = emptyList())) { _, to, _ -> to }
            assertEquals(NostrPeopleInbox.NO_OWN_INBOX, inbox.state.value.sendStatus)
            assertEquals(0, inbox.state.value.sent)
            assertNotEquals(0, inbox.state.value.sends)
            assertFalse(inbox.state.value.pending)
        } finally { inbox.close() }
    }

    @Test fun aCopyNoRelayTookWaitsAndSaysWhose() = runTest {
        val inbox = NostrPeopleInbox(sender, memoryVault(), { true }, { false }, { now })
        inbox.open()
        try {
            var refuse = recipient.pubkey
            val publish: suspend (NostrEvent, List<String>, () -> Boolean) -> List<String> = { event, to, _ ->
                check(event.tags.single()[1] != refuse) { "No inbox relay accepted the message." }
                to
            }
            inbox.send(recipient.pubkey, "Hello", null, routes(listOf("wss://theirs.example/")), publish)
            assertTrue(inbox.state.value.pending)
            assertTrue(inbox.state.value.sendStatus.startsWith("None of their inbox relays"))
            refuse = sender.pubkey
            inbox.send("", "", null, routes(listOf("wss://theirs.example/")), publish)
            assertTrue(inbox.state.value.pending)
            assertTrue(inbox.state.value.sendStatus.startsWith("Sent, but your own copy is waiting"))
            refuse = ""
            inbox.send("", "", null, routes(listOf("wss://theirs.example/")), publish)
            assertFalse(inbox.state.value.pending)
            assertEquals(NostrPeopleInbox.SENT, inbox.state.value.sendStatus)
        } finally { inbox.close() }
    }

    @Test fun inboxRelayAuthenticationUsesAFreshKeyNotTheAccount() = runTest {
        val first = EphemeralInboxAuthenticator { now }
        val second = EphemeralInboxAuthenticator { now }
        val event = first.sign("wss://auth.example/", "challenge-1")
        assertTrue(dev.forgesworn.kithmoot.protocol.Events.verify(event))
        assertEquals(22242, event.kind)
        assertEquals(first.pubkey, event.pubkey)
        assertEquals(listOf(listOf("relay", "wss://auth.example/"), listOf("challenge", "challenge-1")), event.tags)
        assertNotEquals(sender.pubkey, first.pubkey)
        assertNotEquals(first.pubkey, second.pubkey)
        first.close(); second.close()
    }
}
