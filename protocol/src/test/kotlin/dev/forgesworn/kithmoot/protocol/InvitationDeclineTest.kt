package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InvitationDeclineTest {
    private val root = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/invitation-decline-vectors.json")).readText()).jsonObject
    private val input = root.getValue("input").jsonObject
    private fun text(key: String) = input.getValue(key).jsonPrimitive.content
    private val host = RoomInvitationHost(RoomInvitation(text("bearerHex").hexToBytes(), Schnorr.publicKeyHex(text("inviterSkHex").hexToBytes())), text("inviterSkHex").hexToBytes())
    private val requester = text("requesterSkHex").hexToBytes()
    private val now = input.getValue("now").jsonPrimitive.long
    private fun row(name: String) = root.getValue("cases").jsonArray.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == name }
    private fun event(name: String = "declined") = NostrEvent.fromJson(row(name).getValue("event"))

    @Test fun `verbatim browser known answers authenticate refusals without admitting a guest`() {
        for (name in listOf("declined", "wrong-request", "unauthorised", "legacy-version")) {
            val event = event(name)
            assertTrue(Events.verify(event))
            assertEquals(name == "declined", decodeInvitationDecline(event, host.invitation, requester, text("request"), now) != null)
            assertNull(decodeRoomAdmissionGrant(event, host.invitation, requester, text("request"), now))
            val signer = text(if (name == "unauthorised") "outsiderSkHex" else "inviterSkHex").hexToBytes()
            val body = Nip44.decrypt(event.content, Nip44.conversationKey(requester, event.pubkey.hexToBytes()))
            val rebuilt = Events.sign(signer, event.kind, event.createdAt, event.tags,
                Nip44.encrypt(body, Nip44.conversationKey(signer, Schnorr.publicKeyHex(requester).hexToBytes()), row(name).getValue("nonceHex").jsonPrimitive.content.hexToBytes()),
                row(name).getValue("auxHex").jsonPrimitive.content.hexToBytes())
            assertEquals(event, rebuilt)
        }
    }

    @Test fun `native refusal encoder reproduces the browser event exactly`() {
        val row = row("declined")
        assertEquals(event(), encodeInvitationDecline(host, Schnorr.publicKeyHex(requester), text("request"), now,
            row.getValue("nonceHex").jsonPrimitive.content.hexToBytes(), row.getValue("auxHex").jsonPrimitive.content.hexToBytes()))
    }

    @Test fun `wrong request device invitation signature and stale clocks cannot end a wait`() {
        val event = event()
        assertNull(decodeInvitationDecline(event, host.invitation, requester, "cd".repeat(32), now))
        assertNull(decodeInvitationDecline(event, host.invitation, ByteArray(32) { 6 }, text("request"), now))
        assertNull(decodeInvitationDecline(event, createRoomInvitation().invitation, requester, text("request"), now))
        assertNull(decodeInvitationDecline(event.copy(sig = "00".repeat(64)), host.invitation, requester, text("request"), now))
        for (clock in listOf(now + 91, now - 91, -1, Long.MAX_VALUE))
            assertNull(decodeInvitationDecline(event, host.invitation, requester, text("request"), clock))
    }

    @Test fun `duplicate recipients and rendezvous are refused despite a valid outer signature`() {
        for (tag in listOf(event().tags[0], event().tags[1])) {
            val changed = Events.sign(host.inviterSecretKey, KIND_INVITATION_GRANT, now, event().tags + listOf(tag), event().content)
            assertTrue(Events.verify(changed))
            assertNull(decodeInvitationDecline(changed, host.invitation, requester, text("request"), now))
        }
    }

    @Test fun `only a current delegated responder may refuse entry`() {
        val key = ByteArray(32) { 8 }
        val grant = encodeInvitationGrant(host, Schnorr.publicKeyHex(key), text("request"), ByteArray(32) { 9 }, now)
        val delegate = requireNotNull(decodeRoomAdmissionGrant(grant, host.invitation, key, text("request"), now)?.delegate)
        val reply = encodeInvitationDecline(delegate, Schnorr.publicKeyHex(requester), text("request"), now)
        assertEquals(Schnorr.publicKeyHex(key), decodeInvitationDecline(reply, host.invitation, requester, text("request"), now)?.responder)
        assertNull(decodeInvitationDecline(reply, host.invitation, requester, text("request"), now + INVITATION_DELEGATION_TTL_SECONDS + 1))
    }

    @Test fun `a valid signature cannot make a malformed or capability-bearing payload a refusal`() {
        val original = Json.parseToJsonElement(Nip44.decrypt(event().content,
            Nip44.conversationKey(requester, event().pubkey.hexToBytes()))).jsonObject
        val mutations = listOf(
            "secret" to JsonPrimitive("09".repeat(32)),
            "decision" to JsonPrimitive("admitted"),
            "decision" to JsonPrimitive(false),
            "v" to JsonPrimitive("3"),
            "v" to JsonPrimitive(2),
            "request" to JsonPrimitive(123),
            "delegation" to JsonPrimitive("[]"),
        )
        for ((field, value) in mutations) {
            val body = JsonObject(original + (field to value))
            val reply = Events.sign(host.inviterSecretKey, KIND_INVITATION_GRANT, now, event().tags,
                Nip44.encrypt(body.toString(), Nip44.conversationKey(host.inviterSecretKey, Schnorr.publicKeyHex(requester).hexToBytes())))
            assertTrue(Events.verify(reply))
            assertNull("Malformed $field", decodeInvitationDecline(reply, host.invitation, requester, text("request"), now))
        }
    }

    @Test fun `the encoder refuses borrowed authority invalid request identifiers and unsafe clocks`() {
        val device = Schnorr.publicKeyHex(requester)
        for (clock in listOf(-1L, Long.MAX_VALUE)) assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationDecline(host, device, text("request"), clock)
        }
        assertThrows(IllegalArgumentException::class.java) { encodeInvitationDecline(host, device, "bad", now) }
        assertThrows(IllegalArgumentException::class.java) {
            encodeInvitationDecline(RoomInvitationHost(host.invitation, ByteArray(32) { 6 }), device, text("request"), now)
        }
    }
}
