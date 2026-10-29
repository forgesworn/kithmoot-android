package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.DecodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.DeliveryReceipt
import dev.forgesworn.kithmoot.protocol.DeliveryReceiptStream
import dev.forgesworn.kithmoot.protocol.EncodeDeliveryReceiptOptions
import dev.forgesworn.kithmoot.protocol.KindredProof
import dev.forgesworn.kithmoot.protocol.KindredTier
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomPolicy
import dev.forgesworn.kithmoot.protocol.decodeDeliveryReceipt
import dev.forgesworn.kithmoot.protocol.deliveryReceiptDigest
import dev.forgesworn.kithmoot.protocol.deliveryReceiptIdHash
import dev.forgesworn.kithmoot.protocol.deliveryReceiptKey
import dev.forgesworn.kithmoot.protocol.deliveryReceiptOuterSecretKey
import dev.forgesworn.kithmoot.protocol.encodeDeliveryReceipt
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared `delivery-receipt-v1.json` vectors (kind 1465), checked byte for
 * byte against the web client's: the key derivations, the digest, every
 * accepted receipt, the writer's canonical body, and every refusal. The file
 * is a verbatim copy of the web project's, kept apart from
 * `kithmoot-vectors.json`. See "Delivery receipts" in `docs/protocol.md`.
 */
class DeliveryReceiptVectorsTest {
    private val root: JsonObject = Json.parseToJsonElement(
        requireNotNull(DeliveryReceiptVectorsTest::class.java.getResourceAsStream("/delivery-receipt-v1.json")) {
            "delivery-receipt-v1.json is missing from the test resources"
        }.bufferedReader().use { it.readText() },
    ).jsonObject

    private val inputs = root.child("inputs")
    private val roomId = inputs.text("roomId")
    private val deviceSecretKey = inputs.bytes("deviceSecretKey")
    private val valid = root.list("valid").map { it.jsonObject }
    private val refused = root.list("refused").map { it.jsonObject }
    private val allStreams = streamsOf(valid.first().child("decode"))

    private fun streamsOf(decode: JsonObject): List<DeliveryReceiptStream> =
        decode.list("streams").map { DeliveryReceiptStream(it.jsonObject.text("d"), it.jsonObject.bytes("key")) }

    private fun policyOf(decode: JsonObject): RoomPolicy? = decode.childOrNull("policy")?.let { RoomPolicy.fromJson(it) }

    private fun decodeOptions(decode: JsonObject) =
        DecodeDeliveryReceiptOptions(decode.text("roomId"), streamsOf(decode), decode.number("now"), policyOf(decode))

    private fun expectedOf(expected: JsonObject) = DeliveryReceipt(
        d = expected.text("d"),
        createdAt = expected.number("createdAt"),
        participant = expected.text("participant"),
        device = expected.text("device"),
        agent = expected.flag("agent"),
        got = expected.strings("got"),
    )

    @Test fun `the file has the shape this suite was written against`() {
        assertEquals(1L, root.number("schema"))
        assertEquals(2, root.list("idHashes").size)
        assertEquals(12, valid.size)
        assertEquals(33, refused.size)
        assertEquals(refused.size, refused.map { it.text("name") }.toSet().size)
    }

    @Test fun `message id hashes match the web derivation`() {
        for (entry in root.list("idHashes").map { it.jsonObject }) {
            assertEquals(entry.text("id"), entry.text("hash"), deliveryReceiptIdHash(entry.text("id")))
        }
    }

    @Test fun `every accepted receipt derives, signs and decodes exactly like the web client`() {
        for (vector in valid) {
            val name = vector.text("name")
            val event = NostrEvent.fromJson(vector.child("event"))
            val d = event.tagValue("d")!!
            val stream = allStreams.single { it.d == d }
            val index = if (name == "second-receipt-in-the-same-second") 1 else 0

            assertEquals("$name receiptKey", vector.text("receiptKey"), deliveryReceiptKey(stream.key).toHex())
            val outerSecretKey = deliveryReceiptOuterSecretKey(deviceSecretKey, d, event.createdAt, index)
            assertEquals("$name outerSecretKey", vector.text("outerSecretKey"), outerSecretKey.toHex())
            assertEquals("$name outer pubkey", event.pubkey, Schnorr.publicKeyHex(outerSecretKey))
            assertEquals(
                "$name digest",
                vector.text("digest"),
                deliveryReceiptDigest(roomId, d, event.pubkey, event.createdAt, vector.text("body")).toHex(),
            )
            assertEquals(
                "$name decoded",
                expectedOf(vector.child("expected")),
                decodeDeliveryReceipt(event, decodeOptions(vector.child("decode"))),
            )
        }
    }

    @Test fun `the writer produces the canonical body byte for byte`() {
        val ids = root.list("idHashes").map { it.jsonObject.text("id") }
        for (name in listOf("root-stream", "agent", "with-proof-under-a-kith-policy")) {
            val vector = valid.single { it.text("name") == name }
            val parsed = Json.parseToJsonElement(vector.text("body")).jsonObject
            val expected = vector.child("expected")
            val stream = allStreams.single { it.d == expected.text("d") }
            val vectorEvent = vector.child("event")
            val event = encodeDeliveryReceipt(
                EncodeDeliveryReceiptOptions(
                    roomId = roomId,
                    stream = stream,
                    deviceSecretKey = deviceSecretKey,
                    credential = NostrEvent.fromJson(parsed.child("credential")),
                    proof = parsed.childOrNull("proof")?.let { KindredProof.fromJson(it) },
                    agent = parsed["agent"]?.jsonPrimitive?.content == "true",
                    messageIds = ids,
                    createdAt = vectorEvent.number("created_at"),
                ),
            )
            assertEquals("$name outer pubkey", vectorEvent.text("pubkey"), event.pubkey)
            assertEquals("$name tags", vectorEvent.list("tags").map { t -> t.let { (it as kotlinx.serialization.json.JsonArray).map { v -> v.jsonPrimitive.content } } }, event.tags)
            val envelope = Json.parseToJsonElement(Nip44.decrypt(event.content, deliveryReceiptKey(stream.key))).jsonObject
            assertEquals("$name body", vector.text("body"), envelope.text("body"))
            // BIP-340 auxiliary randomness is fresh in production, so the
            // signature is checked rather than compared.
            assertTrue(
                "$name inner signature",
                Schnorr.verify(envelope.bytes("sig"), vector.bytes("digest"), Schnorr.publicKey(deviceSecretKey)),
            )
            // And the receipt we wrote reads back.
            assertNotNull(
                "$name reads back",
                decodeDeliveryReceipt(event, decodeOptions(vector.child("decode"))),
            )
        }
    }

    @Test fun `every refusal in the vectors is refused here too`() {
        for (vector in refused) {
            val name = vector.text("name")
            val decoded = try {
                decodeDeliveryReceipt(NostrEvent.fromJson(vector.child("event")), decodeOptions(vector.child("decode")))
            } catch (e: Exception) {
                throw AssertionError("$name: decoding must not throw", e)
            }
            assertNull(name, decoded)
        }
    }

    @Test fun `hex helpers behave on the fixtures`() {
        assertEquals("deadbeef", "deadbeef".hexToBytes().toHex())
    }
}
