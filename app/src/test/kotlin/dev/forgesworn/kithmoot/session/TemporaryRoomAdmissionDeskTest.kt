package dev.forgesworn.kithmoot.session

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TemporaryRoomAdmissionDeskTest {
    private class Transport : RoomTransport {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 128)
        val offers = mutableListOf<NostrEvent>()
        var generation = 0L
        var confirmations = true
        var subscribers = 0
        var beforeDispatch: suspend () -> Unit = {}
        var confirm: suspend () -> Boolean = { confirmations }
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = incoming
            .onStart { subscribers++ }.onCompletion { subscribers-- }
        override fun publicationGeneration() = generation
        override fun publish(event: NostrEvent) { error("Admission must use guarded confirmation") }
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
            stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            beforeDispatch()
            if (generation != this.generation || !stillAllowed()) return false
            offers += event
            return confirm()
        }
    }

    private class Fixture(scope: CoroutineScope) {
        val transport = Transport()
        val host = createRoomInvitation()
        val roomSecret = ByteArray(32) { 9 }
        var clock = 1_800_000_000L
        var epoch: Int? = 0
        var current = true
        var accepted = 0
        var retired = 0
        val desk = TemporaryRoomAdmissionDesk(scope, transport, host, roomSecret, { epoch }, { current },
            onRetired = { retired++ }, onGrantAccepted = { accepted++ }, now = { clock })
        val job = desk.start()
        fun request(key: ByteArray = ByteArray(32) { 4 }, name: String = "Rowan",
            account: ByteArray? = null, prove: Boolean = false): NostrEvent {
            val device = Schnorr.publicKeyHex(key)
            return encodeInvitationRequest(host.invitation, key, clock, name = name,
                participant = account?.let(Schnorr::publicKeyHex),
                accountProof = if (prove) encodeInvitationAccountProof(host.invitation, device, account!!, clock) else null)
        }
    }

    @Test fun `a bearer request waits and only an acknowledged grant clears the decision`() = runTest {
        val f = Fixture(backgroundScope)
        val request = f.request()
        f.transport.incoming.emit(request); runCurrent()
        assertEquals(1, f.desk.pending.value.size)
        assertTrue(f.transport.offers.isEmpty())
        assertEquals(0, f.accepted)
        f.desk.admit(request.id); runCurrent()
        assertTrue(f.desk.pending.value.isEmpty())
        assertEquals(1, f.accepted)
        val admission = decodeRoomAdmissionGrant(f.transport.offers.single(), f.host.invitation,
            ByteArray(32) { 4 }, request.id, f.clock)!!
        assertContentEquals(f.roomSecret, admission.secret)
    }

    @Test fun `identical names retain separate device request identities and retries do not add cards`() = runTest {
        val f = Fixture(backgroundScope)
        val a = f.request(); val b = f.request(ByteArray(32) { 5 })
        repeat(3) { f.transport.incoming.emit(a); f.transport.incoming.emit(b) }; runCurrent()
        assertEquals(listOf(a.id, b.id), f.desk.pending.value.map { it.requestId })
        assertEquals(listOf("Rowan", "Rowan"), f.desk.pending.value.map { it.name })
        assertEquals(2, f.desk.pending.value.map { it.device }.distinct().size)
        assertTrue(f.transport.offers.isEmpty())
    }

    @Test fun `claims and signed proofs remain distinguishable without automatically admitting either`() = runTest {
        val f = Fixture(backgroundScope); val account = ByteArray(32) { 3 }
        val claim = f.request(account = account); val proof = f.request(ByteArray(32) { 5 }, account = account, prove = true)
        f.transport.incoming.emit(claim); f.transport.incoming.emit(proof); runCurrent()
        val rows = f.desk.pending.value
        assertEquals(Schnorr.publicKeyHex(account), rows[0].claimedParticipant)
        assertNull(rows[0].verifiedParticipant)
        assertEquals(Schnorr.publicKeyHex(account), rows[1].verifiedParticipant)
        assertTrue(f.transport.offers.isEmpty())
    }

    @Test fun `dismissal sends nothing and relay replay does not repeat the prompt`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.incoming.emit(request); runCurrent(); f.desk.dismiss(request.id)
        f.transport.incoming.emit(request); runCurrent()
        assertTrue(f.desk.pending.value.isEmpty())
        assertTrue(f.transport.offers.isEmpty())
    }

    @Test fun `missing confirmation offers retry and retry sends the exact same signed grant`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.confirmations = false
        f.transport.incoming.emit(request); runCurrent(); f.desk.admit(request.id); runCurrent()
        assertEquals(AdmissionDecisionPhase.RETRY, f.desk.pending.value.single().phase)
        assertEquals(0, f.accepted)
        f.transport.confirmations = true; f.desk.admit(request.id); runCurrent()
        assertEquals(2, f.transport.offers.size)
        assertEquals(f.transport.offers[0].toJson(), f.transport.offers[1].toJson())
        assertEquals(1, f.accepted)
    }

    @Test fun `repeated admit taps and dismissal while confirming do not offer extra grants`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request(); val ack = CompletableDeferred<Boolean>()
        f.transport.confirm = { ack.await() }
        f.transport.incoming.emit(request); runCurrent()
        repeat(4) { f.desk.admit(request.id) }; runCurrent(); f.desk.dismiss(request.id)
        assertEquals(1, f.transport.offers.size)
        assertEquals(AdmissionDecisionPhase.SENDING, f.desk.pending.value.single().phase)
        ack.complete(true); runCurrent(); assertEquals(1, f.accepted)
    }

    @Test fun `requests expire and an expired button cannot grant room contents`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.incoming.emit(request); runCurrent(); f.clock += 90
        advanceTimeBy(1_001); runCurrent()
        assertTrue(f.desk.pending.value.isEmpty()); f.desk.admit(request.id); runCurrent()
        assertTrue(f.transport.offers.isEmpty())
    }

    @Test fun `changing epoch before dispatch prevents an old room-key grant`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.beforeDispatch = { f.epoch = 1 }
        f.transport.incoming.emit(request); runCurrent(); f.desk.admit(request.id); runCurrent()
        assertTrue(f.transport.offers.isEmpty()); assertEquals(0, f.accepted)
        assertTrue(f.desk.pending.value.isEmpty())
    }

    @Test fun `changing rooms before dispatch prevents publication`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.beforeDispatch = { f.current = false }
        f.transport.incoming.emit(request); runCurrent(); f.desk.admit(request.id); runCurrent()
        assertTrue(f.transport.offers.isEmpty()); assertEquals(0, f.accepted)
    }

    @Test fun `a transport generation change cannot leak a stale grant`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.beforeDispatch = { f.transport.generation++ }
        f.transport.incoming.emit(request); runCurrent(); f.desk.admit(request.id); runCurrent()
        assertTrue(f.transport.offers.isEmpty()); assertEquals(0, f.accepted)
    }

    @Test fun `retirement empties the queue and closes every admission action`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.incoming.emit(request); runCurrent()
        f.transport.incoming.emit(encodeInvitationRetirement(f.host.invitation, f.host.inviterSecretKey, f.clock)); runCurrent()
        assertEquals(1, f.retired); assertTrue(f.desk.pending.value.isEmpty())
        f.desk.admit(request.id); f.transport.incoming.emit(f.request(ByteArray(32) { 5 })); runCurrent()
        assertTrue(f.transport.offers.isEmpty()); assertTrue(f.desk.pending.value.isEmpty())
    }

    @Test fun `cancelling the desk rejects a late noncancellable dispatch`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request(); val barrier = CompletableDeferred<Unit>()
        f.transport.beforeDispatch = { withContext(NonCancellable) { barrier.await() } }
        f.transport.incoming.emit(request); runCurrent(); f.desk.admit(request.id); runCurrent()
        f.job.cancel(); barrier.complete(Unit); runCurrent()
        assertTrue(f.transport.offers.isEmpty()); assertEquals(0, f.accepted)
        assertTrue(f.desk.pending.value.isEmpty()); assertEquals(0, f.transport.subscribers)
        assertTrue(f.roomSecret.any { it != 0.toByte() })
        assertTrue(Events.verify(encodeInvitationGrant(f.host, Schnorr.publicKeyHex(ByteArray(32) { 4 }),
            request.id, f.roomSecret, f.clock)))
    }

    @Test fun `invalid signed requests and this responder's own request are ignored`() = runTest {
        val f = Fixture(backgroundScope); val request = f.request()
        f.transport.incoming.emit(request.copy(sig = "0".repeat(128)))
        f.transport.incoming.emit(f.request(f.host.inviterSecretKey)); runCurrent()
        assertTrue(f.desk.pending.value.isEmpty())
    }

    @Test fun `the pending queue remains bounded without automatically granting overflow`() = runTest {
        val f = Fixture(backgroundScope)
        repeat(65) { index -> f.transport.incoming.emit(f.request(ByteArray(32) { (index + 1).toByte() })) }
        runCurrent(); assertEquals(64, f.desk.pending.value.size); assertTrue(f.transport.offers.isEmpty())
    }
}
