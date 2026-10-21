package dev.forgesworn.kithmoot.vectors

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.EpochGrant
import dev.forgesworn.kithmoot.protocol.EpochKeys
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.KIND_EPOCH_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_ROOM_REKEY
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.RoomEpoch
import dev.forgesworn.kithmoot.protocol.decodeEpochGrant
import dev.forgesworn.kithmoot.protocol.decodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.encodeEpochGrant
import dev.forgesworn.kithmoot.protocol.encodeRekeyEvent
import dev.forgesworn.kithmoot.protocol.isSealPubkey
import dev.forgesworn.kithmoot.protocol.sealSender
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fold-kit's `vectors/seal-vectors.json`, copied byte for byte (never edited), for the part of it
 * this client reads: the `from` tag on a rekey (1462) and an authority's grant (20469) that names
 * the one-time key the copies were sealed from (`sealSender`).
 *
 * This client does not mint seal keys, so its readers open with the device key. The vectors whose
 * copy is sealed to a seal key it cannot hold (`credential-seal-*`, `rekey-seal`,
 * `epoch-grant-seal`, `member-grant-seal`, and the `renewedDevice` and `thief` readings of the
 * one-time vectors) are counted but not run. What is run:
 * - `rekey-one-time-sender`: `sealSender`, the `bareDevice` copy (sealed from the one-time key to
 *   the bare device key) and that the authority's own key opens none of the copies;
 * - `rekey-from-doubled` and `rekey-from-off-curve`: no sender, so the rekey is read for its
 *   epoch without a secret;
 * - `epoch-grant-one-time-sender`: `sealSender` and that the signer's key no longer opens it;
 * - `epoch-grant-from-off-curve`: refused.
 *
 * The recorded negatives would also be refused by a copy this reader cannot open, so the hand-built
 * cases below put the `from` tag alone in the way: the same events sealed to the device key.
 */
class SealVectorsTest {
    private val root: JsonObject by lazy {
        val stream = requireNotNull(javaClass.getResourceAsStream("/seal-vectors.json")) { "seal-vectors.json is missing" }
        Json.parseToJsonElement(stream.bufferedReader().use { it.readText() }).jsonObject
    }
    private val vectors: List<JsonObject> get() = root.child("groups").list("seal").map { it.jsonObject }
    private fun vec(name: String): JsonObject = vectors.single { it.text("name") == name }
    private fun event(value: JsonObject): NostrEvent = NostrEvent.fromJson(value)
    private fun keys(roomId: String, epoch: Int, key: ByteArray) = EpochKeys(epoch, roomId, key)

    @Test fun `the file is in the KithMoot vector format and every vector is counted`() {
        assertEquals("kithmoot/v1", root.text("protocolVersion"))
        assertEquals(setOf("seal"), root.child("groups").keys)
        assertEquals(
            listOf(
                "credential-seal-key", "credential-without-seal", "credential-seal-doubled", "credential-seal-off-curve",
                "newer-credential", "rekey-seal", "rekey-without-seal-keys", "epoch-grant-seal", "member-grant-seal",
                "rekey-one-time-sender", "rekey-from-doubled", "rekey-from-off-curve",
                "epoch-grant-one-time-sender", "epoch-grant-from-off-curve",
            ),
            vectors.map { it.text("name") },
        )
        assertEquals(
            listOf("credential-seal-doubled", "credential-seal-off-curve", "rekey-from-doubled", "rekey-from-off-curve", "epoch-grant-from-off-curve"),
            vectors.filter { it.text("kind") == "negative" }.map { it.text("name") },
        )
        for (v in vectors) {
            assertTrue(v.text("kind") in setOf("positive", "negative"))
            v.text("note")
        }
    }

