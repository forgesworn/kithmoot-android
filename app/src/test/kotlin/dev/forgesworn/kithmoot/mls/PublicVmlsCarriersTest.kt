package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.AccountNetworkMode
import dev.forgesworn.kithmoot.account.AccountNetworkPolicy
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Runs the app's actual public invitation/request factories and RelayPool, replacing only OS sockets. */
@OptIn(ExperimentalCoroutinesApi::class)
class PublicVmlsCarriersTest {
    private val relay = "wss://keeper.example"
    private val identity = LocalSigner(ByteArray(32) { 7 })
    private fun policy(default: String) = AccountNetworkPolicy(default, { null }, {})
    private val directory = listOf(Filter(kinds = listOf(10050), authors = listOf(identity.pubkey), limit = 8))
    private suspend fun wrap() = identity.sign(1059, 1_900_000_000, listOf(listOf("p", identity.pubkey)), "encrypted test request")
    private class Signer(private val actual: ParticipantSigner, private val release: CompletableDeferred<Unit>? = null) : ParticipantSigner by actual {
        var authCalls = 0
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
            check(kind == 22242)
            authCalls++
            release?.await()
            return actual.sign(kind, createdAt, tags, content)
        }
    }

    @Test fun privateRequestCannotOpenAuthenticateSubscribeOrPublish() = runTest {
        val policy = policy("private-invitations")
        val sockets = FakeSocketFactory()
        val signer = Signer(identity)
        val factory = PublicVmlsCarriers(policy, backgroundScope, sockets)
        for (create in listOf<() -> VmlsCarrier>({ factory.invitations(listOf(relay)) },
            { factory.requests(listOf(relay), null) }, { factory.requests(listOf(relay), signer) })) {
            val carrier = create()
            try {
                val reader = backgroundScope.launch { carrier.subscribe(directory).collect {} }
                val sent = async { carrier.publish(wrap()) }
                runCurrent(); advanceTimeBy(25_000); runCurrent()
                assertFalse(sent.await())
                assertTrue(sockets.opened.isEmpty(), "Private mode must refuse before the OS socket factory")
                assertEquals(0, signer.authCalls)
                reader.cancel()
            } finally { carrier.close() }
        }
    }

    @Test fun explicitNostrChoicePermitsDirectoryAuthAndConfirmedRequest() = runTest {
        val policy = policy("private-invitations")
        val sockets = FakeSocketFactory()
        val signer = Signer(identity)
        val factory = PublicVmlsCarriers(policy, backgroundScope, sockets)
        policy.set(AccountNetworkMode.NOSTR)
        val carrier = factory.requests(listOf(relay), signer)
        try {
            val reader = backgroundScope.launch { carrier.subscribe(directory).collect {} }
            runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent()
            assertTrue(socket.sent.any { it.startsWith("[\"REQ\"") && it.contains("10050") && it.contains(identity.pubkey) })
            socket.deliverAuth("keeper challenge"); runCurrent()
            assertEquals(1, signer.authCalls)
            val auth = NostrEvent.fromJson(Json.parseToJsonElement(socket.authFrames().single().substringAfter("[\"AUTH\",").dropLast(1)).jsonObject)
            assertEquals(22242, auth.kind)
            assertEquals(identity.pubkey, auth.pubkey)
            socket.deliverOk(auth.id, true); runCurrent()
            val event = wrap(); val sent = async { carrier.publish(event) }; runCurrent()
            assertEquals(1, socket.publishedFrames().size)
            socket.deliverOk(event.id, true); runCurrent()
            assertTrue(sent.await())
            reader.cancel()
        } finally { carrier.close() }
    }

    @Test fun invitationCarrierPublishesOnlyUnderItsOriginalNostrGeneration() = runTest {
        val policy = policy("nostr")
        val sockets = FakeSocketFactory()
        val factory = PublicVmlsCarriers(policy, backgroundScope, sockets)
        val carrier = factory.invitations(listOf(relay))
        try {
            runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent()
            val event = wrap(); val sent = async { carrier.publish(event) }; runCurrent()
            assertEquals(1, socket.publishedFrames().size)
            socket.deliverOk(event.id, true); runCurrent(); assertTrue(sent.await())
            val before = socket.sent.toList()
            policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
            assertTrue(socket.closedByPool)
            // Drop only after revocation: retry jitter cannot open another permitted socket first.
            socket.drop(); runCurrent()
            val queued = async { carrier.publish(wrap()) }; runCurrent()
            advanceTimeBy(60_000); runCurrent()
            assertFalse(queued.await())
            policy.set(AccountNetworkMode.NOSTR)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, sockets.opened.size, "Stale invitation carrier cannot reconnect")
            assertEquals(before, socket.sent)
            val fresh = factory.invitations(listOf(relay))
            try { runCurrent(); assertEquals(2, sockets.opened.size) } finally { fresh.close() }
        } finally { carrier.close() }
    }

    @Test fun privateTransitionClosesSocketAndBlocksLateAuthQueuedWriteAndReconnect() = runTest {
        val policy = policy("nostr")
        val sockets = FakeSocketFactory()
        val releaseSigner = CompletableDeferred<Unit>()
        val signer = Signer(identity, releaseSigner)
        val factory = PublicVmlsCarriers(policy, backgroundScope, sockets)
        val carrier = factory.requests(listOf(relay), signer)
        try {
            val reader = backgroundScope.launch { carrier.subscribe(directory).collect {} }
            runCurrent(); val socket = sockets.opened.single(); socket.open(); runCurrent()
            socket.deliverAuth("pending challenge"); runCurrent()
            assertEquals(1, signer.authCalls)
            assertTrue(socket.authFrames().isEmpty())
            val pending = async { carrier.publish(wrap()) }; runCurrent()
            val before = socket.sent.toList()
            policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
            assertTrue(socket.closedByPool, "The policy closes active egress synchronously")
            releaseSigner.complete(Unit); runCurrent()
            socket.drop(); advanceTimeBy(60_000); runCurrent()
            assertFalse(pending.await())
            assertEquals(before, socket.sent, "Late AUTH and queued request cannot escape")
            assertEquals(1, sockets.opened.size)
            policy.set(AccountNetworkMode.NOSTR)
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, sockets.opened.size, "Old carriers cannot borrow a later Nostr permission")
            val fresh = factory.requests(listOf(relay), null)
            try { runCurrent(); assertEquals(2, sockets.opened.size) } finally { fresh.close() }
            reader.cancel()
        } finally { releaseSigner.complete(Unit); carrier.close() }
    }
}
