package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.ConsentDecision
import dev.forgesworn.kithmoot.account.ConsentPrompt
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.CoordinationStatus
import dev.forgesworn.kithmoot.account.EngineCapabilities
import dev.forgesworn.kithmoot.account.EngineJoin
import dev.forgesworn.kithmoot.account.EngineSession
import dev.forgesworn.kithmoot.account.EngineSessions
import dev.forgesworn.kithmoot.account.EngineStep
import dev.forgesworn.kithmoot.account.EnrolledDevice
import dev.forgesworn.kithmoot.account.Hosted
import dev.forgesworn.kithmoot.account.JoinRefusedException
import dev.forgesworn.kithmoot.account.MlsVault
import dev.forgesworn.kithmoot.account.MlsVaultUnavailableException
import dev.forgesworn.kithmoot.account.Nip55Signer
import dev.forgesworn.kithmoot.account.ParticipantSigner
import dev.forgesworn.kithmoot.account.SessionHost
import dev.forgesworn.kithmoot.account.StoredRendezvousChild
import dev.forgesworn.kithmoot.account.VaultRefusal
import dev.forgesworn.kithmoot.account.VaultResult
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.account.sessionCall
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.KIND_CIRCLE_EVENT_GRANT
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.ActiveLinkRoute
import dev.forgesworn.kithmoot.relay.HybridRelaySockets
import dev.forgesworn.kithmoot.relay.LinkRelayAddress
import dev.forgesworn.kithmoot.relay.LinkTransportManager
import dev.forgesworn.kithmoot.relay.OkHttpRelaySockets
import dev.forgesworn.kithmoot.relay.RelayAuthenticator
import dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider
import dev.forgesworn.kithmoot.relay.RelayPool
import dev.forgesworn.kithmoot.storage.RoomStorageException
import dev.forgesworn.kithmoot.vmls.LeafBinding
import dev.forgesworn.vmls.ffi.VmlsBindingRequest
import dev.forgesworn.vmls.ffi.VmlsCredential
import dev.forgesworn.vmls.ffi.VmlsException
import dev.forgesworn.vmls.ffi.VmlsStep
import dev.forgesworn.vmls.ffi.prepareCreate
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * VMLS rooms' runtime (P3-03b-3), debug builds only: the persona's engine
 * under the shared coordinated vault, its box routes (decision 16), the
 * grants it issues there, and the foreground loop that drives each room
 * through [SessionDriver] and maps its events to the room (decision 22).
 *
 * No screen holds a room yet: [create] is the keeper's, for the room screens
 * and the lab. Rounds are paused while [quiet] holds (a Tor-only room is
 * open, C7), as witness traffic is. One runtime per process: its locks are
 * the instance's.
 */