    @Test fun `a rekey from a one-time key names it, opens for the bare device, and the authority key opens nothing`() {
        val v = vec("rekey-one-time-sender")
        val i = v.child("input")
        val out = v.child("output")
        val rekey = event(i.child("event"))
        assertTrue(Events.verify(rekey))
        assertEquals(i.text("authority"), rekey.pubkey)
        assertEquals(listOf("d", "epoch", "from"), rekey.tags.map { it[0] })

        val sender = sealSender(rekey)
        assertEquals(out.text("sealSender"), sender)
        assertEquals(i.text("senderSkHex").hexToBytes().let(Schnorr::publicKeyHex), sender)
        assertNotEquals("the sender is not the signer", rekey.pubkey, sender)

        // Opened with the key the tag names, never the signer's: sealed from the one-time key to the bare device key.
        val bare = out.child("bareDevice")
        val notice = decodeRekeyEvent(rekey, i.text("roomId"), i.text("authority"), keys(i.text("roomId"), 0, i.bytes("previousKeyHex")), i.bytes("bareDeviceSkHex"))
        assertNotNull(notice)
        assertEquals(bare.number("epoch").toInt(), notice!!.epoch)
        assertEquals(bare.text("secretHex"), notice.secret!!.toHex())

        // Forward secrecy: the authority's own key opens none of the copies.
        val body = Json.parseToJsonElement(Nip44.decrypt(rekey.content, i.bytes("previousKeyHex"))).jsonObject
        val authoritySk = i.bytes("authoritySkHex")
        val opens = out.child("authorityOpens")
        assertEquals(2, opens.size)
        for ((device, expected) in opens) {
            val copy = body.child("keys").text(device)
            val opened = runCatching { Nip44.decrypt(copy, Nip44.conversationKey(authoritySk, device.hexToBytes())) }.isSuccess
            assertEquals("authority opens the copy for $device", expected.jsonPrimitive.content.toBoolean(), opened)
        }
    }

    @Test fun `a rekey with a doubled or an off-curve from tag is read for its epoch, without a secret`() {
        for (name in listOf("rekey-from-doubled", "rekey-from-off-curve")) {
            val i = vec(name).child("input")
            val rekey = event(i.child("event"))
            assertTrue(name, Events.verify(rekey))
            assertEquals(name, i.text("authority"), rekey.pubkey)
            assertNull("$name sealSender", sealSender(rekey))
            assertTrue(vec(name).child("output").isNull("sealSender"))

            val want = vec(name).child("output").child("renewedDevice")
            assertFalse("no secret is recorded", "secretHex" in want)
            val notice = decodeRekeyEvent(rekey, i.text("roomId"), i.text("authority"), keys(i.text("roomId"), 0, i.bytes("previousKeyHex")), i.bytes("deviceSkHex"))
            assertNotNull("$name is still read", notice)
            assertEquals(name, want.number("epoch").toInt(), notice!!.epoch)
            assertTrue(name, notice.removed.isEmpty())
            assertNull("$name yields no secret", notice.secret)
        }
    }

    @Test fun `a grant from a one-time key names it, and the signer's key no longer opens it`() {
        val v = vec("epoch-grant-one-time-sender")
        val i = v.child("input")
        val grant = event(i.child("event"))
        assertTrue(Events.verify(grant))
        assertEquals(KIND_EPOCH_GRANT, grant.kind)
        assertEquals(listOf("d", "p", "from"), grant.tags.map { it[0] })

        val sender = sealSender(grant)
        assertEquals(v.child("output").text("sealSender"), sender)
        assertEquals(i.text("senderSkHex").hexToBytes().let(Schnorr::publicKeyHex), sender)
        assertNotEquals(grant.pubkey, sender)
        assertFalse(v.child("output").flag("authorityOpens"))
        val device = Schnorr.publicKeyHex(i.bytes("deviceSkHex"))
        assertTrue("the signer's key does not open the grant", runCatching { Nip44.decrypt(grant.content, Nip44.conversationKey(i.bytes("authoritySkHex"), device.hexToBytes())) }.isFailure)
        // Sealed to a seal key this client does not hold, so its device-key reader opens nothing here: see the hand-built grants.
    }

    @Test fun `a grant with an off-curve from tag is refused`() {
        val v = vec("epoch-grant-from-off-curve")
        val i = v.child("input")
        val grant = event(i.child("event"))
        assertTrue(Events.verify(grant))
        assertNull(sealSender(grant))
        assertTrue(v.child("output").isNull("renewedDevice"))
        assertNull(decodeEpochGrant(grant, i.text("roomId"), i.text("authority"), i.bytes("deviceSkHex"), i.text("request"), i.number("now")))
    }

