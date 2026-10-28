package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.hexToBytes
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SpryLinkTest {
    private fun fixture() = Json.parseToJsonElement(requireNotNull(javaClass.getResource("/spry-link.json")).readText()).jsonObject
    @Test fun readsActualRustSignedEncryptedMessagesAndPeerCards() {
        val vector = fixture()
        val now = vector.getValue("now").jsonPrimitive.long
        val target = SpryControlEndpoint.parse(vector.getValue("endpoint").jsonPrimitive.content)
        val key = ByteArray(32) { vector.getValue("clientKeyRepeatedByte").jsonPrimitive.int.toByte() }
        val request = NostrEvent.fromJson(vector.getValue("request"))
        val response = NostrEvent.fromJson(vector.getValue("response"))
        assertTrue(Events.verify(request))
        val body = Json.parseToJsonElement(Nip44.decrypt(request.content, Nip44.conversationKey(key, target.publicKey.hexToBytes()))).jsonObject
        assertEquals("/v1/grants", body.getValue("path").jsonPrimitive.content)
        assertEquals(buildJsonObject { put("ok", true) }, SpryLink.response(target, request, response, key, now))
        assertNull(SpryLink.response(target, request.copy(id = "0".repeat(64)), response, key, now))
        assertNull(SpryLink.response(target, request, response, key, now + 30))
        assertNull(SpryLink.response(target, request, response.copy(content = "tampered"), key, now))
        val card = requireNotNull(SpryLink.card(vector["node"], now))
        assertEquals(target.publicKey, card.pubkey)
        assertNull(SpryLink.card(card.toJson(), now + 600))
        assertNull(SpryLink.card(card.copy(content = "{}").toJson(), now))
    }
    @Test fun aRefusalCarriesItsStatusAndNeverItsText() {
        val node = ByteArray(32) { 1 }
        val client = ByteArray(32) { 2 }
        val target = SpryControlEndpoint(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(node), listOf("wss://relay.example/"))
        val now = 1_800_000_000L
        val request = SpryLink.request(target, "/v1/grants/devices", buildJsonObject { put("v", 1) }, client, now)
        val conversation = Nip44.conversationKey(node, request.pubkey.hexToBytes())
        fun reply(status: Int, body: JsonObject) = Events.sign(node, 24182, now,
            listOf(listOf("p", request.pubkey), listOf("e", request.id), listOf("expiration", (now + 30).toString())),
            Nip44.encrypt(buildJsonObject { put("v", 1); put("status", status); put("body", body) }.toString(), conversation))
        val refused = reply(403, buildJsonObject { put("error", "Approval required.") })
        assertEquals(SpryReply(403, null), SpryLink.reply(target, request, refused, client, now))
        assertNull(SpryLink.response(target, request, refused, client, now))
        val ok = buildJsonObject { put("v", 1); put("sealed", true); put("enforced", true); put("devices", 2) }
        assertEquals(SpryReply(200, ok), SpryLink.reply(target, request, reply(200, ok), client, now))
        assertEquals(ok, SpryLink.response(target, request, reply(200, ok), client, now))
        assertNull(SpryLink.reply(target, request, reply(199, ok), client, now))
        // The room-grant paths are ones a client may ask for.
        for (path in listOf("/v1/grants/remove", "/v1/grants/devices", "/v1/grants/vouch")) assertTrue(path in SpryLink.PATHS)
    }
    @Test fun refusesImplicitPrivateDuplicateAndCredentialledRelayDestinations() {
        val key = fixture().getValue("request").jsonObject.getValue("pubkey").jsonPrimitive.content
        assertEquals(listOf("wss://relay.example/path%2Fkept"), SpryControlEndpoint.parse("spry://$key?relay=wss%3A%2F%2Frelay.example%2Fpath%252Fkept").relays)
        for (uri in listOf("spry://$key", "spry://$key?relay=ws%3A%2F%2Frelay.example", "spry://$key?relay=wss%3A%2F%2F127.0.0.1", "spry://$key?relay=wss%3A%2F%2Fnode.local", "spry://$key?relay=wss%3A%2F%2Frelay.example&x=value")) {
            assertTrue(runCatching { SpryControlEndpoint.parse(uri) }.isFailure)
        }
    }
}
