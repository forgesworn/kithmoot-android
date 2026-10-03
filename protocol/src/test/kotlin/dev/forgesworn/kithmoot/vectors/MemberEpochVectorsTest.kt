package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.decodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.deriveEpoch
import dev.forgesworn.kithmoot.protocol.deriveMemberEpochRequestKey
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeMemberEpochRequest
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.epochCommitment
import dev.forgesworn.kithmoot.protocol.readRekeyEvidence
import dev.forgesworn.kithmoot.protocol.secretKeyFromSeed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fold-kit 0.5.0's `vectors/member-epoch-vectors.json`, copied byte for byte (never edited),
 * reproduced the way its own `vectors/verify-member-epoch.test.ts` does: every event is rebuilt
 * by the real encoder from its recorded random draws and must come out identical, and every
 * grant is fed through the real decoder and must give the recorded result.
 */
class MemberEpochVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/member-epoch-vectors.json")) { "member-epoch-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val vectors: List<JsonObject> get() = root.child("groups").list("memberEpoch").map { it.jsonObject }
    private fun vec(name: String): JsonObject = vectors.single { it.text("name") == name }
    private fun draws(value: JsonObject): List<ByteArray> = value.strings("randomHex").map { it.hexToBytes() }
    private fun event(value: JsonObject): NostrEvent = NostrEvent.fromJson(value)
    private fun keys(epoch: Int, key: ByteArray) = EpochKeys(epoch, "00".repeat(32), key)

    @Test fun `the file is in the KithMoot vector format and every vector is counted`() {
        assertEquals("kithmoot/v1", root.text("protocolVersion"))
        assertEquals(setOf("memberEpoch"), root.child("groups").keys)
        assertEquals(13, vectors.size)
        assertEquals(7, vectors.count { it.text("name").startsWith("member-grant-") })
        assertEquals(6, vectors.count { it.text("kind") == "negative" })
        for (v in vectors) {
            assertTrue(v.text("kind") in setOf("positive", "negative"))
            v.text("note")
        }
    }

    @Test fun `epoch commitment, two ways`() {
        val input = vec("epoch-commitment").child("input")
        val secret = input.bytes("secretHex")
        val expected = vec("epoch-commitment").child("output").text("commitHex")
        assertEquals(expected, epochCommitment(input.text("roomId"), input.number("epoch").toInt(), secret))
        val independent = Digests.hmacSha256(secret, "kithmoot/v1/epoch-commit:${input.text("roomId")}:${input.number("epoch")}".toByteArray()).toHex()
        assertEquals(expected, independent)
    }

    @Test fun `member request key, two ways`() {
        val v = vec("member-request-key")
        val roomKey = v.child("input").bytes("roomKeyHex")
        assertEquals(v.child("output").text("keyHex"), deriveMemberEpochRequestKey(roomKey).toHex())
        assertEquals(v.child("output").text("keyHex"), Digests.hkdfSha256(roomKey, null, "kithmoot/v1/member-epoch-request-key".toByteArray(), 32).toHex())
    }

    @Test fun `every rekey rebuilds byte for byte and reads as the recorded evidence`() {
        val bodyKeys = mapOf(
            "rekey-with-commitment" to listOf("v", "epoch", "removed", "commit", "keys"),
            "rekey-without-commitment" to listOf("v", "epoch", "removed", "keys"),
            // The known-members gate (#207): the authority's member list, removed dropped.
            "rekey-with-members" to listOf("v", "epoch", "removed", "commit", "members", "keys"),
        )
        for ((name, wantedKeys) in bodyKeys) {
            val v = vec(name)
            val i = v.child("input")
            val random = draws(i)
            val recipients = i.strings("recipients")
            assertEquals("$name draws", recipients.size + 2, random.size)
            val previousKey = i.bytes("previousKeyHex")
            val next = i.child("next")
            val rebuilt = encodeRekeyEvent(
                i.text("roomId"), i.bytes("authoritySkHex"), keys(i.number("previousEpoch").toInt(), previousKey),
                RoomEpoch(next.number("epoch").toInt(), next.bytes("secretHex")), recipients, i.strings("removed"), i.number("createdAt"),
                commit = name != "rekey-without-commitment",
                members = if ("members" in i) i.strings("members") else null,
                recipientNonces = recipients.withIndex().associate { (n, r) -> r to random[n] },
                bodyNonce = random[recipients.size], auxRand = random[recipients.size + 1],
            )
            assertEquals(name, i.child("event"), rebuilt.toJson())
            val evidence = readRekeyEvidence(event(i.child("event")), i.text("roomId"), i.text("authority"), i.number("previousEpoch").toInt(), previousKey)
            assertNotNull(name, evidence)
            val out = v.child("output").child("evidence")
            assertEquals(out.number("epoch").toInt(), evidence!!.epoch)
            assertEquals(out.strings("removed"), evidence.removed)
            assertEquals(out.flag("closed"), evidence.closed)
            assertEquals(out.textOrNull("commit"), evidence.commit)
            assertEquals(if ("members" in out) out.strings("members") else null, evidence.members)
            val body = Json.parseToJsonElement(Nip44.decrypt(i.child("event").text("content"), previousKey)).jsonObject
            assertEquals(wantedKeys, body.keys.toList())
            if (name != "rekey-without-commitment") {
                assertEquals(i.text("previousKeyHex"), deriveEpoch(RoomEpoch(1, i.bytes("previousSecretHex"))).key.toHex())
            }
        }
    }

    @Test fun `the member request rebuilds byte for byte and a member decodes it`() {
        val v = vec("member-request")
        val i = v.child("input")
        val random = draws(i)
        assertEquals(2, random.size)
        val credential = i.child("credential")
        // The body nests the credential in the order this client's NostrEvent writes it.
        assertEquals(listOf("kind", "created_at", "tags", "content", "pubkey", "id", "sig"), credential.keys.toList())
        val rebuilt = encodeMemberEpochRequest(
            i.text("roomId"), i.text("authority"), i.bytes("roomKeyHex"), i.bytes("requesterDeviceSkHex"),
            event(credential), i.number("have").toInt(), i.number("now"), nonce = random[0], auxRand = random[1],
        )
        assertEquals("member-request", i.child("event"), rebuilt.toJson())
        val decoded = decodeMemberEpochRequest(event(i.child("event")), i.text("roomId"), i.text("authority"), i.bytes("roomKeyHex"), i.number("now"))
        val out = v.child("output").child("decoded")
        assertNotNull(decoded)
        assertEquals(out.text("device"), decoded!!.device)
        assertEquals(out.text("participant"), decoded.participant)
        assertEquals(out.text("request"), decoded.request)
        assertEquals(out.number("have").toInt(), decoded.have)
    }

    @Test fun `every grant rebuilds byte for byte and decodes to the recorded result`() {
        val request = vec("member-request").child("input").child("event")
        val memberDevice = vec("rekey-with-commitment").child("input").strings("recipients")[0]
        val grants = vectors.filter { it.text("name").startsWith("member-grant-") }
        for (v in grants) {
            val name = v.text("name")
            val i = v.child("input")
            val grant = i.child("grant")
            val random = draws(grant)
            assertEquals("$name draws", 3, random.size)
            assertEquals("$name seed", 96, grant.strings("randomHex")[0].length)
            assertEquals("$name signer", grant.text("signerSkHex"), secretKeyFromSeed(random[0]).toHex())
            assertEquals(Schnorr.publicKeyHex(grant.bytes("signerSkHex")), grant.child("event").text("pubkey"))
            assertNotEquals(memberDevice, grant.child("event").text("pubkey"))
            assertEquals(listOf(request.text("id")), i.strings("requests"))
            val rekeys = i.list("rekeys").map { event(it.jsonObject.child("event")) }
            val rebuilt = encodeMemberEpochGrant(
                i.text("roomId"), request.text("pubkey"), request.text("id"),
                grant.list("epochs").map { it.jsonObject }.map { RoomEpoch(it.number("epoch").toInt(), it.bytes("secretHex")) },
                rekeys, i.number("now"), signerSeed = random[0], nonce = random[1], auxRand = random[2],
            )
            assertEquals(name, grant.child("event"), rebuilt.toJson())
            val current = i.child("current")
            val decoded = decodeMemberEpochGrant(
                event(grant.child("event")), i.text("roomId"), i.text("authority"), i.bytes("requesterDeviceSkHex"),
                i.strings("requests").toSet(), current.number("epoch").toInt(), current.bytes("keyHex"),
                i.text("participant"), i.number("now"), i.strings("removed"),
                expected = i["expected"]?.takeIf { it != JsonNull }?.jsonPrimitive?.int,
            )
            val expected = v.child("output")["result"]
            if (expected == null || expected == JsonNull) {
                assertNull(name, decoded)
                assertEquals(name, "negative", v.text("kind"))
            } else {
                val result = expected.jsonObject
                assertNotNull(name, decoded)
                assertEquals("positive", v.text("kind"))
                assertEquals(result.number("epoch").toInt(), decoded!!.epoch.epoch)
                assertEquals(result.text("secretHex"), decoded.epoch.secret.toHex())
                assertEquals(result.strings("removed"), decoded.removed)
                assertEquals(rekeys.map { it.id }, decoded.rekeys.map { it.id })
            }
        }
    }

    @Test fun `the epoch-0 key every grant starts from is the room key of the epoch-0 rekey`() {
        val grant = vec("member-grant-accepted").child("input")
        assertEquals(vec("rekey-without-commitment").child("input").text("previousKeyHex"), grant.child("current").text("keyHex"))
        assertEquals(vec("member-request").child("input").text("roomId"), grant.text("roomId"))
    }

}
