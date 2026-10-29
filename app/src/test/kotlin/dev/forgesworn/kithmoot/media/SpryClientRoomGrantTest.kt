package dev.forgesworn.kithmoot.media

import dev.forgesworn.kithmoot.protocol.SpryKey
import dev.forgesworn.kithmoot.protocol.SpryOffer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/** What the Spry client refuses before anything leaves the phone, and how a refusal reads. */
class SpryClientRoomGrantTest {
    private val pair = SpryPairing("https://mine.example.org", "c".repeat(43), "01".repeat(32), null)
    private val now = System.currentTimeMillis() / 1000
    private val ownOffer = SpryOffer(pair.endpoint, "a".repeat(43), now + 3_600)
    private fun key(n: Int) = SpryKey.publicKeyHex(ByteArray(32) { (n + 1).toByte() })

    @Test fun `a lapsed shared relay says so, and this device's own keeps its own words`() = runBlocking {
        val sharedLapsed = SpryOffer("https://theirs.example.org", "b".repeat(43), now - 1)
        val shared = assertIs<SpryRequestException>(runCatching { SpryClient.credentials(sharedLapsed, ByteArray(32) { 3 }, pair) }.exceptionOrNull())
        assertEquals(403, shared.status)
        assertEquals(SHARED_RELAY_UNAVAILABLE, shared.message)
        // A guest with no pairing at all hears the same.
        val guest = assertIs<SpryRequestException>(runCatching { SpryClient.credentials(sharedLapsed, ByteArray(32) { 3 }, null) }.exceptionOrNull())
        assertEquals(SHARED_RELAY_UNAVAILABLE, guest.message)
        val own = assertIs<SpryRequestException>(runCatching { SpryClient.credentials(ownOffer.copy(expiresAt = now - 1), ByteArray(32) { 3 }, pair) }.exceptionOrNull())
        assertEquals("Spry call access has expired.", own.message)
    }

    @Test fun `registrations keep to the node's rules before sending`(): Unit = runBlocking {
        val k = key(1)
        assertFailsWith<IllegalArgumentException> { SpryClient.registerDevices(pair, ownOffer, SpryDevicesChange(add = listOf(k), remove = listOf(k)), 1) }
        assertFailsWith<IllegalArgumentException> { SpryClient.registerDevices(pair, ownOffer, SpryDevicesChange(add = (0 until 33).map(::key)), 1) }
        assertFailsWith<IllegalArgumentException> { SpryClient.registerDevices(pair, ownOffer, SpryDevicesChange(add = listOf(k.uppercase())), 1) }
        assertFailsWith<IllegalArgumentException> { SpryClient.registerDevices(pair, ownOffer, SpryDevicesChange(add = listOf(k)), -1) }
        assertFailsWith<IllegalArgumentException> { SpryClient.registerDevices(pair, ownOffer.copy(endpoint = "https://theirs.example.org"), SpryDevicesChange(add = listOf(k)), 1) }
        assertFailsWith<IllegalArgumentException> { SpryClient.vouch(ownOffer, ByteArray(32) { 3 }, emptyList()) }
        assertFailsWith<IllegalArgumentException> { SpryClient.vouch(ownOffer, ByteArray(32) { 3 }, (0 until 5).map(::key)) }
        assertFailsWith<IllegalArgumentException> { SpryClient.removeGrant(pair, ownOffer.copy(endpoint = "https://theirs.example.org")) }
    }
}
