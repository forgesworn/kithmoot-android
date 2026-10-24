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
    @Test fun refusesImplicitPrivateDuplicateAndCredentialledRelayDestinations() {
        val key = fixture().getValue("request").jsonObject.getValue("pubkey").jsonPrimitive.content
        assertEquals(listOf("wss://relay.example/path%2Fkept"), SpryControlEndpoint.parse("spry://$key?relay=wss%3A%2F%2Frelay.example%2Fpath%252Fkept").relays)
        for (uri in listOf("spry://$key", "spry://$key?relay=ws%3A%2F%2Frelay.example", "spry://$key?relay=wss%3A%2F%2F127.0.0.1", "spry://$key?relay=wss%3A%2F%2Fnode.local", "spry://$key?relay=wss%3A%2F%2Frelay.example&x=value")) {
            assertTrue(runCatching { SpryControlEndpoint.parse(uri) }.isFailure)
        }
    }
}
