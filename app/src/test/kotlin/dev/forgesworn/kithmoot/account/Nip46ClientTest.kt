package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_DEVICE_CREDENTIAL
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.*

/**
 * A signer on the far side of a relay, in memory: it holds the person's key,
 * decrypts what the client sends, and answers the way NIP-46 says.
 */
private class FakeBunker(val secret: String?, val refuse: Boolean = false) : RoomTransport {
    val userKey = Entropy.bytes(32)
    val userPubkey = Schnorr.publicKeyHex(userKey)
    val signerKey = Entropy.bytes(32)
    val signerPubkey = Schnorr.publicKeyHex(signerKey)
    val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
    val methods = ArrayList<String>()
    var connectedClient: String? = null

    override fun describe(): List<String> = listOf("wss://fake")
    override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events

    override fun publish(event: NostrEvent) {
        assertEquals(KIND_NOSTR_CONNECT, event.kind)
        assertEquals(signerPubkey, event.tagValue("p"))
        assertTrue(Events.verify(event))
        val key = Nip44.conversationKey(signerKey, event.pubkey.hexToBytes())
        val request = Json.parseToJsonElement(Nip44.decrypt(event.content, key)).jsonObject
        val method = request["method"]!!.jsonPrimitive.content
        val params = request["params"]!!.jsonArray.map { it.jsonPrimitive.content }
        methods += method
        val (result, error) = when {
            refuse -> null to "not today"
            method == "connect" -> if (params.getOrNull(1) == (secret ?: "")) { connectedClient = event.pubkey; "ack" to null } else null to "bad secret"
            connectedClient != event.pubkey -> null to "unknown client"
            method == "get_public_key" -> userPubkey to null
            method == "sign_event" -> {
                val unsigned = Json.parseToJsonElement(params[0]).jsonObject
                val signed = Events.sign(userKey, unsigned["kind"]!!.jsonPrimitive.content.toInt(), unsigned["created_at"]!!.jsonPrimitive.content.toLong(),
                    unsigned["tags"]!!.jsonArray.map { tag -> tag.jsonArray.map { it.jsonPrimitive.content } }, unsigned["content"]!!.jsonPrimitive.content)
                signed.toJson().toString() to null
            }
            else -> null to "unsupported"
        }
        val body = buildJsonObject {
            put("id", request["id"]!!.jsonPrimitive.content)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }.toString()
        events.tryEmit(Events.sign(signerKey, KIND_NOSTR_CONNECT, 1_800_000_000, listOf(listOf("p", event.pubkey)), Nip44.encrypt(body, key)))
    }

    fun pointer() = BunkerPointer(signerPubkey, listOf("wss://fake"), secret)
}

@OptIn(ExperimentalCoroutinesApi::class)
class Nip46ClientTest {
    @Test fun `connects with the secret, learns the key, and signs a device credential`() = runTest {
        val bunker = FakeBunker("open-sesame")
        val client = Nip46Client(bunker.pointer(), Entropy.bytes(32), bunker, this, now = { 1_800_000_000 })
        advanceUntilIdle()
        client.connect()
        assertEquals(bunker.userPubkey, client.getPublicKey())
        val signer = BunkerSigner(bunker.userPubkey, client)
        val identity = PrimaryIdentity.createWith(signer, "ab".repeat(32), 1_800_003_600, 1_800_000_000)
        assertEquals(bunker.userPubkey, identity.participant)
        assertEquals(KIND_DEVICE_CREDENTIAL, identity.credential.kind)
        assertEquals(identity.devicePubkey, identity.credential.tagValue("device"))
        assertTrue(Events.verify(identity.credential))
        assertNull(identity.participantKeyForStorage(), "the key is with the signer, not here")
        assertEquals(listOf("connect", "get_public_key", "sign_event"), bunker.methods)
        client.close()
    }

    @Test fun `a refusal is an error the person can read, not a hang`() = runTest {
        val bunker = FakeBunker("s", refuse = true)
        val client = Nip46Client(bunker.pointer(), Entropy.bytes(32), bunker, this, now = { 1_800_000_000 })
        advanceUntilIdle()
        val error = assertFailsWith<SignerException> { client.connect() }
        assertTrue("not today" in error.message!!)
        client.close()
    }

    @Test fun `silence times out instead of waiting forever`() = runTest {
        val quiet = object : RoomTransport {
            override fun describe() = listOf("wss://quiet")
            override fun publish(event: NostrEvent) {}
            override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = MutableSharedFlow()
        }
        val client = Nip46Client(BunkerPointer("ab".repeat(32), listOf("wss://quiet"), null), Entropy.bytes(32), quiet, this, now = { 1 }, timeoutMs = 1_000)
        val error = assertFailsWith<SignerException> { client.getPublicKey() }
        assertTrue("did not answer" in error.message!!)
        client.close()
    }

    @Test fun `an answer from anybody but the signer is ignored`() = runTest {
        val bunker = FakeBunker(null)
        val impostor = Entropy.bytes(32)
        val client = Nip46Client(bunker.pointer(), Entropy.bytes(32), bunker, this, now = { 1_800_000_000 }, timeoutMs = 500)
        advanceUntilIdle()
        // Something that looks like an ack, signed by somebody else, before the real one could come.
        bunker.events.tryEmit(Events.sign(impostor, KIND_NOSTR_CONNECT, 1_800_000_000, listOf(listOf("p", client.clientPubkey)), "garbage"))
        client.connect()
        assertEquals(bunker.userPubkey, client.getPublicKey())
        client.close()
    }
}
