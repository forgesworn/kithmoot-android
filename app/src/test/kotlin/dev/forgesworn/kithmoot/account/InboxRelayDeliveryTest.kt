package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.support.FakeSocket
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InboxRelayDeliveryTest {
    private val sender = LocalSigner(ByteArray(32) { 1 })
    private val recipient = LocalSigner(ByteArray(32) { 2 })
    private val now = 1_790_000_000L

    private suspend fun wrap() = NostrDirectMessages.prepare(sender, recipient.pubkey, "Hello", null, now) { true }.second.first()
    private fun FakeSocket.signedAuth(): NostrEvent =
        NostrEvent.fromJson(Json.parseToJsonElement(authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)

    @Test fun answersAnInboxRelayChallengeWithAThrowawayKeyThenOffersTheWrapAgain() = runTest {
        val sockets = FakeSocketFactory()
        val wrap = wrap()
        val result = async { deliverToInboxRelay(wrap, "wss://auth.example", sockets, { now }, { true }) }
        runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent()
        // As auth.nostr1.com does: the EVENT leaves before the challenge is answered and is refused.
        assertEquals(1, socket.publishedFrames().size)
        socket.deliverAuth("challenge-1")
        socket.deliverRaw("""["OK","${wrap.id}",false,"auth-required: you must auth"]""")
        runCurrent()
        val signed = socket.signedAuth()
        assertTrue(Events.verify(signed))
        assertNotEquals(sender.pubkey, signed.pubkey)
        assertNotEquals(wrap.pubkey, signed.pubkey)
        socket.deliverOk(signed.id, true); runCurrent()
        assertEquals(2, socket.publishedFrames().size)
        socket.deliverOk(wrap.id, true); runCurrent()
        assertTrue(result.await())
    }

    @Test fun aRefusalThatIsNotAboutAuthenticationIsNotOfferedAgain() = runTest {
        val sockets = FakeSocketFactory()
        val wrap = wrap()
        val result = async { deliverToInboxRelay(wrap, "wss://closed.example", sockets, { now }, { true }) }
        runCurrent()
        val socket = sockets.opened.single(); socket.open(); runCurrent()
        socket.deliverRaw("""["OK","${wrap.id}",false,"restricted: members only"]"""); runCurrent()
        assertFalse(result.await())
        assertEquals(1, socket.publishedFrames().size)
    }

    @Test fun eachInboxRelayIsAskedAndTheAcceptingOnesAreReported() = runTest {
        val sockets = FakeSocketFactory()
        val wrap = wrap()
        val relays = listOf("wss://open.example", "wss://closed.example")
        val result = async { deliverToInboxRelays(wrap, relays, sockets, { now }, { true }) }
        runCurrent()
        sockets.openAll(); runCurrent()
        sockets.forUrl("wss://open.example").single().deliverOk(wrap.id, true)
        sockets.forUrl("wss://closed.example").single().deliverRaw("""["OK","${wrap.id}",false,"blocked: no"]""")
        runCurrent()
        assertEquals(listOf("wss://open.example"), result.await())
    }
}