    @Test fun `sealSender is the signer with no from tag, the tag with one, null otherwise`() {
        val signer = "4fca1ba4b39e24ea0acea325af3e6a62315903952c277e197cccfe8f0143c533"
        val one = "02f71d0f7235cbe6e623bc1542539c9db10601a3a892af483783614c00c7fd99"
        fun of(vararg tags: List<String>, pubkey: String = signer) = sealSender(NostrEvent(1462, 0, tags.toList(), "", pubkey, "0".repeat(64), "0".repeat(128)))
        assertEquals(signer, of(listOf("d", "x")))
        assertEquals("upper-case signer is lower-cased", signer, of(pubkey = signer.uppercase()))
        assertEquals(one, of(listOf("from", one)))
        assertEquals("upper-case tag is lower-cased", one, of(listOf("d", "x"), listOf("from", one.uppercase())))
        assertEquals("the signer's own key is a valid sender", signer, of(listOf("from", signer)))
        assertEquals("tag names are exact", signer, of(listOf("From", one)))
        assertNull("two from tags", of(listOf("from", one), listOf("from", one)))
        assertNull("two from tags, one the signer", of(listOf("from", one), listOf("from", signer)))
        assertNull("x = 5 is on no point", of(listOf("from", "0".repeat(63) + "5")))
        assertNull("x at the field size", of(listOf("from", "f".repeat(55) + "efffffc2f")))
        assertNull("not hex", of(listOf("from", "zz".repeat(32))))
        assertNull("too short", of(listOf("from", one.dropLast(2))))
        assertNull("too long", of(listOf("from", one + "00")))
        assertNull("empty", of(listOf("from", "")))
        assertNull("no value", of(listOf("from")))
        assertTrue(isSealPubkey(one))
        assertFalse(isSealPubkey(null))
    }

    // The same events with the copies sealed to the device key, so the `from` tag alone decides.

    private fun seed(n: Int) = ByteArray(32) { (n + it).toByte() }
    private val roomId = "58c0b5a427b80559f5b67f35e74c686f630259dcdafca2ab03e3b082f4a1740c"
    private val authoritySk = seed(1)
    private val authority = Schnorr.publicKeyHex(authoritySk)
    private val deviceSk = seed(40)
    private val device = Schnorr.publicKeyHex(deviceSk)
    private val oneTimeSk = seed(80)
    private val oneTime = Schnorr.publicKeyHex(oneTimeSk)
    private val now = 1_800_000_000L
    private val offCurve = "0".repeat(63) + "5"

    private fun key(sk: ByteArray, peer: String) = Nip44.conversationKey(sk, peer.hexToBytes())

    /** A real grant, resealed from [sealFrom] to the device key and signed by [signer] with [tags] after d and p. */
    private fun grant(extra: List<List<String>>, sealFrom: ByteArray = oneTimeSk, signer: ByteArray = authoritySk, request: String = "ab".repeat(32)): NostrEvent {
        val real = encodeEpochGrant(roomId, authoritySk, device, request, now, epoch = RoomEpoch(2, seed(120)))
        val plaintext = Nip44.decrypt(real.content, key(authoritySk, device))
        return Events.sign(signer, KIND_EPOCH_GRANT, now, listOf(listOf("d", roomId), listOf("p", device)) + extra, Nip44.encrypt(plaintext, key(sealFrom, device)))
    }

    private fun readGrant(grant: NostrEvent) = decodeEpochGrant(grant, roomId, authority, deviceSk, "ab".repeat(32), now)

    @Test fun `a grant sealed from a one-time key to the device key is read through its from tag`() {
        val read = readGrant(grant(listOf(listOf("from", oneTime)))) as EpochGrant.Current
        assertEquals(2, read.epoch)
        assertArrayEquals(seed(120), read.secret)
        assertTrue("upper-case tag", readGrant(grant(listOf(listOf("from", oneTime.uppercase())))) is EpochGrant.Current)
        // The grant a client wrote before one-time senders: no tag, sealed from the signer.
        val legacy = encodeEpochGrant(roomId, authoritySk, device, "ab".repeat(32), now, epoch = RoomEpoch(2, seed(120)))
        assertEquals(listOf("d", "p"), legacy.tags.map { it[0] })
        assertTrue(readGrant(legacy) is EpochGrant.Current)
    }

