package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TemporaryRoomAdmissionTest {
    private val now = 1_800_000_000L
    private val host = createRoomInvitation()
    private val roomKey = ByteArray(32) { 9 }
    private val accountKey = ByteArray(32) { 3 }
    private val account = Schnorr.publicKeyHex(accountKey)

    private class Transport : RoomTransport {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 16)
        val sent = mutableListOf<NostrEvent>()
        var subscribers = 0
        var onPublish: (NostrEvent) -> Unit = {}
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = incoming
            .onStart { subscribers++ }.onCompletion { subscribers-- }
        override fun publish(event: NostrEvent) { sent += event; onPublish(event) }
    }

    @Test fun `an authenticated refusal stops retries and wipes the owned request key`() = runTest {
        val transport = Transport(); val key = ByteArray(32) { 4 }
        transport.onPublish = { event ->
            val request = decodeInvitationRequest(event, host.invitation, now)!!
            transport.incoming.tryEmit(encodeInvitationDecline(host, request.device, request.requestId, now))
        }
        assertFailsWith<DeclinedInvitationException> {
            requestTemporaryRoomAdmission(transport, host.invitation, now = { now }, newRequestKey = { key })
        }
        advanceTimeBy(10_000); runCurrent()
        assertEquals(1, transport.sent.size); assertEquals(0, transport.subscribers)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test fun `invalid refusals cannot interrupt the wait and a later grant still succeeds`() = runTest {
        val transport = Transport(); val key = ByteArray(32) { 4 }
        transport.onPublish = { event ->
            val request = decodeInvitationRequest(event, host.invitation, now)!!
            if (transport.sent.size == 1) {
                val valid = encodeInvitationDecline(host, request.device, request.requestId, now)
                transport.incoming.tryEmit(valid.copy(sig = "00".repeat(64)))
                transport.incoming.tryEmit(encodeInvitationDecline(host, request.device, "ab".repeat(32), now))
                transport.incoming.tryEmit(encodeInvitationDecline(host, Schnorr.publicKeyHex(ByteArray(32) { 6 }), request.requestId, now))
                transport.incoming.tryEmit(encodeInvitationDecline(host, request.device, request.requestId, now - 91))
            } else transport.incoming.tryEmit(encodeInvitationGrant(host, request.device, request.requestId, roomKey, now))
        }
        val result = requestTemporaryRoomAdmission(transport, host.invitation, now = { now }, newRequestKey = { key })!!
        assertContentEquals(roomKey, result.secret)
        assertEquals(2, transport.sent.size)
        assertEquals(1, transport.sent.map { it.id }.distinct().size)
        assertEquals(0, transport.subscribers)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test fun `a matching live signer proves this request and the delegate survives key cleanup`() = runTest {
        val transport = Transport()
        val key = ByteArray(32) { 4 }
        val phases = mutableListOf<AdmissionRequestPhase>()
        transport.onPublish = { event ->
            val request = decodeInvitationRequest(event, host.invitation, now)!!
            assertEquals(account, request.verifiedParticipant)
            assertEquals("Rowan", request.name)
            transport.incoming.tryEmit(encodeInvitationGrant(host, request.device, request.requestId, roomKey, now))
        }
        val result = requestTemporaryRoomAdmission(transport, host.invitation, name = "Rowan",
            participant = account, signer = LocalSigner(accountKey), now = { now },
            onPhase = phases::add, newRequestKey = { key })!!
        assertContentEquals(roomKey, result.secret)
        assertTrue(key.all { it == 0.toByte() })
        assertEquals(0, transport.subscribers)
        assertEquals(listOf(AdmissionRequestPhase.SIGNING, AdmissionRequestPhase.WAITING), phases)
        assertNotNull(result.delegate)
        assertTrue(Events.verify(encodeInvitationGrant(result.delegate!!, account, "a".repeat(64), roomKey, now)))
    }

    @Test fun `a mismatched signer cannot prove a claimed account`() = runTest {
        val transport = Transport()
        requestTemporaryRoomAdmission(transport, host.invitation, participant = account,
            signer = LocalSigner(ByteArray(32) { 7 }), timeoutMs = 10, now = { now })
        val request = decodeInvitationRequest(transport.sent.single(), host.invitation, now)!!
        assertEquals(account, request.participant)
        assertNull(request.verifiedParticipant)
        assertEquals(0, transport.subscribers)
    }

    @Test fun `retry republishes one immutable request and the timeout closes its subscription`() = runTest {
        val transport = Transport()
        val result = requestTemporaryRoomAdmission(transport, host.invitation, timeoutMs = 5_001,
            retryMs = 2_000, now = { now })
        assertNull(result)
        assertEquals(3, transport.sent.size)
        assertEquals(1, transport.sent.map { it.id }.distinct().size)
        assertEquals(0, transport.subscribers)
    }

    @Test fun `cancelling a delayed signature prevents publication and wipes its key`() = runTest {
        val transport = Transport()
        val key = ByteArray(32) { 4 }
        val signing = CompletableDeferred<Unit>()
        val signer = object : ParticipantSigner by LocalSigner(accountKey) {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                signing.complete(Unit)
                // Model an external provider that returns even after Cancel.
                withContext(NonCancellable) { delay(100) }
                return LocalSigner(accountKey).sign(kind, createdAt, tags, content)
            }
        }
        val job = launch { requestTemporaryRoomAdmission(transport, host.invitation,
            participant = account, signer = signer, now = { now }, newRequestKey = { key }) }
        signing.await()
        assertEquals(1, transport.subscribers)
        job.cancelAndJoin()
        assertTrue(transport.sent.isEmpty())
        assertEquals(0, transport.subscribers)
        assertTrue(key.all { it == 0.toByte() })
    }

    @Test fun `the shared deadline includes signing and ignores a late signature`() = runTest {
        val transport = Transport()
        val signer = object : ParticipantSigner by LocalSigner(accountKey) {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                withContext(NonCancellable) { delay(101) }
                return LocalSigner(accountKey).sign(kind, createdAt, tags, content)
            }
        }
        assertNull(requestTemporaryRoomAdmission(transport, host.invitation, participant = account,
            signer = signer, timeoutMs = 100, now = { now }))
        assertTrue(transport.sent.isEmpty())
        assertEquals(0, transport.subscribers)
    }

    @Test fun `retirement received while signing prevents any request`() = runTest {
        val transport = Transport()
        val signer = object : ParticipantSigner by LocalSigner(accountKey) {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                assertEquals(1, transport.subscribers)
                transport.incoming.tryEmit(encodeInvitationRetirement(host.invitation, host.inviterSecretKey, now))
                delay(100)
                return LocalSigner(accountKey).sign(kind, createdAt, tags, content)
            }
        }
        assertFailsWith<RetiredInvitationException> {
            requestTemporaryRoomAdmission(transport, host.invitation, participant = account, signer = signer, now = { now })
        }
        assertTrue(transport.sent.isEmpty())
        assertEquals(0, transport.subscribers)
    }

    @Test fun `an account replacement after signing prevents publication`() = runTest {
        val transport = Transport()
        var current = true
        val signer = object : ParticipantSigner by LocalSigner(accountKey) {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                current = false
                return LocalSigner(accountKey).sign(kind, createdAt, tags, content)
            }
        }
        assertFailsWith<IllegalStateException> {
            requestTemporaryRoomAdmission(transport, host.invitation, participant = account, signer = signer,
                stillCurrent = { current }, now = { now })
        }
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `a grant received after account replacement cannot complete entry`() = runTest {
        val transport = Transport()
        var current = true
        transport.onPublish = { event ->
            val request = decodeInvitationRequest(event, host.invitation, now)!!
            current = false
            transport.incoming.tryEmit(encodeInvitationGrant(host, request.device, request.requestId, roomKey, now))
        }
        assertFailsWith<IllegalStateException> {
            requestTemporaryRoomAdmission(transport, host.invitation, participant = account,
                signer = LocalSigner(accountKey), stillCurrent = { current }, now = { now })
        }
        assertEquals(1, transport.sent.size)
        assertEquals(0, transport.subscribers)
    }
}
