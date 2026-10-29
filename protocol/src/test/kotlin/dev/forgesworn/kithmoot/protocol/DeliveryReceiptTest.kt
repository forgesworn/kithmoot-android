package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeliveryReceiptTest {
    private val now = 1_800_000_000L
    private val roomId = "a1".repeat(32)
    private val stream = DeliveryReceiptStream("c3".repeat(32), ByteArray(32) { 5 })
    private val otherStream = DeliveryReceiptStream("d4".repeat(32), ByteArray(32) { 6 })
    private val participantKey = ByteArray(32) { 1 }
    private val deviceKey = ByteArray(32) { 2 }
    private val participant = Schnorr.publicKeyHex(participantKey)

    private fun credentialFor(sk: ByteArray, room: String = roomId, key: ByteArray = participantKey) =
        createDeviceCredential(key, Schnorr.publicKeyHex(sk), room, now + 3600, createdAt = now - 60, auxRand = ByteArray(32))

    private val credential = credentialFor(deviceKey)

    private fun receipt(
        ids: List<String> = listOf("m1", "m2"),
        index: Int = 0,
        on: DeliveryReceiptStream = stream,
        sk: ByteArray = deviceKey,
        cred: NostrEvent = credential,
        createdAt: Long = now,
    ) = encodeDeliveryReceipt(
        EncodeDeliveryReceiptOptions(roomId, on, sk, cred, messageIds = ids, createdAt = createdAt, index = index),
    )

    private fun read(event: NostrEvent, streams: List<DeliveryReceiptStream> = listOf(stream, otherStream), at: Long = now) =
        decodeDeliveryReceipt(event, DecodeDeliveryReceiptOptions(roomId, streams, at))

    @Test fun `a receipt round trips, naming the person and the hashed messages`() {
        val event = receipt()
        assertEquals(KIND_DELIVERY_RECEIPT, event.kind)
        assertEquals(
            DeliveryReceipt(
                d = stream.d,
                createdAt = now,
                participant = participant,
                device = Schnorr.publicKeyHex(deviceKey),
                agent = false,
                got = listOf(deliveryReceiptIdHash("m1"), deliveryReceiptIdHash("m2")),
            ),
            read(event),
        )
    }

    @Test fun `the outer event carries only d and expiration, signed by a key that is neither device nor person`() {
        val event = receipt()
        assertEquals(listOf(listOf("d", stream.d), listOf("expiration", (now + DELIVERY_RECEIPT_TTL_SECONDS).toString())), event.tags)
        assertNotEquals(Schnorr.publicKeyHex(deviceKey), event.pubkey)
        assertNotEquals(participant, event.pubkey)
        assertFalse(event.content.contains(Schnorr.publicKeyHex(deviceKey)))
    }

    @Test fun `the outer key differs per stream, second and index, and the device can derive it again`() {
        val a = receipt()
        val b = receipt(index = 1)
        val c = receipt(createdAt = now + 1)
        val d = receipt(on = otherStream)
        assertEquals(4, setOf(a.pubkey, b.pubkey, c.pubkey, d.pubkey).size)
        assertEquals(b.pubkey, Schnorr.publicKeyHex(deliveryReceiptOuterSecretKey(deviceKey, stream.d, now, 1)))
    }

    @Test fun `duplicate ids collapse and an agent flag survives`() {
        val event = encodeDeliveryReceipt(
            EncodeDeliveryReceiptOptions(roomId, stream, deviceKey, credential, agent = true, messageIds = listOf("m1", "m1"), createdAt = now),
        )
        val decoded = read(event)!!
        assertEquals(listOf(deliveryReceiptIdHash("m1")), decoded.got)
        assertTrue(decoded.agent)
    }

    @Test fun `a conference room's end lowers the expiration`() {
        val event = encodeDeliveryReceipt(
            EncodeDeliveryReceiptOptions(roomId, stream, deviceKey, credential, messageIds = listOf("m1"), createdAt = now, roomEnds = now + 100),
        )
        assertEquals((now + 100).toString(), event.tagValue("expiration"))
        assertNotNull(read(event))
    }

    @Test fun `a stream the reader does not follow is refused`() {
        assertNull(read(receipt(), streams = listOf(otherStream)))
    }

    @Test fun `stale and too-new receipts are refused`() {
        val event = receipt()
        assertNull(read(event, at = now - 61))
        assertNotNull(read(event, at = now - 60))
        assertNull(read(event, at = now + DELIVERY_RECEIPT_TTL_SECONDS + 1))
    }

    @Test fun `the encoder refuses no ids, too many ids, and a bad time`() {
        for (ids in listOf(emptyList(), List(MAX_DELIVERY_RECEIPT_IDS + 1) { "m$it" })) {
            try {
                receipt(ids = ids)
                fail("expected a refusal for ${ids.size} ids")
            } catch (_: IllegalArgumentException) {
            }
        }
        assertNotNull(read(receipt(ids = List(MAX_DELIVERY_RECEIPT_IDS) { "m$it" })))
        try {
            receipt(createdAt = -1)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `the encoder refuses a credential that does not name this device or this room`() {
        val otherDevice = ByteArray(32) { 9 }
        for (bad in listOf(credentialFor(otherDevice), credentialFor(deviceKey, room = "b2".repeat(32)))) {
            try {
                receipt(cred = bad)
                fail("expected a refusal")
            } catch (_: IllegalArgumentException) {
            }
        }
        val expired = createDeviceCredential(participantKey, Schnorr.publicKeyHex(deviceKey), roomId, now - 1, createdAt = now - 60)
        try {
            receipt(cred = expired)
            fail("expected a refusal")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `own receipts are found with the keys that signed them, and another device's are ignored`() {
        val otherKey = ByteArray(32) { 7 }
        val theirs = receipt(sk = otherKey, cred = credentialFor(otherKey))
        val mine = listOf(receipt(), receipt(index = 3), receipt(index = 7), receipt(on = otherStream))
        val wrongKind = theirs.copy(kind = 1)

        val found = ownDeliveryReceipts(mine + theirs + wrongKind, listOf(stream, otherStream), deviceKey)
        assertEquals(mine.map { it.id }, found.map { it.event.id })
        for (own in found) {
            assertEquals(own.event.pubkey, Schnorr.publicKeyHex(own.secretKey))
            own.secretKey.fill(0)
        }
        assertEquals(mine.size - 1, ownDeliveryReceipts(mine, listOf(stream), deviceKey).onEach { it.secretKey.fill(0) }.size)
    }

    @Test fun `a receipt beyond the searched indices is not claimed`() {
        val tooHigh = receipt(index = MAX_DELIVERY_RECEIPT_INDEX)
        assertTrue(ownDeliveryReceipts(listOf(tooHigh), listOf(stream), deviceKey).isEmpty())
    }

    @Test fun `own receipt search never throws on rubbish`() {
        val junk = listOf(
            NostrEvent(KIND_DELIVERY_RECEIPT, now, emptyList(), "", "00", "00", "00"),
            receipt().copy(content = "not nip44"),
        )
        assertTrue(ownDeliveryReceipts(junk, listOf(stream), deviceKey).isEmpty())
        assertNull(read(junk[0]))
        assertNull(read(junk[1]))
    }

    @Test fun `key derivations are deterministic and domain separated`() {
        assertArrayEquals(deliveryReceiptKey(stream.key), deliveryReceiptKey(stream.key))
        assertFalse(deliveryReceiptKey(stream.key).contentEquals(stream.key))
        assertEquals(16, deliveryReceiptIdHash("anything").length)
        assertTrue(Entropy.bytes(32).size == 32)
    }
}
