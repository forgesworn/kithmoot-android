package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.support.FakeSocketFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountNetworkPolicyTest {
    private class Store : ProjectStorage {
        var raw: String? = null
        var held = false
        var writes = 0
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun release() { held = false }
        override suspend fun load() = raw
        override suspend fun save(value: String) { check(held); raw = value; writes++ }
    }
    private val signer = LocalSigner(ByteArray(32) { 3 })
    private fun policy(default: String = "nostr") = AccountNetworkPolicy(default, { null }, {})

    /** The same extracted composition RoomViewModel uses, with actual account services
     * and real RelayPools. Only the OS socket and encrypted disk adapters are replaced. */
    private class Services(private val scope: CoroutineScope, val policy: AccountNetworkPolicy) {
        val sockets = FakeSocketFactory()
        val stores = List(3) { Store() }
        val pools = mutableListOf<RelayPool>()
        private val children = mutableListOf<Job>()
        private val closes = mutableListOf<suspend () -> Unit>()
        private fun start(actor: ParticipantSigner, type: Int) {
            val token = policy.token()
            val job = SupervisorJob(scope.coroutineContext[Job])
            val child = CoroutineScope(scope.coroutineContext + job)
            children += job
            val pool = RelayPool(listOf("wss://recording.example"), policy.ordinarySockets(sockets), child)
            pools += pool
            suspend fun before() = policy.requireCurrent(token)
            when (type) {
                0 -> {
                    val service = SharedProjects(actor, pool, stores[type], child, beforePublish = ::before)
                    closes += { service.close() }
                    child.launch { service.open() }
                }
                1 -> {
                    val service = RoomBookmarks(actor, pool, stores[type], child, beforePublish = ::before)
                    closes += { service.close() }
                    child.launch { service.open() }
                }
                2 -> {
                    val service = ContactInbox(actor, stores[type], child, ContactsRelay(pool, {}),
                        { ContactsRelay(pool, {}) }, { policy.current(token) }, { true }, { false }, beforeNetwork = ::before)
                    closes += { service.close() }
                    child.launch { service.open() }
                }
            }
            pool.start()
        }
        val composition = AccountNetworkServices<ParticipantSigner>(policy,
            listOf({
                children.toList().forEach { it.cancelAndJoin() }; children.clear()
                closes.toList().forEach { it() }; closes.clear()
                pools.toList().forEach { it.stop() }
            }), List(3) { type -> { actor: ParticipantSigner -> start(actor, type) } })
        suspend fun restore(actor: ParticipantSigner?, force: Boolean = false) = policy.accountGate.withLock {
            composition.reconcile(actor, force)
        }
        suspend fun mode(mode: AccountNetworkMode, actor: ParticipantSigner?) = policy.accountGate.withLock {
            policy.set(mode); composition.reconcile(actor)
        }
    }

    @Test fun privateColdRestoreRefreshReconnectAndAccountReplacementOpenNothing() = runTest {
        val policy = policy("private-invitations")
        val main = Services(backgroundScope, policy)
        val chatOnlyOrBackground = Services(backgroundScope, policy)
        main.restore(signer); chatOnlyOrBackground.restore(signer)
        main.restore(signer, force = true) // manual/relay refresh
        main.restore(LocalSigner(ByteArray(32) { 4 }))
        runCurrent(); advanceTimeBy(60_000); runCurrent()
        assertTrue(main.sockets.opened.isEmpty())
        assertTrue(chatOnlyOrBackground.sockets.opened.isEmpty())
        assertTrue(main.stores.all { !it.held && it.writes == 0 })
        assertFailsWith<IllegalStateException> { policy.requireNostr() }
    }

    @Test fun ordinaryCompositionStartsReal1059And30078ServicesOnlyAfterChoiceAndStopsAllOwners() = runTest {
        val policy = policy("private-invitations")
        val main = Services(backgroundScope, policy)
        val secondary = Services(backgroundScope, policy)
        main.restore(signer); secondary.restore(signer)
        main.mode(AccountNetworkMode.NOSTR, signer); secondary.restore(signer)
        runCurrent(); main.sockets.openAll(); secondary.sockets.openAll(); runCurrent()
        val frames = main.sockets.opened.flatMap { it.sent }
        assertTrue(frames.any { it.contains("1059") && it.contains(signer.pubkey) }, frames.toString())
        assertTrue(frames.any { it.contains("30078") && it.contains(signer.pubkey) }, frames.toString())
        assertTrue(main.stores.all { it.held })
        val opened = main.sockets.opened.size
        main.mode(AccountNetworkMode.PRIVATE_INVITATIONS, signer)
        // Boundary closes every owner's sockets before its coroutine observes the mode.
        assertTrue(secondary.sockets.opened.all { it.closedByPool })
        secondary.restore(signer)
        assertTrue(main.stores.all { !it.held })
        assertTrue(secondary.stores.all { !it.held })
        advanceTimeBy(60_000); runCurrent()
        assertEquals(opened, main.sockets.opened.size)
        main.mode(AccountNetworkMode.NOSTR, signer); runCurrent()
        assertTrue(main.sockets.opened.size > opened)
        main.restore(null); secondary.restore(null)
    }

    @Test fun existingAndDelayedFactoriesCannotSendOrReconnectAfterModeRoundTrip() {
        val policy = policy()
        val recording = FakeSocketFactory()
        val oldFactory = policy.ordinarySockets(recording)
        val listener = object : RelaySocketListener {
            override fun onOpen() {}
            override fun onMessage(text: String) {}
            override fun onClosed(reason: String) {}
        }
        val socket = oldFactory.open("wss://recording.example", listener)
        socket.send("before")
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        assertTrue(recording.opened.single().closedByPool)
        policy.set(AccountNetworkMode.NOSTR)
        assertFailsWith<IllegalStateException> { socket.send("stale publication") }
        assertFailsWith<IllegalStateException> { oldFactory.open("wss://recording.example", listener) }
        assertEquals(listOf("before"), recording.opened.single().sent)
        policy.ordinarySockets(recording).open("wss://recording.example", listener).send("fresh choice")
        assertEquals(2, recording.opened.size)
    }

    @Test fun actualPoolReconnectAndQueuedPublishStayBlockedAfterPrivateTransition() = runTest {
        val policy = policy()
        val recording = FakeSocketFactory()
        val pool = RelayPool(listOf("wss://recording.example"), policy.ordinarySockets(recording), backgroundScope)
        pool.start(); runCurrent(); recording.openAll(); runCurrent()
        val socket = recording.opened.single()
        socket.drop(); runCurrent()
        val event = signer.sign(30078, 1_800_000_000, listOf(listOf("d", "paused")), "kept")
        pool.publish(event)
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        advanceTimeBy(60_000); runCurrent()
        policy.set(AccountNetworkMode.NOSTR)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, recording.opened.size)
        assertTrue(recording.opened.flatMap { it.publishedFrames() }.isEmpty())
        pool.stop()
    }

    @Test fun suspendedPublisherAndAccountReplacementLoseTheirGeneration() = runTest {
        val policy = policy()
        val token = policy.token()
        val signing = CompletableDeferred<Unit>()
        var sent = false
        supervisorScope {
            val pending = async { signing.await(); policy.requireCurrent(token); sent = true }
            policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
            policy.set(AccountNetworkMode.NOSTR)
            signing.complete(Unit)
            assertFailsWith<IllegalStateException> { pending.await() }
            assertFalse(sent)
        }
        val replacementToken = policy.token()
        policy.invalidate()
        assertFalse(policy.current(replacementToken))
    }

    @Test fun persistedChoicePrecedesDefaultAndInvalidSettingsFailClosed() {
        var saved: String? = null
        val ordinary = AccountNetworkPolicy("nostr", { saved }, { saved = it })
        assertTrue(ordinary.allowsNostr)
        ordinary.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        assertFalse(AccountNetworkPolicy("nostr", { saved }, {}).allowsNostr)
        saved = "nostr"
        assertTrue(AccountNetworkPolicy("private-invitations", { saved }, {}).allowsNostr)
        assertFalse(AccountNetworkPolicy("nostr", { "unexpected" }, {}).allowsNostr)
        assertFailsWith<IllegalArgumentException> { AccountNetworkMode.parse("preview") }
    }

    @Test fun avatarHttpFactoryIsColdInPrivateAndActiveRequestsCancelOnTransition() {
        val policy = policy("private-invitations")
        var created = 0
        var cancelled = 0
        assertFailsWith<IllegalStateException> { policy.resource(policy.token(), { created++; Any() }, { cancelled++ }) }
        assertEquals(0, created)
        policy.set(AccountNetworkMode.NOSTR)
        val token = policy.token()
        val request = policy.resource(token, { created++; Any() }, { cancelled++ })
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        assertEquals(1, cancelled)
        request.close()
        policy.set(AccountNetworkMode.NOSTR)
        assertFailsWith<IllegalStateException> { policy.resource(token, { created++; Any() }, {}) }
        assertEquals(1, created)
    }
    @Test fun failedPersistenceHoldsPrivateButNeverEnablesPublic() {
        var fail = false
        var saved: String? = "nostr"
        val policy = AccountNetworkPolicy("nostr", { saved }, { if (fail) error("disk refused") else saved = it })
        val token = policy.token()
        fail = true
        assertFailsWith<IllegalStateException> { policy.set(AccountNetworkMode.PRIVATE_INVITATIONS) }
        assertFalse(policy.allowsNostr)
        assertFalse(policy.current(token))
        assertEquals("nostr", saved)
        assertFailsWith<IllegalStateException> { policy.set(AccountNetworkMode.NOSTR) }
        assertFalse(policy.allowsNostr)
        fail = false
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        assertEquals("private-invitations", saved)
        policy.set(AccountNetworkMode.NOSTR)
        assertTrue(policy.allowsNostr)
    }

    @Test fun synchronousJobInvalidationDoesNotDependOnObservingIntermediateMode() = runTest {
        val policy = policy()
        var sends = 0
        val job = backgroundScope.launch(start = CoroutineStart.LAZY) {
            delay(1_000)
            sends++
        }
        policy.guardJob(policy.token(), job); job.start(); runCurrent()
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        policy.set(AccountNetworkMode.NOSTR)
        advanceTimeBy(2_000); runCurrent()
        assertTrue(job.isCancelled)
        assertEquals(0, sends)
    }

    @Test fun coldPreferenceReadOrFirstSaveFailureHoldsPrivateWithoutThrowing() {
        var creates = 0
        val unreadable = AccountNetworkPolicy("nostr", { error("unreadable") }, {})
        val unwritable = AccountNetworkPolicy("nostr", { null }, { error("unwritable") })
        for (policy in listOf(unreadable, unwritable)) {
            assertFalse(policy.allowsNostr)
            assertNotNull(policy.state.value.persistenceError)
            assertFailsWith<IllegalStateException> {
                policy.resource(policy.token(), { creates++; Any() }, {})
            }
        }
        assertEquals(0, creates)
    }

    @Test fun onlyExplicitActivePairedAuthorityPermitsPrivateRelayRooms() {
        val own = "wss://arbitrary-relay.example"
        val public = "wss://another-relay.example"
        assertTrue(privatePairedRelayRoom(listOf(own)) { it == own })
        assertFalse(privatePairedRelayRoom(listOf(own, public)) { it == own })
        assertFalse(privatePairedRelayRoom(listOf(own)) { false })
        assertFalse(privatePairedRelayRoom(emptyList()) { true })
    }

    @Test fun foregroundGenerationDetectsConflatedPrivatePublicRoundTripButKeepsPairedRooms() {
        val policy = policy()
        val opened = policy.token()
        assertFalse(staleOrdinaryRoomNetwork(opened, policy.token()))
        policy.set(AccountNetworkMode.PRIVATE_INVITATIONS)
        policy.set(AccountNetworkMode.NOSTR)
        assertTrue(staleOrdinaryRoomNetwork(opened, policy.token()))
        assertFalse(staleOrdinaryRoomNetwork(null, policy.token())) // exclusively paired/nearby room
    }

}
