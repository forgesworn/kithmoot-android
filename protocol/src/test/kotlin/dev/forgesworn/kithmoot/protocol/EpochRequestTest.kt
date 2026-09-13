package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpochRequestTest {
    private val now = 1_800_000_000L
    private val roomSecret = "07".repeat(32).hexToBytes()
    private val room = deriveRoom(roomSecret)
    private val authoritySecret = "08".repeat(32).hexToBytes()
    private val authority = Schnorr.publicKeyHex(authoritySecret)
    private val participantSecret = "09".repeat(32).hexToBytes()
    private val deviceSecret = "0a".repeat(32).hexToBytes()
    private val device = Schnorr.publicKeyHex(deviceSecret)
    private val credential = createDeviceCredential(participantSecret, device, room.roomId, now + 3_600, now, ByteArray(32))

    @Test fun `the authority creates a readable successor rekey and a closed rekey seals no secret`() {
        val current = deriveEpoch(RoomEpoch(0, roomSecret))
        val nextSecret = "0c".repeat(32).hexToBytes()
        val event = encodeRekeyEvent(
            room.roomId, authoritySecret, current, RoomEpoch(1, nextSecret),
            listOf(device), listOf("0d".repeat(32)), now,
            by = "0e".repeat(32),
            recipientNonces = mapOf(device to ByteArray(32) { 13 }),
            bodyNonce = ByteArray(32) { 14 }, auxRand = ByteArray(32) { 15 },
        )
        val decoded = decodeRekeyEvent(event, room.roomId, authority, current, deviceSecret)!!
        assertEquals(1, decoded.epoch)
        assertArrayEquals(nextSecret, decoded.secret)
        assertEquals(listOf("0d".repeat(32)), decoded.removed)
        assertEquals("0e".repeat(32), decoded.by)

        val closed = encodeRekeyEvent(
            room.roomId, authoritySecret, current, RoomEpoch(1, nextSecret),
            listOf(device), emptyList(), now, closed = true,
            recipientNonces = mapOf(device to ByteArray(32) { 16 }),
            bodyNonce = ByteArray(32) { 17 }, auxRand = ByteArray(32) { 18 },
        )
        val terminal = decodeRekeyEvent(closed, room.roomId, authority, current, deviceSecret)!!
        assertTrue(terminal.closed)
        assertNull(terminal.secret)
    }

    @Test fun `a credential-bound device asks the authority and malformed or stale requests fail closed`() {
        val request = encodeEpochRequest(
            room.roomId, authority, deviceSecret, credential, now,
            nonce = ByteArray(32) { 1 }, auxRand = ByteArray(32) { 2 },
        )
        val decoded = decodeEpochRequest(request, room.roomId, authoritySecret, now)
        assertEquals(device, decoded?.device)
        assertEquals(credential.pubkey, decoded?.participant)
        assertEquals(request.id, decoded?.request)
        assertNull(decodeEpochRequest(request, room.roomId, authoritySecret, now + EPOCH_MAX_AGE_SECONDS + 1))
        assertNull(decodeEpochRequest(request.copy(tags = listOf(listOf("d", "ff".repeat(32)), listOf("p", authority))), room.roomId, authoritySecret, now))

        val borrowed = encodeEpochRequest(
            room.roomId, authority, "0b".repeat(32).hexToBytes(), credential, now,
            nonce = ByteArray(32) { 3 }, auxRand = ByteArray(32) { 4 },
        )
        assertNull(decodeEpochRequest(borrowed, room.roomId, authoritySecret, now))
    }

    @Test fun `the authority grants the current epoch or returns a terminal refusal to this request only`() {
        val request = encodeEpochRequest(
            room.roomId, authority, deviceSecret, credential, now,
            nonce = ByteArray(32) { 5 }, auxRand = ByteArray(32) { 6 },
        )
        val nextSecret = "0c".repeat(32).hexToBytes()
        val removed = listOf("0d".repeat(32), "0d".repeat(32), "0e".repeat(32))
        val grant = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            RoomEpoch(2, nextSecret), removed,
            nonce = ByteArray(32) { 7 }, auxRand = ByteArray(32) { 8 },
        )
        val decoded = decodeEpochGrant(grant, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(2, decoded.epoch)
        assertArrayEquals(nextSecret, decoded.secret)
        assertEquals(removed.distinct().sorted(), decoded.removed)
        assertNull(decodeEpochGrant(grant, room.roomId, authority, deviceSecret, "ff".repeat(32), now))

        val refusal = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            refused = "removed", nonce = ByteArray(32) { 9 }, auxRand = ByteArray(32) { 10 },
        )
        assertEquals(EpochGrant.Refused("removed"), decodeEpochGrant(refusal, room.roomId, authority, deviceSecret, request.id, now))

        val unchanged = encodeEpochGrant(
            room.roomId, authoritySecret, device, request.id, now,
            epoch = RoomEpoch(0, roomSecret), nonce = ByteArray(32) { 11 }, auxRand = ByteArray(32) { 12 },
        )
        val zero = decodeEpochGrant(unchanged, room.roomId, authority, deviceSecret, request.id, now) as EpochGrant.Current
        assertEquals(0, zero.epoch)
        assertNull(zero.secret)
        assertTrue(zero.removed.isEmpty())
    }
}
