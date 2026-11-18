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
                inbox.send(recipient.pubkey, "Reply from People", null, { authors -> authors.associateWith { relays } }) { event, _, allowed ->
                    assertTrue(allowed())
                    published += event
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
}