    @Test fun `a grant whose from tag is missing, doubled, off the curve or not the sealing key is refused`() {
        assertNull("sealed from a one-time key, no tag: opened with the signer's key", readGrant(grant(emptyList())))
        assertNull("two from tags", readGrant(grant(listOf(listOf("from", oneTime), listOf("from", oneTime)))))
        assertNull("off the curve", readGrant(grant(listOf(listOf("from", offCurve)))))
        assertNull("no value", readGrant(grant(listOf(listOf("from")))))
        assertNull("a tag that is not the sealing key", readGrant(grant(listOf(listOf("from", Schnorr.publicKeyHex(seed(100)))))))
        assertNull("the tag is the authority's word: sealed from the signer, tag names another", readGrant(grant(listOf(listOf("from", oneTime)), sealFrom = authoritySk)))
        assertNull("signed by somebody who is not the authority", readGrant(grant(listOf(listOf("from", oneTime)), signer = seed(60))))
    }

    /** A real rekey with the device's copy resealed from [sealFrom], signed by [signer] with [extra] tags after d and epoch. */
    private fun rekey(extra: List<List<String>>, sealFrom: ByteArray = oneTimeSk, signer: ByteArray = authoritySk): NostrEvent {
        val real = encodeRekeyEvent(roomId, authoritySk, current, RoomEpoch(1, seed(120)), listOf(device), emptyList(), now)
        val body = Json.parseToJsonElement(Nip44.decrypt(real.content, current.key)).jsonObject
        val sealed = Nip44.decrypt(body.child("keys").text(device), key(authoritySk, device))
        val resealed = buildJsonObject {
            for ((k, v) in body) if (k == "keys") put(k, buildJsonObject { put(device, JsonPrimitive(Nip44.encrypt(sealed, key(sealFrom, device)))) }) else put(k, v)
        }
        return Events.sign(signer, KIND_ROOM_REKEY, now, listOf(listOf("d", roomId), listOf("epoch", "1")) + extra, Nip44.encrypt(resealed.toString(), current.key))
    }

    private val current = EpochKeys(0, roomId, seed(20))

    private fun readRekey(rekey: NostrEvent, reader: ByteArray = deviceSk) = decodeRekeyEvent(rekey, roomId, authority, current, reader)

    @Test fun `a rekey sealed from a one-time key to the device key is read through its from tag`() {
        val notice = requireNotNull(readRekey(rekey(listOf(listOf("from", oneTime)))))
        assertEquals(1, notice.epoch)
        assertArrayEquals(seed(120), notice.secret)
        assertArrayEquals("upper-case tag", seed(120), readRekey(rekey(listOf(listOf("from", oneTime.uppercase()))))!!.secret)
        val legacy = encodeRekeyEvent(roomId, authoritySk, current, RoomEpoch(1, seed(120)), listOf(device), emptyList(), now)
        assertEquals(listOf("d", "epoch"), legacy.tags.map { it[0] })
        assertArrayEquals(seed(120), readRekey(legacy)!!.secret)
    }

    @Test fun `a rekey whose from tag is doubled or off the curve is still read, without a secret`() {
        for ((what, extra) in listOf(
            "two from tags" to listOf(listOf("from", oneTime), listOf("from", oneTime)),
            "off the curve" to listOf(listOf("from", offCurve)),
            "no value" to listOf(listOf("from")),
        )) {
            val notice = readRekey(rekey(extra))
            assertNotNull("$what is still read", notice)
            assertEquals(what, 1, notice!!.epoch)
            assertTrue(what, notice.removed.isEmpty())
            assertNull("$what yields no secret", notice.secret)
        }
        assertNull("not signed by the authority, whatever it says", readRekey(rekey(listOf(listOf("from", oneTime)), signer = seed(60))))
    }
}