class VmlsRuntime(
    private val vault: MlsVault,
    private val link: LinkTransportManager,
    val store: VmlsRoomStore,
    private val ledger: VmlsGrantLedger,
    /** The persona's rendezvous child, read afresh for each use; null when it has none. The caller's copy is wiped here. */
    private val rendezvous: suspend (String) -> StoredRendezvousChild?,
    private val quiet: AtomicBoolean,
    private val scope: CoroutineScope,
    /** Answers the vault's consent asks; null puts each ask on [consent] for the dialog. */
    prompt: ConsentPrompt? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) : VmlsBoxes {
    private val _state = MutableStateFlow(VmlsBoxesState())
    override val state: StateFlow<VmlsBoxesState> = _state.asStateFlow()

    private val _consent = MutableStateFlow<ConsentScope?>(null)
    override val consent: StateFlow<ConsentScope?> = _consent.asStateFlow()
    private var waiting: CompletableDeferred<ConsentDecision>? = null
    private val asking = Mutex()

    /** One page action at a time, and one pass of rounds at a time (the host's persona lock is not reentrant). */
    private val acting = Mutex()
    private val rounding = Mutex()

    private val prompt: ConsentPrompt = prompt ?: ConsentPrompt { scope ->
        asking.withLock {
            val answer = CompletableDeferred<ConsentDecision>()
            synchronized(this) { waiting = answer; _consent.value = scope }
            try {
                // Unanswered, the ask is denied: the vault records the denial for that request only.
                withTimeoutOrNull(CONSENT_MILLIS) { answer.await() } ?: ConsentDecision.Deny
            } finally {
                synchronized(this) { waiting = null; _consent.value = null }
            }
        }
    }

    /** The persona's engine, rebuilt when its device or rendezvous key changes. */
    private class Engine(val device: EnrolledDevice, val rz: String, val sessions: EngineSessions, val host: SessionHost<EngineSession>, val join: EngineJoin)
    private val engines = HashMap<String, Engine>()

    /** One driver per persona and box, its events gathered for the round that raised them. */
    private class Driven(val routeId: String, val driver: SessionDriver<EngineSession>, val events: MutableList<Any>)
    private val drivers = HashMap<String, Driven>()

    /** Each room as driven, seeded from its engine at first: members, epoch and commit flags are never stored. */
    private val live = HashMap<String, VmlsRoom>()

    /** What each box last answered, by persona and box. */
    private val answered = HashMap<String, Boolean>()

    /** Messages received while the app ran, by persona and session: not stored (P3-05 decides history). */
    private val received = HashMap<String, ArrayDeque<RoomSignal.Message>>()

    override fun answer(scope: ConsentScope, decision: ConsentDecision) {
        synchronized(this) { if (_consent.value == scope) waiting?.complete(decision) }
    }

    override fun open(persona: String?) = act(persona) { refresh(it) }

    override fun pair(signer: ParticipantSigner, code: String) = act(signer.pubkey) { pairing(signer, code) }

    /** [pair], awaited: throws what the page shows. */
    internal suspend fun pairing(signer: ParticipantSigner, code: String) {
        val persona = signer.pubkey
        check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
        need(persona)?.let { throw IllegalStateException(it) }
        val pairing = try { BothyPairing.parse(code.trim(), now()) } catch (error: IllegalArgumentException) {
            throw IllegalStateException(error.message ?: "The pairing code is not valid.")
        }
        try {
            (signer as? Nip55Signer)?.requestPermissions(listOf(AUTH_KIND, KIND_CIRCLE_EVENT_GRANT, LeafBinding.DEVICE_CREDENTIAL_KIND))
            val device = enrolled(persona, signer)
            val box = pairing.linkNodeId
            val route = store.route(persona, box)?.takeIf { it.routeId in link.routeIds() } ?: run {
                val paired = withTimeoutOrNull(PAIR_TIMEOUT_MILLIS) {
                    try { link.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).await() } catch (error: ExecutionException) { throw error.cause ?: error }
                } ?: throw IllegalStateException("Your box did not answer. Show a fresh pairing code and try again.")
                val route = VmlsBoxRoute(persona, box, paired.routeId, boxName(pairing.name))
                try { store.putRoute(route) } catch (error: Exception) { runCatching { link.remove(route.routeId) }; throw error }
                route
            }
            grant(signer, route, device.device)
            // The box takes a grant once its witness confirms it: ask until it answers with VMLS.
            var vmls: Boolean? = null
            repeat(GRANT_CHECKS) { attempt ->
                vmls = ask(route)
                if (vmls == true) return@repeat
                if (attempt < GRANT_CHECKS - 1) delay(GRANT_CHECK_MILLIS)
            }
            refresh(persona)
            if (vmls == true) _state.update { it.copy(notice = "${route.boxName} hosts VMLS rooms for this account.") }
            else throw IllegalStateException("${route.boxName} did not take this account's grant. Only the box's owner can host VMLS rooms on it.")
        } finally {
            pairing.pairingSecret.fill(0)
        }
    }

    override fun check(persona: String, box: String) = act(persona) { p ->
        val route = store.route(p, box) ?: throw IllegalStateException("This box is not paired.")
        ask(route)
        refresh(p)
    }

    override fun forget(signer: ParticipantSigner, box: String) = act(signer.pubkey) { forgetting(signer, box) }

    /** [forget], awaited: throws what the page shows. */
    internal suspend fun forgetting(signer: ParticipantSigner, box: String) {
        val persona = signer.pubkey
        val route = store.route(persona, box) ?: throw IllegalStateException("This box is not paired.")
        check(store.rooms().none { it.persona == persona && it.box == box }) { "A VMLS room still uses this box." }
        val device = (vault.device(vault.context(PRINCIPAL, persona)) as? VaultResult.Ok)?.value?.device
        // The grant is withdrawn first: the route is the only way to reach the box with its revocation.
        if (device != null) ledger.revoke(box, device)?.let { revocation ->
            publish(route, signer, listOf(revocation))
            ledger.revoked(box, device, revocation)
        }
        store.forgetRoute(persona, box)
        runCatching { link.remove(route.routeId) }
        synchronized(this) { drivers.remove(key(persona, box)); answered.remove(key(persona, box)) }
        refresh(persona)
    }

    override suspend fun foregroundRounds(persona: String?) {
        if (persona == null || quiet.get()) return
        rounding.withLock {
            val engine = engine(persona) ?: return
            for (room in store.rooms().filter { it.persona == persona && !it.ended }) {
                if (quiet.get()) return
                val route = store.route(persona, room.box) ?: continue
                try {
                    drive(engine, route, room)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // One room's failure leaves the others driven; the next pass tries it again.
                }
            }
        }
    }

    /**
     * The keeper's new room [name] at [box], a lone-member group whose first
     * snapshot the host witnesses (decision 21: stored in the VMLS room
     * store). The box must answer with VMLS for this phone's device.
     */
    suspend fun create(persona: String, box: String, name: String): VmlsRoom = rounding.withLock {
        check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        val route = store.route(persona, box) ?: throw IllegalStateException("Pair with this box first.")
        val installation = (client(engine, route).capabilities() as? BoxAnswer.Ok)?.value?.let(EngineCapabilities::installation)
            ?: throw IllegalStateException("${route.boxName} did not answer with VMLS.")
        val at = now()
        val request = binding(engine.device, box, at)
        var session: String? = null
        val pending = sessionCall { prepareCreate(engine.sessions.platform, at.toULong(), request, installation) }
        val created = pending.use {
            val sign = pending.request()
            val signature = engine.join.sign(persona, sign)
            engine.host.create(persona) {
                val made = sessionCall { pending.complete(at.toULong(), sign.operation, signature) }
                val opened = EngineSession(made.session)
                // A step the host cannot take closes the session it never received.
                val step = try { session = made.session.id().toHex(); hostedStep(made.step) } catch (fault: Throwable) { opened.close(); throw fault }
                opened to step
            }
        }
        // Held: staged, and finished by its first round once the witness confirms it.
        if (created is Hosted.Fenced || created is Hosted.Unknown) throw IllegalStateException("This account's vault refused the new room.")
        VmlsRoom(persona, checkNotNull(session), name, box, VmlsRole.KEEPER, joined = true).also { store.put(it) }
    }

    /** What [persona]'s room [session] received while the app ran, oldest first. */
    fun messages(persona: String, session: String): List<RoomSignal.Message> = synchronized(this) { received[key(persona, session)]?.toList().orEmpty() }

    /** [persona]'s room [session] as last driven, or as stored before its first round. */
    fun room(persona: String, session: String): VmlsRoom? = synchronized(this) { live[key(persona, session)] } ?: store.room(persona, session)

    /** Sends [text] to [persona]'s room [session]; it leaves at the next round. */
    suspend fun send(persona: String, session: String, text: String): Unit = rounding.withLock {
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        val room = room(persona, session) ?: throw IllegalStateException("This room is no longer kept on this phone.")
        check(room.canSend) { "This room cannot send now." }
        val sent = engine.host.step(persona, session.hexToBytes()) { s ->
            val step = sessionCall { s.inner.send(text.toByteArray()) }
            hostedStep(step).let { EngineStep(it.snapshot, step.events) }
        }
        if (sent !is Hosted.Released) throw IllegalStateException("The message waits for this account's vault.")
        apply(room, sent.value)
    }

    // ---- driving ----

    private suspend fun drive(engine: Engine, route: VmlsBoxRoute, stored: VmlsRoom) {
        val persona = stored.persona
        val session = stored.session.hexToBytes()
        val driven = driver(engine, route)
        var room = synchronized(this) { live[key(persona, stored.session)] } ?: seed(engine, stored) ?: return
        synchronized(driven.events) { driven.events.clear() }
        val round = driven.driver.round(persona, session, now())
        val events = synchronized(driven.events) { driven.events.toList().also { driven.events.clear() } }
        room = apply(room, events)
        when (round) {
            is Round.Done -> room = room.settled()
            // The phase moved under the round: the engine's word is taken again.
            is Round.Stopped, is Round.Fenced -> room = seed(engine, room) ?: room
            else -> Unit
        }
        if (room.canSend && round is Round.Done && room.role == VmlsRole.KEEPER) room = removeDue(engine, room)
        save(room)
    }

    /** Applies a step's events to [room], keeps its messages and runs its actions; saves it. */
    private suspend fun apply(room: VmlsRoom, events: List<Any>): VmlsRoom {
        if (events.isEmpty()) return room
        val applied = room.apply(events.map(::roomSignal), now())
        if (applied.messages.isNotEmpty()) synchronized(this) {
            val kept = received.getOrPut(key(room.persona, room.session)) { ArrayDeque() }
            applied.messages.forEach { kept.addLast(it); if (kept.size > MAX_MESSAGES) kept.removeFirst() }
        }
        var next = save(applied.room)
        if (RoomAction.StartUpdate in applied.actions) next = update(next)
        return next
    }

    /** The engine's own Update, its binding renewed (decision 22: `UpdateDue`). */
    private suspend fun update(room: VmlsRoom): VmlsRoom {
        val engine = engine(room.persona) ?: return room
        val session = room.session.hexToBytes()
        val at = now()
        val request = try { binding(engine.device, room.box, at) } catch (_: IllegalStateException) { return room }
        val sign = (engine.host.step(room.persona, session) { s ->
            EngineStep(null, try { s.inner.prepareUpdate(at.toULong(), request) } catch (_: VmlsException.Engine) { null })
        } as? Hosted.Released)?.value ?: return room
        val signature = try { engine.join.sign(room.persona, sign) } catch (_: JoinRefusedException) { return room }
        val done = engine.host.step(room.persona, session) { s ->
            try {
                val step = s.inner.completeUpdate(at.toULong(), sign.operation, signature)
                hostedStep(step).let { EngineStep(it.snapshot, step.events) }
            } catch (_: VmlsException.Engine) {
                EngineStep(null, null)
            }
        }
        val events = (done as? Hosted.Released)?.value ?: return room
        return apply(room.committing(), events)
    }

    /** The keeper's Remove of every leaf whose grace has run (decision 19). */
    private suspend fun removeDue(engine: Engine, room: VmlsRoom): VmlsRoom {
        val (marked, due) = room.dueRemovals(now())
        if (due.isEmpty()) return room
        val at = now()
        val outcome = engine.host.step(room.persona, room.session.hexToBytes()) { s ->
            try {
                val step = s.inner.remove(at.toULong(), due.map { it.hexToBytes() })
                hostedStep(step).let { EngineStep(it.snapshot, Removal(step.events, null)) }
            } catch (refused: VmlsException.Engine) {
                EngineStep(null, Removal(emptyList(), refused.code))
            }
        }
        val removal = (outcome as? Hosted.Released)?.value ?: return marked.removalDeferred(due)
        return when (removal.refused) {
            null -> apply(marked.committing(), removal.events)
            in DEFERRED -> marked.removalDeferred(due)
            else -> marked.removalAbandoned(due)
        }
    }

    private class Removal(val events: List<Any>, val refused: String?)

    /** The engine's state, authoritative over what was stored. Null when the host does not hold the session. */
    private suspend fun seed(engine: Engine, room: VmlsRoom): VmlsRoom? {
        val read = engine.host.step(room.persona, room.session.hexToBytes()) { s ->
            val phase = s.phase()
            val members = if (phase == Phase.PendingJoin) emptyList() else try { roomMembers(s.inner.members()) } catch (_: VmlsException) { emptyList() }
            EngineStep(null, Triple(phase, s.epoch(), members))
        }
        val (phase, epoch, members) = (read as? Hosted.Released)?.value ?: return null
        return save(room.seed(phase, epoch, members))
    }

    /** Stores the driven [room], keeping what the consent gate wrote meanwhile; a room forgotten stays forgotten. */
    private fun save(room: VmlsRoom): VmlsRoom {
        val k = key(room.persona, room.session)
        val saved = store.saveDriven(room)
        synchronized(this) { if (saved == null) live.remove(k) else live[k] = saved }
        return saved ?: room
    }

    // ---- the persona's engine, routes and grants ----

    /** Null while the persona lacks a witness-confirmed vault, an enrolled device or a rendezvous key. */
    private suspend fun engine(persona: String): Engine? {
        if (need(persona) != null) return null
        val device = (vault.device(vault.context(PRINCIPAL, persona)) as? VaultResult.Ok)?.value ?: return null
        val rz = rendezvousKey(persona) ?: return null
        synchronized(this) {
            engines[persona]?.let { kept ->
                if (kept.device == device && kept.rz == rz) return kept
                kept.host.closeAll()
                drivers.keys.removeAll { it.startsWith("$persona:") }
                live.keys.removeAll { it.startsWith("$persona:") }
            }
            val sessions = EngineSessions(device.device.hexToBytes(), rz.hexToBytes())
            val host = SessionHost(vault, sessions)
            val join = EngineJoin(vault, sessions, PRINCIPAL, prompt) { rendezvous(persona) }
            return Engine(device, rz, sessions, host, join).also { engines[persona] = it }
        }
    }

    private fun driver(engine: Engine, route: VmlsBoxRoute): Driven = synchronized(this) {
        drivers[key(route.persona, route.box)]?.takeIf { it.routeId == route.routeId } ?: run {
            val events = mutableListOf<Any>()
            val driver = SessionDriver(engine.host, client(engine, route), route.box.hexToBytes(), EngineCapabilities, events = { synchronized(events) { events.addAll(it) } })
            Driven(route.routeId, driver, events).also { drivers[key(route.persona, route.box)] = it }
        }
    }

    private fun client(engine: Engine, route: VmlsBoxRoute) = client(engine.device.persona, route)

    private fun client(persona: String, route: VmlsBoxRoute) = VmlsBoxClient(link, route.routeId, route.box, {
        vault.signBoxRequestV1(vault.context(PRINCIPAL, persona), it, prompt)
    })

    /** Whether [route]'s box answers with VMLS and its installation for this phone's device; null when unreachable. */
    private suspend fun ask(route: VmlsBoxRoute): Boolean? {
        val vmls = when (val answer = client(route.persona, route).capabilities()) {
            is BoxAnswer.Ok -> EngineCapabilities.installation(answer.value) != null
            is BoxAnswer.Refused, is BoxAnswer.NotSigned, BoxAnswer.Malformed -> false
            BoxAnswer.Unreachable -> null
        }
        synchronized(this) { if (vmls == null) answered.remove(key(route.persona, route.box)) else answered[key(route.persona, route.box)] = vmls }
        return vmls
    }

    /** The persona's device, enrolled now if it has none, with a person credential as long as the engine allows. */
    private suspend fun enrolled(persona: String, signer: ParticipantSigner): EnrolledDevice {
        val ctx = vault.context(PRINCIPAL, persona)
        (vault.device(ctx) as? VaultResult.Ok)?.value?.let { return it }
        return when (val enrolled = vault.enrol(ctx, signer, now() + LeafBinding.MAX_PERSON_CREDENTIAL_SECONDS - CLOCK_MARGIN_SECONDS)) {
            is VaultResult.Ok -> enrolled.value
            is VaultResult.Refused -> throw IllegalStateException(
                if (enrolled.refusal == VaultRefusal.Denied) "The signer did not sign this phone's MLS device credential."
                else "This account's vault could not enrol an MLS device (${enrolled.refusal}).",
            )
        }
    }

    /**
     * [device]'s grant at [route]'s box, signed by the keeper [signer],
     * stored before it is published, and dated by the box's clock (a scoped
     * grant counts only up to 120 s ahead of it). A live grant with time
     * left is published again as it is.
     */
    private suspend fun grant(signer: ParticipantSigner, route: VmlsBoxRoute, device: String) {
        val boxNow = when (val answer = client(route.persona, route).capabilities()) {
            is BoxAnswer.Ok -> answer.serverTime
            is BoxAnswer.Refused -> answer.serverTime
            else -> null
        } ?: now()
        ledger.prune(boxNow)
        val live = ledger.get(route.box, device)?.takeIf { it.state == VmlsGrantState.ACTIVE && it.expiration - boxNow > GRANT_RENEW_SECONDS }
        val plan = live?.plan ?: ledger.plan(signer, route.persona, route.box, device, boxNow).also { ledger.record(VmlsGrantRecord(route.box, it)) }
        publish(route, signer, listOf(plan.active))
    }

    /** Publishes [events] to the box's sheltered relay over [route], authenticated as [signer]; the same event again is safe. */
    private suspend fun publish(route: VmlsBoxRoute, signer: ParticipantSigner, events: List<NostrEvent>) {
        val url = LinkRelayAddress.canonicalForNode(route.box)
        val relayScope = CoroutineScope(scope.coroutineContext + SupervisorJob())
        val authenticator = object : RelayAuthenticator {
            override val pubkey = signer.pubkey
            override suspend fun sign(url: String, challenge: String) =
                signer.sign(AUTH_KIND, now(), listOf(listOf("relay", url), listOf("challenge", challenge)), "")
        }
        val sockets = HybridRelaySockets(OkHttpRelaySockets(), link, ActiveLinkRoute { u -> route.routeId.takeIf { u == url } })
        val relay = RelayPool(listOf(url), sockets, relayScope, authenticators = RelayAuthenticatorProvider { u -> authenticator.takeIf { u == url } })
        try {
            relay.start()
            withTimeoutOrNull(RELAY_READY_MILLIS) { relay.connected.first { url in it } }
                ?: throw IllegalStateException("${route.boxName}'s relay did not become ready.")
            for (event in events) {
                // A grant answered `pending` waits on the box's witness: the identical event again is safe.
                var confirmed = false
                for (attempt in 1..PUBLISH_ATTEMPTS) {
                    confirmed = runCatching { relay.publishConfirmed(event) }.getOrDefault(false)
                    if (confirmed) break
                    delay(GRANT_CHECK_MILLIS)
                }
                if (!confirmed) throw IllegalStateException("${route.boxName} did not confirm this account's grant.")
            }
        } finally {
            relay.stop()
            relayScope.cancel()
        }
    }

    /** What [persona] lacks before it can hold a VMLS room, as words for the person; null when nothing. */
    private suspend fun need(persona: String): String? = when (needs(persona).firstOrNull()) {
        null -> null
        VmlsNeed.SIGN_IN -> "Sign in first."
        VmlsNeed.WITNESS -> "Enrol this account at its restore witness first: VMLS rooms need a vault your box confirms."
        VmlsNeed.RENDEZVOUS -> "This account has no rendezvous key yet: provision one from your signer in Settings."
    }

    private suspend fun needs(persona: String?): List<VmlsNeed> {
        if (persona == null) return listOf(VmlsNeed.SIGN_IN)
        val needs = mutableListOf<VmlsNeed>()
        val status = try { vault.coordinationStatus(persona) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        if (status != CoordinationStatus.Active) needs += VmlsNeed.WITNESS
        if (rendezvousKey(persona) == null) needs += VmlsNeed.RENDEZVOUS
        return needs
    }

    private suspend fun rendezvousKey(persona: String): String? {
        val child = try { rendezvous(persona) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null } ?: return null
        return try { child.receipt.rendezvousPubkey } finally { child.wipe() }
    }

    /** A binding for [device] at [box] from [at], as long as the engine's renewal allows and its credential lasts. */
    private fun binding(device: EnrolledDevice, box: String, at: Long): VmlsBindingRequest {
        val credential = device.credential ?: throw IllegalStateException("This phone's MLS device has no credential to bind.")
        val expiresAt = minOf(at + BINDING_SECONDS, device.credentialExpiresAt)
        check(expiresAt - at >= MIN_BINDING_SECONDS) { "This phone's MLS device credential is about to lapse." }
        return VmlsBindingRequest(
            VmlsCredential(credential.pubkey.hexToBytes(), credential.createdAt.toULong(), credential.tags, credential.content, credential.sig.hexToBytes()),
            box.hexToBytes(), expiresAt.toULong(),
        )
    }

    private suspend fun refresh(persona: String?) {
        val needs = needs(persona)
        if (persona == null) { _state.value = VmlsBoxesState(needs = needs); return }
        val device = (vault.device(vault.context(PRINCIPAL, persona)) as? VaultResult.Ok)?.value?.device
        val rooms = store.rooms().filter { it.persona == persona }
        val boxes = store.routes().filter { it.persona == persona }.map { route ->
            VmlsBoxView(route.box, route.boxName, synchronized(this) { answered[key(persona, route.box)] }, rooms.count { it.box == route.box })
        }
        _state.update { it.copy(persona = persona, needs = needs, device = device, boxes = boxes) }
    }

    private fun act(persona: String?, work: suspend (String) -> Unit) {
        scope.launch {
            acting.withLock {
                _state.update { it.copy(busy = true, error = null, notice = null) }
                try {
                    if (persona == null) refresh(null) else work(persona)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _state.update { it.copy(error = describe(error)) }
                } finally {
                    _state.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun key(persona: String, id: String) = "$persona:$id"

    companion object {
        const val PRINCIPAL = "dev.forgesworn.kithmoot"
        private const val AUTH_KIND = 22242
        /**
         * A leaf binding's life. The engine asks for an Update (`UpdateDue`) once a binding has a day or
         * less left (`UPDATE_WINDOW_SECONDS`), so a binding of a day or less would be renewed on every tick.
         */
        const val BINDING_SECONDS = 7L * 86_400
        /** Longer than the engine's update window, or the new binding is due again at once. */
        private const val MIN_BINDING_SECONDS = 25L * 3_600
        private const val CLOCK_MARGIN_SECONDS = 300L
        /** A live grant with less than this left is renewed rather than published again. */
        private const val GRANT_RENEW_SECONDS = 7L * 86_400
        private const val GRANT_CHECKS = 10
        private const val GRANT_CHECK_MILLIS = 2_000L
        private const val PUBLISH_ATTEMPTS = 10
        private const val RELAY_READY_MILLIS = 30_000L
        /** The bridge's pairing rendezvous allows 60 s. */
        private const val PAIR_TIMEOUT_MILLIS = 90_000L
        /** An unanswered consent ask is denied after this. */
        private const val CONSENT_MILLIS = 120_000L
        private const val MAX_MESSAGES = 200
        /** The engine holds one commit at a time, and asks for an Update before this phone may commit. */
        private val DEFERRED = setOf("CommitInFlight", "UpdateRequired")

        private fun boxName(name: String): String =
            name.filterNot { it.isISOControl() }.trim().take(VmlsRoom.MAX_NAME).ifBlank { "Bothy box" }

        /** Words for the person; never a stack trace or a secret. */
        private fun describe(error: Exception): String = when (error) {
            is IllegalStateException -> error.message ?: "That did not work."
            is IllegalArgumentException -> error.message ?: "That did not work."
            is MlsVaultUnavailableException -> "This account's vault could not be read or written."
            is RoomStorageException -> "This phone's VMLS rooms could not be read or written."
            else -> "The box could not be reached (${error.javaClass.simpleName})."
        }
    }
}
