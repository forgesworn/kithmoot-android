package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Nip44
import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RekeySizingTest {
    private fun key(seed: Int) = ByteArray(32).apply { this[31] = seed.toByte() }
    private val root = key(1)
    private val authority = Schnorr.publicKeyHex(root)
    private val room = deriveRoom(key(2)).roomId
    private fun assertFails(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun nip44PredictionMatchesActualFramingAcrossPaddingEdgesAndUtf8() {
        val lengths = listOf(1, 2, 31, 32, 33, 63, 64, 65, 127, 128, 129, 255, 256, 257,
            511, 512, 513, 1023, 1024, 1025, 65534, 65535)
        for (length in lengths) assertEquals("plaintext bytes=$length",
            Nip44.encrypt("a".repeat(length), key(3), key(4)).length, Nip44.encodedLength(length))
        val unicode = "é🧭英国"
        assertEquals(Nip44.encrypt(unicode, key(3), key(4)).length,
            Nip44.encodedLength(unicode.toByteArray(Charsets.UTF_8).size))
        assertFails { Nip44.encodedLength(0) }; assertFails { Nip44.encodedLength(-1) }
        assertFails { Nip44.encodedLength(65536) }; assertFails { Nip44.encodedLength(Int.MAX_VALUE) }
    }

    private fun compare(count: Int, removedCount: Int = 0, epoch: Int = 1, now: Long = 0,
        closed: Boolean = false, destruct: Boolean = false, scheduled: Boolean = false,
        commitment: Boolean = true, memberList: Boolean = true, by: String? = null) {
        val recipients = (10 until 10 + count).map { Schnorr.publicKeyHex(key(it)) }
        val gone = (80 until 80 + removedCount).map { Schnorr.publicKeyHex(key(it)) }
        // Public participant/device keys are deliberately independent lists.
        val members = if (memberList) recipients + gone else null
        val current = deriveEpoch(RoomEpoch(epoch - 1, key(2)))
        val next = RoomEpoch(epoch, key(3))
        try {
            val predicted = rekeyEventBytes(room, authority, epoch, recipients, gone, now, by, closed,
                commitment, members, scheduled, destruct)
            val actual = encodeRekeyEvent(room, root, current, next, recipients, gone, now, by, closed,
                commitment, members, scheduled, destruct,
                recipientNonces = recipients.associateWith { key(4) }, bodyNonce = key(5), auxRand = key(6))
            assertTrue(Events.verify(actual))
            assertEquals(actual.toCompactJson().toByteArray(Charsets.UTF_8).size, predicted)
            println("REKEY_UNSIGNED_SIZE case_devices=$count removed=$removedCount epoch=$epoch time=$now closed=$closed bytes=$predicted")
        } finally { current.key.fill(0); next.secret.fill(0) }
    }

    @Test fun oneAndThirtyTwoActualDeviceSealsMatchUnsignedSize() { compare(1); compare(32) }
    @Test fun removalAudienceNearNativeHistoricalCapacityMatchesAndExceedsEventBound() {
        compare(32, 96)
        val devices = (10..41).map { Schnorr.publicKeyHex(key(it)) }
        val gone = (80..175).map { Schnorr.publicKeyHex(key(it)) }
        assertTrue(rekeyEventBytes(room, authority, 1, devices, gone, 0, commit = true, members = devices + gone) > 16 * 1024)
    }
    @Test fun closureAndDestructionHaveNoDeviceSealsAndMatchActualEncoding() {
        compare(32, 96, closed = true); compare(32, 96, closed = true, destruct = true)
    }
    @Test fun epochTimeAndOptionalFieldWidthsMatchActualEncoding() {
        compare(1, epoch = 10, now = 10, scheduled = true)
        compare(2, epoch = MAX_EPOCH, now = 9_007_199_254_740_991L, by = authority)
        compare(0, commitment = false, memberList = false)
    }
    @Test fun canonicalDuplicateAndUppercaseFieldsMatchActualEncoding() {
        val device = Schnorr.publicKeyHex(key(10)); val member = Schnorr.publicKeyHex(key(11))
        val recipients = listOf(device, device.uppercase())
        val gone = listOf(member, member.uppercase())
        val current = deriveEpoch(RoomEpoch(0, key(2))); val next = RoomEpoch(1, key(3))
        try {
            val predicted = rekeyEventBytes(room.uppercase(), authority.uppercase(), 1, recipients, gone, 0,
                commit = true, members = listOf(member, member.uppercase(), device))
            val actual = encodeRekeyEvent(room.uppercase(), root, current, next, recipients, gone, 0,
                commit = true, members = listOf(member, member.uppercase(), device),
                bodyNonce = key(5), auxRand = key(6))
            assertEquals(actual.toCompactJson().toByteArray(Charsets.UTF_8).size, predicted)
        } finally { current.key.fill(0); next.secret.fill(0) }
    }
    @Test fun malformedFieldsAndContradictoryFlagsRefuseUnsignedSizing() {
        assertFails { rekeyEventBytes("bad", authority, 1, emptyList(), emptyList(), 0) }
        assertFails { rekeyEventBytes(room, "bad", 1, emptyList(), emptyList(), 0) }
        assertFails { rekeyEventBytes(room, authority, 0, emptyList(), emptyList(), 0) }
        assertFails { rekeyEventBytes(room, authority, MAX_EPOCH + 1, emptyList(), emptyList(), 0) }
        assertFails { rekeyEventBytes(room, authority, 1, listOf("bad"), emptyList(), 0) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), listOf("bad"), 0) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), emptyList(), 0, by = "bad") }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), emptyList(), 0, members = listOf("bad")) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), listOf(authority), 0, scheduled = true) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), emptyList(), 0, closed = true, scheduled = true) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), emptyList(), 0, destruct = true) }
        assertFails { rekeyEventBytes(room, authority, 1, emptyList(), List(1000) { it.toString(16).padStart(64, '0') }, 0) }
    }
}
