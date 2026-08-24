package dev.forgesworn.kithmoot.protocol

import dev.forgesworn.kithmoot.crypto.Schnorr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Staleness, the first of the three NIP-AC rules the design says signalling
 * reuses. The other two live in [SignalGuard].
 */
class SignalStalenessTest {

    private val senderSecretKey = ByteArray(32) { (it + 1).toByte() }
    private val recipientSecretKey = ByteArray(32) { (it + 40).toByte() }
    private val recipient = Schnorr.publicKeyHex(recipientSecretKey)
    private val roomId = "d".repeat(64)
    private val sentAt = 1_700_000_000L

    private fun wrap(): NostrEvent = wrapSignal(
        body = SignalBody(type = "offer", roomId = roomId, sdp = "v=0"),
        senderSecretKey = senderSecretKey,
        recipientPubkey = recipient,
        createdAt = sentAt,
    ).wrap

    @Test
    fun `a fresh signal is accepted`() {
        assertNotNull(unwrapSignal(wrap(), recipientSecretKey, roomId, now = sentAt))
        assertNotNull(unwrapSignal(wrap(), recipientSecretKey, roomId, now = sentAt + SIGNAL_MAX_AGE_SECONDS - 1))
    }

    @Test
    fun `BUG (I5)- a signal older than the staleness window is refused`() {
        // A hostile or simply buggy relay re-delivering a captured wrap must
        // not force a renegotiation nobody asked for.
        assertNull(unwrapSignal(wrap(), recipientSecretKey, roomId, now = sentAt + SIGNAL_MAX_AGE_SECONDS + 1))
    }

    @Test
    fun `BUG (I5)- a signal stamped too far in the future is refused`() {
        // The window is symmetric, so a sender cannot mint a wrap that stays
        // acceptable for ever by stamping it years ahead.
        assertNull(unwrapSignal(wrap(), recipientSecretKey, roomId, now = sentAt - SIGNAL_MAX_AGE_SECONDS - 1))
    }

    @Test
    fun `the window matches the TypeScript reference`() {
        assertEquals(20L, SIGNAL_MAX_AGE_SECONDS)
    }
}
