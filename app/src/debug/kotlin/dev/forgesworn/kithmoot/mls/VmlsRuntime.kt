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
import dev.forgesworn.kithmoot.account.VaultSession
import dev.forgesworn.kithmoot.account.sessionContext
import dev.forgesworn.kithmoot.account.hostedStep
import dev.forgesworn.kithmoot.account.sessionCall
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.account.admissible
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.JoinUrlException
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_GRANT
import dev.forgesworn.kithmoot.protocol.KIND_INVITATION_REQUEST
import dev.forgesworn.kithmoot.protocol.RoomInvitation
import dev.forgesworn.kithmoot.protocol.VMLS_JOIN_WAIT_SECONDS
import dev.forgesworn.kithmoot.protocol.VmlsJoinAnswer
import dev.forgesworn.kithmoot.protocol.VmlsJoinRequest
import dev.forgesworn.kithmoot.protocol.createRoomInvitation
import dev.forgesworn.kithmoot.protocol.decodeVmlsInvitationUrl
import dev.forgesworn.kithmoot.protocol.decodeVmlsJoinAnswer
import dev.forgesworn.kithmoot.protocol.decodeVmlsJoinRequest
import dev.forgesworn.kithmoot.protocol.deriveInvitationId
import dev.forgesworn.kithmoot.protocol.encodeVmlsInvitationUrl
import dev.forgesworn.kithmoot.protocol.encodeVmlsJoinAnswer
import dev.forgesworn.kithmoot.protocol.encodeVmlsJoinRequest
import dev.forgesworn.kithmoot.relay.Filter
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.mapNotNull
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
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
import kotlinx.coroutines.withContext
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
    /** The keepers' links, with their keys. */
    private val invites: VmlsInviteStore,
    /** Where invitations travel: Nostr relays in the app, memory in the lab. */
    private val carriers: (List<String>) -> VmlsCarrier,
    /** The persona's rendezvous child, read afresh for each use; null when it has none. The caller's copy is wiped here. */
    private val rendezvous: suspend (String) -> StoredRendezvousChild?,
    private val quiet: AtomicBoolean,
    private val scope: CoroutineScope,
    /** Answers the vault's consent asks; null puts each ask on [consent] for the dialog. */
    prompt: ConsentPrompt? = null,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /** How far ahead of its expiry the device credential is renewed (P3-03b-3d); a lab widens it to renew at once. */
    private val credentialRenewSeconds: Long = VmlsRenewal.CREDENTIAL_RENEW_SECONDS,
    /** How far ahead a grant in use is renewed (P3-03b-3d). */
    private val grantRenewSeconds: Long = GRANT_RENEW_SECONDS,
    /** How long a removed device keeps its grant, to fetch its own removal (D1 R2). */
    private val removedGraceSeconds: Long = REMOVED_GRACE_SECONDS,
    private val random: SecureRandom = SecureRandom(),
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

    /** Scopes the person denied, and until when they are not asked again: the vault keeps no box-request denial. */
    private val denied = HashMap<ConsentScope, Long>()

    /**
     * The ask waits for the person, however long the dialog is held back (over
     * a call, say): no answer is ever made for them. Leaving the foreground
     * cancels it, which the vault records as nothing. A denial holds for
     * [DENIED_SECONDS], so the rounds do not ask again at once.
     */
    private val prompt: ConsentPrompt = prompt ?: ConsentPrompt { scope ->
        if (cooling(scope)) return@ConsentPrompt ConsentDecision.Deny
        asking.withLock {
            // Another ask may have been denied while this one queued.
            if (cooling(scope)) return@withLock ConsentDecision.Deny
            val answer = CompletableDeferred<ConsentDecision>()
            synchronized(this) { waiting = answer; _consent.value = scope }
            try {
                answer.await().also { decision ->
                    if (decision != ConsentDecision.Approve) synchronized(this) { denied[scope] = now() + DENIED_SECONDS }
                }
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

    private fun cooling(scope: ConsentScope): Boolean = synchronized(this) { (denied[scope] ?: 0) > now() }

    /** The person asked for something again (pair, check): their earlier denials are asked about afresh. */
    private fun forgiven(persona: String) = synchronized(this) { denied.keys.removeAll { it.persona == persona } }

    override fun answer(scope: ConsentScope, decision: ConsentDecision) {
        synchronized(this) { if (_consent.value == scope) waiting?.complete(decision) }
    }

    /** The account changed (§6.2): an ask still showing is withdrawn, not answered, so it is never shown to the next account. */
    override fun sessionEnded() {
        synchronized(this) { waiting?.cancel() }
    }

    override fun open(persona: String?) = act(persona) { viewer = it; publishRooms(); refresh(it) }

    override fun pair(signer: ParticipantSigner, code: String) = act(signer.pubkey) { pairing(signer, code) }

    /** [pair], awaited: throws what the page shows. */
    internal suspend fun pairing(signer: ParticipantSigner, code: String) {
        val persona = signer.pubkey
        check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
        need(persona)?.let { throw IllegalStateException(it) }
        forgiven(persona)
        pairingWith(signer, code, persona)
    }

    /** Pairings under way: the app's route sweep keeps every route while any is. */
    private val pairingNow = AtomicInteger(0)

    /** [persona]'s route to [pairing]'s box: the one kept, or a new ordinary pairing by its code (decision 15). */
    private suspend fun pairedRoute(persona: String, pairing: BothyPairing): VmlsBoxRoute {
        val box = pairing.linkNodeId
        store.route(persona, box)?.takeIf { it.routeId in link.routeIds() }?.let { return it }
        // Mid-pairing, the route exists before the store names it: the app's route sweep keeps every route meanwhile.
        pairingNow.incrementAndGet()
        try {
            val paired = withTimeoutOrNull(PAIR_TIMEOUT_MILLIS) {
                try { link.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).await() } catch (error: ExecutionException) { throw error.cause ?: error }
            } ?: throw IllegalStateException("Your box did not answer. Show a fresh pairing code and try again.")
            val route = VmlsBoxRoute(persona, box, paired.routeId, boxName(pairing.name))
            try { store.putRoute(route) } catch (error: Exception) { runCatching { link.remove(route.routeId) }; throw error }
            return route
        } finally {
            pairingNow.decrementAndGet()
        }
    }

    private suspend fun pairingWith(signer: ParticipantSigner, code: String, persona: String) {
        val pairing = try { BothyPairing.parse(code.trim(), now()) } catch (error: IllegalArgumentException) {
            throw IllegalStateException(error.message ?: "The pairing code is not valid.")
        }
        try {
            (signer as? Nip55Signer)?.requestPermissions(listOf(AUTH_KIND, KIND_CIRCLE_EVENT_GRANT, LeafBinding.DEVICE_CREDENTIAL_KIND))
            val device = enrolled(persona, signer)
            val route = pairedRoute(persona, pairing)
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
        forgiven(p)
        val route = store.route(p, box) ?: throw IllegalStateException("This box is not paired.")
        ask(route)
        refresh(p)
    }

    override fun forget(signer: ParticipantSigner, box: String) = act(signer.pubkey) { forgetting(signer, box) }

    /** [forget], awaited: throws what the page shows. */
    internal suspend fun forgetting(signer: ParticipantSigner, box: String): Unit = rounding.withLock {
        val persona = signer.pubkey
        val route = store.route(persona, box) ?: throw IllegalStateException("This box is not paired.")
        check(store.rooms().none { it.persona == persona && it.box == box }) { "A VMLS room still uses this box." }
        val device = (vault.device(vault.sessionContext(PRINCIPAL, persona)) as? VaultResult.Ok)?.value?.device
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

    /** The Link routes VMLS boxes use, which the app's sweep of unconsented routes must keep. */
    override fun routeIds(): Set<String> =
        // Mid-pairing, a route exists before the store names it: keep every route until it does.
        if (pairingNow.get() > 0) link.routeIds()
        else try { store.routes().mapTo(HashSet()) { it.routeId } } catch (_: RoomStorageException) { link.routeIds() }

    /** Under the session the rounds began in, as every action is: a sign-out while one runs leaves it nothing to sign. */
    override suspend fun foregroundRounds(persona: String?, signer: ParticipantSigner?) =
        withContext(persona?.let(::begun) ?: EmptyCoroutineContext) {
            val keeper = signer?.takeIf { persona != null && it.pubkey == persona }
            // Before the rounds, so they run on the engine the new credential makes (see renewCredential).
            if (keeper != null) renewCredential(keeper)
            roundsNow(persona)
            if (keeper != null) {
                revokeRemoved(keeper)
                renewGrants(keeper)
            }
        }

    /** A credential renewal the vault refused, by persona, and when it is asked again: not on every pass. */
    private val credentialRetry = HashMap<String, Long>()

    /**
     * Renews the persona's device credential, under the same device key, once
     * it has [VmlsRenewal.CREDENTIAL_RENEW_SECONDS] or less left: the engine's
     * next Update then binds under it (`UpdateDue` and the vault already do
     * that), and no extra Update is forced. A refusal or denial is not asked
     * again for a day.
     *
     * The renewed device differs from the engine's, so the next [engine] call
     * rebuilds it and closes its sessions (`closeAll`). That is accepted:
     * sessions are decrypted copies of the witnessed snapshots and reopen
     * from them, nothing is lost, and the renewal runs ahead of the rounds
     * under the same lock so no round is cut short by it.
     */
    private suspend fun renewCredential(signer: ParticipantSigner) = rounding.withLock {
        // A Tor-only room is open (C7): the signer's relays and the witness are paused with the rest.
        if (quiet.get()) return@withLock
        val persona = signer.pubkey
        val at = now()
        if (synchronized(this) { (credentialRetry[persona] ?: 0) > at }) return@withLock
        val ctx = vault.sessionContext(PRINCIPAL, persona)
        val device = (vault.device(ctx) as? VaultResult.Ok)?.value ?: return@withLock
        if (!VmlsRenewal.credentialDue(device.credentialExpiresAt, at, credentialRenewSeconds)) return@withLock
        val renewed = try {
            vault.renewCredential(ctx, signer, at + LeafBinding.MAX_PERSON_CREDENTIAL_SECONDS - CLOCK_MARGIN_SECONDS)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (renewed is VaultResult.Ok) {
            synchronized(this) { credentialRetry.remove(persona); ownDevices.remove(persona) }
        } else {
            synchronized(this) { credentialRetry[persona] = at + VmlsRenewal.CREDENTIAL_RETRY_SECONDS }
        }
    }

    /** A grant renewal the box did not confirm, by box and device, and when it is tried again: not on every pass. */
    private val grantRetry = HashMap<Pair<String, String>, Long>()

    /**
     * Renews the grants of the devices [signer]'s rooms still use, before
     * they lapse at the box: the persona's own device and each member's and
     * pending join's, at each box where it keeps a room that is not ended.
     * Only ACTIVE grants this keeper issued and has not marked removed, with
     * [GRANT_RENEW_SECONDS] or less left by the box's clock, are renewed
     * (same grant id, a later expiration); a device not in use is left to
     * lapse (D1 R2). One relay session a box carries the renewals. One the
     * box does not confirm is tried again after ten minutes, from the stored
     * grant, which is already the later one.
     */
    private suspend fun renewGrants(signer: ParticipantSigner) = rounding.withLock {
        if (quiet.get()) return@withLock
        val persona = signer.pubkey
        val boxes = store.rooms().filter { it.persona == persona && it.closing == null && !it.ended }.map { it.box }.distinct()
        if (boxes.isEmpty()) return@withLock
        val engine = engine(persona) ?: return@withLock
        val own = ownDevice(persona)
        for (box in boxes) {
            if (quiet.get()) return@withLock
            val route = store.route(persona, box) ?: continue
            try {
                val inUse = devicesOn(engine, persona, box, except = null) + setOfNotNull(own)
                val at = now()
                // The phone's clock first, with a day to spare for the box's: the box is asked only when something may be due.
                val waiting = synchronized(this) { grantRetry.filter { it.key.first == box && it.value > at }.keys.map { it.second }.toSet() }
                val records = ledger.all()
                // Renewals stored but not taken by the box (kept in the ledger, so across restarts) are published again.
                val pending = VmlsRenewal.unconfirmed(records, persona, box, inUse - waiting)
                if (pending.isEmpty() && VmlsRenewal.grantsDue(records, persona, box, inUse - waiting, at, grantRenewSeconds + CLOCK_SLACK_SECONDS).isEmpty()) continue
                // The publish needs the box anyway, and a renewal dated by the phone's clock could run past the 120 s the
                // box allows and be refused for good: without the box's clock this box waits for the next pass.
                val boxNow = boxClock(route) ?: continue
                val due = VmlsRenewal.grantsDue(records, persona, box, inUse - waiting, boxNow, grantRenewSeconds) - pending.toSet()
                val events = ArrayList<NostrEvent>()
                val devices = ArrayList<String>()
                for (record in due) {
                    try {
                        val plan = ledger.plan(signer, record.persona, box, record.device, boxNow, now())
                        // Stored before it is published, marked until the box takes it.
                        ledger.record(VmlsGrantRecord(box, plan, unconfirmed = true), now())
                        events += plan.active; devices += record.device
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        synchronized(this) { grantRetry[box to record.device] = now() + REVOKE_RETRY_SECONDS }
                    }
                }
                for (record in pending) { events += record.plan.active; devices += record.device }
                if (events.isEmpty()) continue
                try {
                    publish(route, signer, events)
                    devices.forEach { ledger.confirmed(box, it) }
                    synchronized(this) { devices.forEach { grantRetry.remove(box to it) } }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    synchronized(this) { devices.forEach { grantRetry[box to it] = now() + REVOKE_RETRY_SECONDS } }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // An unreadable room or a ledger fault leaves this box to the next pass.
            }
        }
    }

    /** A revocation the box did not confirm, by box and device, and when it is tried again: not on every pass. */
    private val revokeRetry = HashMap<Pair<String, String>, Long>()

    /**
     * Revokes the grant of each device removed from [signer]'s rooms once
     * [removedGraceSeconds] have passed (D1 R2): a removed member otherwise
     * keeps its grant, and up to 64 MiB at the box, until the room closes.
     * The grace lets the removed device fetch its own removal first, so it
     * learns it was removed rather than finding the box shut. A device that
     * still has a place in another of the persona's rooms on that box, or a
     * join pending there, keeps its grant (decision 24's rule, as at a close).
     * One the box does not confirm is tried again later.
     */
    private suspend fun revokeRemoved(signer: ParticipantSigner) = rounding.withLock {
        val persona = signer.pubkey
        val at = now()
        // Only guest grants this keeper issued, as at a close: the persona's own devices keep theirs.
        val due = ledger.removals().filter {
            it.issuer == persona && it.persona != persona && it.removedAt!! + removedGraceSeconds <= at &&
                synchronized(this) { (revokeRetry[it.box to it.device] ?: 0) <= at }
        }
        if (due.isEmpty() || quiet.get()) return@withLock
        val engine = engine(persona) ?: return@withLock
        val own = ownDevice(persona)
        for ((box, records) in due.groupBy { it.box }) {
            val route = store.route(persona, box) ?: continue
            // A room unreadable now leaves the revocations for the next pass, rather than revoke a member's grant.
            val placed = try { devicesOn(engine, persona, box, except = null) } catch (_: IllegalStateException) { continue }
            // As at a close: grants lapsed by both clocks are dropped first, so none is revoked that holds nothing.
            ledger.prune(boxClock(route) ?: now(), now())
            for (device in records.map { it.device }) {
                if (device in placed || device == own) { ledger.kept(box, device); continue }
                val revocation = ledger.revoke(box, device) ?: continue
                try {
                    publish(route, signer, listOf(revocation))
                    ledger.revoked(box, device, revocation)
                    synchronized(this) { revokeRetry.remove(box to device) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Not confirmed: tried again later, not on every pass (each try may ask the signer for relay AUTH).
                    synchronized(this) { revokeRetry[box to device] = now() + REVOKE_RETRY_SECONDS }
                }
            }
        }
    }

    /**
     * The devices [persona]'s rooms on [box] still need, other than [except]:
     * their members' and pending joins'. Throws when a room's members cannot
     * be read now, rather than have a guest revoked.
     */
    private suspend fun devicesOn(engine: Engine, persona: String, box: String, except: String?): Set<String> {
        val placed = HashSet<String>()
        for (other in store.rooms().filter { it.persona == persona && it.box == box && it.session != except && it.closing == null && !it.ended }) {
            val read = synchronized(this) { live[key(persona, other.session)] } ?: seed(engine, other)
                ?: throw IllegalStateException("${other.name} on the same box cannot be read just now. Try closing again shortly.")
            read.members.values.forEach { placed += it.device }
            // A join past its deadline no longer holds a place: it is not renewed, and not kept from revocation.
            invites.link(persona, other.session)?.joins?.filter { it.deadline > now() }?.forEach { placed += it.device }
        }
        return placed
    }

    private suspend fun roundsNow(persona: String?) {
        rounding.withLock {
            // Signed out, or another account: the other personas' sessions are closed (SessionHost.closeAll).
            retain(persona)
            viewer = persona
            publishRooms()
            if (persona == null || quiet.get()) return
            val engine = engine(persona) ?: return
            val mine = store.rooms().filter { it.persona == persona }
            // Left or closed: the session is dropped, then the room forgotten. A keeper's still revoking waits for its close.
            for (room in mine.filter { it.closing == Closing.DROPPING }) {
                try { dropped(engine, room) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
            try { sweep(engine, persona) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            // A room stopped by a key compromise or an event this build does not know sends nothing, its outbox included
            // (decisions 22 and 23); one removed or lapsed is read-only for good.
            for (room in mine.filter { it.closing == null && !it.ended && it.stop !is RoomStop.KeyCompromise && it.stop !is RoomStop.Unknown }) {
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
        // Only a witnessed group is a room. Held may have staged it: a later call to the host finishes it, as a
        // session no room names, which closing rooms (P3-03b-3 PR 4) sweeps.
        if (created !is Hosted.Released) throw IllegalStateException(
            if (created is Hosted.Held) "Your box has not confirmed this account's vault yet. Try again shortly." else "This account's vault refused the new room.",
        )
        val id = session ?: throw IllegalStateException("The engine made no session.")
        VmlsRoom(persona, id, name, box, VmlsRole.KEEPER, joined = true).also { store.put(it) }
    }

    /** What [persona]'s room [session] received while the app ran, oldest first. */
    fun messages(persona: String, session: String): List<RoomSignal.Message> = synchronized(this) { received[key(persona, session)]?.toList().orEmpty() }

    /** [persona]'s room [session] as last driven, or as stored before its first round. */
    fun room(persona: String, session: String): VmlsRoom? = synchronized(this) { live[key(persona, session)] } ?: store.room(persona, session)

    /** Sends [text] to [persona]'s room [session]; it leaves at the next round. */
    suspend fun send(persona: String, session: String, text: String): Unit = rounding.withLock {
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        val stored = store.room(persona, session) ?: throw IllegalStateException("This room is no longer kept on this phone.")
        check(stored.closing == null) { "This room is closing." }
        val room = synchronized(this) { live[key(persona, session)] } ?: seed(engine, stored) ?: throw IllegalStateException("This room waits for this account's vault.")
        check(room.canSend) { "This room cannot send now." }
        var ran = false
        val sent = engine.host.step(persona, session.hexToBytes()) { s ->
            ran = true
            val step = sessionCall { s.inner.send(text.toByteArray()) }
            hostedStep(step).let { EngineStep(it.snapshot, step.events) }
        }
        // Held after the engine took it: staged, and released with a later step once the witness confirms it,
        // so sending again would send it twice. Held before that: nothing was sent.
        if (sent is Hosted.Held && ran) { synchronized(this) { said(persona, session, VmlsMessageView(true, persona, text, now())) }; publishRooms(); return@withLock }
        if (sent is Hosted.Held) throw IllegalStateException("This room waits for your box to confirm this account's vault. Send it again shortly.")
        if (sent !is Hosted.Released) throw IllegalStateException("This account's vault refused the message.")
        synchronized(this) { said(persona, session, VmlsMessageView(true, persona, text, now())) }
        apply(room, sent.value)
        publishRooms()
    }

    // ---- the room screens (P3-03b-3 PR 4) ----

    private val _rooms = MutableStateFlow<List<VmlsRoomView>>(emptyList())
    override val rooms: StateFlow<List<VmlsRoomView>> = _rooms.asStateFlow()

    /** Whose rooms [rooms] shows: the signed-in persona. */
    @Volatile private var viewer: String? = null

    /** Each room's messages while the app ran, both ways, oldest first: not stored (P3-05 decides history). */
    private val chat = HashMap<String, ArrayDeque<VmlsMessageView>>()

    /** Under the instance's lock. */
    private fun said(persona: String, session: String, message: VmlsMessageView) {
        val kept = chat.getOrPut(key(persona, session)) { ArrayDeque() }
        kept.addLast(message)
        if (kept.size > MAX_MESSAGES) kept.removeFirst()
    }

    private fun publishRooms() {
        val persona = viewer ?: run { _rooms.value = emptyList(); return }
        val stored = try { store.rooms().filter { it.persona == persona } } catch (_: RoomStorageException) { return }
        val routes = try { store.routes().filter { it.persona == persona }.associateBy { it.box } } catch (_: RoomStorageException) { emptyMap() }
        _rooms.value = synchronized(this) {
            stored.map { kept ->
                val room = live[key(persona, kept.session)]?.copy(closing = kept.closing) ?: kept
                VmlsRoomView(
                    session = room.session, name = room.name, box = room.box, boxName = routes[room.box]?.boxName ?: "Bothy box",
                    keeper = room.role == VmlsRole.KEEPER, state = stateOf(room), reason = reasonOf(room),
                    members = room.members.values.sortedBy { it.leaf }.map { VmlsMemberView(it.leaf, it.identity, it.device, it.pending) },
                    invite = room.invite != null, canSend = room.canSend,
                    messages = chat[key(persona, room.session)]?.toList().orEmpty(),
                )
            }
        }
    }

    private fun stateOf(room: VmlsRoom): VmlsRoomState = when (val status = room.status) {
        RoomStatus.Closing -> VmlsRoomState.CLOSING
        is RoomStatus.Stopped -> when (status.stop) {
            RoomStop.Removed -> VmlsRoomState.REMOVED
            RoomStop.JoinLapsed -> VmlsRoomState.LAPSED
            else -> VmlsRoomState.STOPPED
        }
        RoomStatus.Checking -> VmlsRoomState.CHECKING
        RoomStatus.Retrying -> VmlsRoomState.RETRYING
        RoomStatus.Sending -> VmlsRoomState.SENDING
        RoomStatus.Joining -> VmlsRoomState.JOINING
        RoomStatus.Ready -> VmlsRoomState.READY
    }

    /** Decision 22's words for why a room stopped; the exits are decision 23's. */
    private fun reasonOf(room: VmlsRoom): String? = when (val stop = room.stop) {
        null -> null
        is RoomStop.Recovery -> "This room stopped sending: it needs a recovery this app cannot make yet (${stop.reason})."
        is RoomStop.Unknown -> "This room stopped sending on an event this app does not know (${stop.event})."
        RoomStop.KeyCompromise -> "This room stopped sending: this phone's MLS device may be compromised (${engines[room.persona]?.device?.device?.let(::shortHex) ?: "this device"})."
        RoomStop.Removed -> "You were removed. The room is read-only."
        RoomStop.JoinLapsed -> "The invitation lapsed. Ask the keeper for a new link."
    }

    override fun createRoom(persona: String, box: String, name: String) = act(persona) { p ->
        val room = create(p, box, name.trim())
        _state.update { it.copy(notice = "${room.name} is ready on ${store.route(p, box)?.boxName ?: "your box"}.") }
        publishRooms()
    }

    override fun say(persona: String, session: String, text: String) {
        if (text.isBlank()) return
        scope.launch(begun(persona)) {
            try { send(persona, session, text) } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                _state.update { it.copy(error = describe(error)) }
            }
        }
    }

    override suspend fun inviteLink(persona: String, session: String, base: String, relays: List<String>): String = try {
        check(store.room(persona, session)?.closing == null) { "This room is closing." }
        invite(persona, session, base, relays).also { publishRooms() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        _state.update { it.copy(error = describe(error)) }
        throw error
    }

    override fun retireInvite(persona: String, session: String) = act(persona) { p -> retire(p, session); publishRooms() }

    override fun removeMember(persona: String, session: String, leaf: String) = act(persona) { p -> removing(p, session, leaf) }

    /** [removeMember], awaited. */
    internal suspend fun removing(persona: String, session: String, leaf: String): Unit = rounding.withLock {
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        val stored = store.room(persona, session) ?: throw IllegalStateException("This room is no longer kept on this phone.")
        check(stored.closing == null) { "This room is closing." }
        val room = synchronized(this) { live[key(persona, session)] } ?: seed(engine, stored) ?: throw IllegalStateException("This room waits for this account's vault.")
        check(room.role == VmlsRole.KEEPER) { "Only the room's keeper removes members." }
        check(room.canSend && !room.sending) { "This room cannot change now. Try again shortly." }
        check(leaf in room.members && leaf !in room.removing) { "That member is not in the room." }
        // Kept as the keeper's choice: a Remove deferred or lost is offered again by the rounds until the leaf is gone.
        val (marked, due) = room.evicted(leaf).dueRemovals(now())
        save(removeLeaves(engine, marked, due))
    }

    /**
     * An Update before it is due, deposited by the next round: the
     * two-device lab's racing commit (P3-03b-3). The app updates only on
     * `UpdateDue`.
     */
    internal suspend fun updating(persona: String, session: String): Unit = rounding.withLock {
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        val stored = store.room(persona, session) ?: throw IllegalStateException("This room is no longer kept on this phone.")
        val room = synchronized(this) { live[key(persona, session)] } ?: seed(engine, stored) ?: throw IllegalStateException("This room waits for this account's vault.")
        check(room.canSend && !room.sending) { "This room cannot change now. Try again shortly." }
        check(save(update(room)).sending) { "The engine made no Update." }
    }

    override fun leave(persona: String, session: String) = act(persona) { p -> leaving(p, session) }

    /** [leave], awaited: the session is dropped now if the vault's witness confirms it, otherwise by the rounds. */
    internal suspend fun leaving(persona: String, session: String): Unit = rounding.withLock {
        val stored = store.room(persona, session) ?: return@withLock
        check(stored.role == VmlsRole.GUEST) { "The keeper closes the room instead." }
        store.update(persona, session) { it.copy(closing = Closing.DROPPING) }
        synchronized(this) { live.remove(key(persona, session)) }
        engine(persona)?.let { dropped(it, store.room(persona, session) ?: return@withLock) }
        publishRooms()
    }

    override fun forgetRoom(persona: String, session: String) = act(persona) { p ->
        rounding.withLock {
            val stored = store.room(p, session) ?: return@withLock
            check(stored.ended || synchronized(this) { live[key(p, session)]?.ended } == true) { "Only a room that ended is forgotten; leave or close it instead." }
            // Forgetting revokes nothing: a keeper's guests would keep their grants at the box (D1 R1).
            check(stored.role != VmlsRole.KEEPER) { "A keeper closes its room, which revokes its guests' grants." }
            store.update(p, session) { it.copy(closing = Closing.DROPPING) }
            engine(p)?.let { dropped(it, store.room(p, session) ?: return@withLock) }
            publishRooms()
        }
    }

    override fun close(signer: ParticipantSigner, session: String, force: Boolean) = act(signer.pubkey) { closing(signer, session, force) }

    /**
     * [close], awaited (decision 24): the link is retired and its pending
     * joins forgotten; each guest grant the keeper holds at this box, for a
     * device in none of the keeper's other rooms there, is revoked, the box
     * confirming each; then the session is dropped and the room forgotten.
     * A revocation the box does not confirm leaves the room closing: closing
     * again retries it, and [force] finishes without it (its grant then
     * lapses at the box on its own).
     */
    internal suspend fun closing(signer: ParticipantSigner, session: String, force: Boolean = false): Unit = rounding.withLock {
        val persona = signer.pubkey
        val stored = store.room(persona, session) ?: return@withLock
        check(stored.role == VmlsRole.KEEPER) { "A guest leaves instead." }
        val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
        if (stored.closing != Closing.DROPPING) {
            check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
            val route = store.route(persona, stored.box) ?: throw IllegalStateException("This room's box is not paired.")
            // The devices the persona's other rooms on this box still need, read before anything changes: a room whose
            // members cannot be read now stops the close, rather than have its guests revoked.
            val elsewhere = devicesOn(engine, persona, stored.box, except = session)
            if (stored.closing == null) {
                store.update(persona, session) { it.invited(null).copy(closing = Closing.REVOKING) }
                synchronized(this) { live.remove(key(persona, session)) }
                closePrompts(session)
                invites.forget(persona, session)
                linksChanged.update { it + 1 }
                publishRooms()
            }
            // Every guest grant this keeper issued at the box that has not lapsed by both the box's clock and the phone's: its members', its
            // pending joins' and any it removed earlier (a removed member's grant outlives its leaf).
            val boxNow = boxClock(route) ?: now()
            ledger.prune(boxNow, now())
            val revoke = ledger.all()
                .filter { it.box == stored.box && it.state != VmlsGrantState.REVOKED && it.issuer == persona && it.persona != persona }
                .map { it.device }.toSet() - elsewhere - setOfNotNull(ownDevice(persona))
            var unconfirmed = 0
            for (device in revoke) {
                val revocation = ledger.revoke(stored.box, device) ?: continue
                try {
                    publish(route, signer, listOf(revocation))
                    ledger.revoked(stored.box, device, revocation)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    unconfirmed++
                }
            }
            if (unconfirmed > 0 && !force) throw IllegalStateException(
                "${route.boxName} did not confirm $unconfirmed revocation${if (unconfirmed == 1) "" else "s"}. Close again to retry, or finish without them.",
            )
            store.update(persona, session) { it.copy(closing = Closing.DROPPING) }
        }
        dropped(engine, store.room(persona, session) ?: return@withLock)
        publishRooms()
    }

    /**
     * Drops [room]'s session from the vault's witnessed manifest, then
     * forgets the room. Held, it is dropped by a later pass.
     */
    private suspend fun dropped(engine: Engine, room: VmlsRoom) {
        when (engine.host.drop(room.persona, room.session.hexToBytes())) {
            // Fenced: this installation's vault is superseded, and the session with it.
            is Hosted.Released, Hosted.Unknown, is Hosted.Fenced -> forgotten(room.persona, room.session)
            Hosted.Held -> Unit
        }
    }

    private fun forgotten(persona: String, session: String) {
        store.forget(persona, session)
        invites.link(persona, session)?.let { invites.forget(persona, session) }
        synchronized(this) {
            live.remove(key(persona, session))
            received.remove(key(persona, session))
            chat.remove(key(persona, session))
        }
        publishRooms()
    }

    /** The sessions the vault witnesses for [persona], or null while it cannot say: the lab's view of the sweep. */
    internal suspend fun witnessed(persona: String): Set<String>? = rounding.withLock {
        val engine = engine(persona) ?: return@withLock null
        (engine.host.sessions(persona) as? Hosted.Released)?.value?.keys
    }

    /**
     * Sessions the vault witnesses for [persona] that no room names: a create
     * or join the witness held after its session was staged. Dropped.
     */
    private suspend fun sweep(engine: Engine, persona: String) {
        val held = (engine.host.sessions(persona) as? Hosted.Released)?.value?.keys ?: return
        val named = store.rooms().filter { it.persona == persona }.mapTo(HashSet()) { it.session }
        for (orphan in held - named) engine.host.drop(persona, orphan.hexToBytes())
    }

    // ---- invitations (decisions 17 and 18) ----

    private val gate = VmlsConsentGate()
    private val _joinAsk = MutableStateFlow<VmlsJoinAsk?>(null)
    override val joinAsk: StateFlow<VmlsJoinAsk?> = _joinAsk.asStateFlow()

    /** Requests waiting on the keeper, oldest first, by request id, with when each was asked. */
    /** [at] is when the guest began waiting; [invite] the link it asked over. */
    private class Asked(val ask: VmlsJoinAsk, val request: VmlsJoinRequest, val at: Long, val invite: String)

    /** Requests opened per link in the current window: a flood is turned away before each is decrypted. */
    private val opened = HashMap<String, Pair<Long, Int>>()

    private fun admitted(session: String): Boolean = synchronized(this) {
        val (start, count) = opened[session]?.takeIf { now() - it.first < OPEN_WINDOW_SECONDS } ?: (now() to 0)
        (count < MAX_OPENED).also { opened[session] = start to count + 1 }
    }

    /** When each pending join, by its request id, was last answered again. */
    private val reanswered = HashMap<String, Long>()

    /** Each persona's own MLS device, read once: a request from it is never asked about. */
    private val ownDevices = HashMap<String, String>()

    /** Closes [session]'s prompts: its link was replaced or retired (decision 18). */
    private fun closePrompts(session: String) {
        val closed = synchronized(this) {
            asks.values.filter { it.ask.session == session }.also { gone ->
                gone.forEach { asks.remove(it.ask.requestId) }
                _joinAsk.value = asks.values.firstOrNull()?.ask
            }
        }
        closed.forEach { gate.answered(session, it.ask.requestId) }
    }
    private val asks = LinkedHashMap<String, Asked>()
    private val linksChanged = MutableStateFlow(0)

    /**
     * The keeper's new link for [persona]'s room [session], answered over
     * [relays]: the URL to share, at [base]. An earlier link is retired by it
     * (a new link asks every device afresh).
     */
    suspend fun invite(persona: String, session: String, base: String, relays: List<String>): String = rounding.withLock {
        val room = room(persona, session) ?: throw IllegalStateException("This room is no longer kept on this phone.")
        check(room.role == VmlsRole.KEEPER && room.canSend) { "Only the room's keeper invites, while the room is working." }
        val host = createRoomInvitation()
        try {
            val id = deriveInvitationId(host.invitation)
            val earlier = invites.link(persona, session)
            // Joins admitted over the earlier link are still awaited.
            invites.put(VmlsLink(persona, session, host.invitation.bearer.toHex(), host.inviterSecretKey.toHex(), relays, earlier?.joins.orEmpty()))
            store.update(persona, session) { it.invited(id) } ?: throw IllegalStateException("This room is no longer kept on this phone.")
            synchronized(this) { live[key(persona, session)]?.let { live[key(persona, session)] = it.invited(id) } }
            closePrompts(session)
            linksChanged.update { it + 1 }
            encodeVmlsInvitationUrl(base, host.invitation, room.box, relays)
        } finally {
            host.inviterSecretKey.fill(0)
        }
    }

    /** Retires [persona]'s room [session]'s link: no more prompts. Joins it admitted are still added. */
    suspend fun retire(persona: String, session: String): Unit = rounding.withLock {
        store.update(persona, session) { it.invited(null) }
        synchronized(this) { live[key(persona, session)]?.let { live[key(persona, session)] = it.invited(null) } }
        invites.link(persona, session)?.takeIf { it.joins.isEmpty() }?.let { invites.forget(persona, session) }
        closePrompts(session)
        linksChanged.update { it + 1 }
    }

    override suspend fun serveInvites(persona: String?) {
        if (persona == null) return
        // No relay is kept open while a Tor-only room is (C7): the subscriptions close and reopen after it.
        val quietNow = flow { while (true) { emit(quiet.get()); delay(QUIET_CHECK_MILLIS) } }.distinctUntilChanged()
        combine(linksChanged, quietNow) { _, isQuiet -> isQuiet }.collectLatest { isQuiet ->
            if (isQuiet) return@collectLatest
            val serving = invites.links().filter { link ->
                link.persona == persona && store.room(persona, link.session)?.invite == idOf(link)
            }
            if (serving.isEmpty()) return@collectLatest
            coroutineScope {
                for (link in serving) launch {
                    val invitation = invitationOf(link)
                    carriers(link.relays).use { carrier ->
                        carrier.subscribe(listOf(Filter(
                            kinds = listOf(KIND_INVITATION_REQUEST),
                            tags = mapOf("#d" to listOf(deriveInvitationId(invitation)), "#p" to listOf(invitation.canonicalInviter)),
                        ))).collect { event ->
                            try { asked(link, invitation, event, carrier) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                        }
                    }
                }
                // A prompt left unanswered lapses with the guest's wait.
                launch { while (true) { delay(LAPSE_CHECK_MILLIS); lapse() } }
            }
        }
    }

    /** A request over [link]: asked of the keeper only if the gate lets it through (decision 18). */
    private suspend fun asked(link: VmlsLink, invitation: RoomInvitation, event: NostrEvent, carrier: VmlsCarrier) {
        if (quiet.get()) return
        // A request already seen, or one over a link asked too often of late, is turned away before it is opened.
        if (gate.spent(event.id.lowercase()) || !admitted(link.session)) return
        val key = link.key.hexToBytes()
        val request = try { decodeVmlsJoinRequest(event, invitation, key, now()) } finally { key.fill(0) } ?: return
        if (request.device == ownDevice(link.persona)) return
        // Admitted over this link and not added yet, the device asks again when its answer was lost: answered again,
        // unasked, at most once a minute, and only once the box took its grant. Whoever holds the link, a copy of the
        // guest's credential and its rendezvous public key learns the room's name, the keeper's rendezvous key and the
        // counter this way, as the guest did; the introduction still needs the guest's rendezvous secret.
        val current = invites.link(link.persona, link.session) ?: return
        current.joins.firstOrNull { it.device == request.device && it.deadline > now() }?.let { join ->
            gate.spend(request.requestId)
            if (join.guest != request.persona || join.rendezvous != request.rendezvous) return
            val room = store.room(link.persona, link.session)?.takeIf { it.invite == deriveInvitationId(invitation) } ?: return
            val granted = ledger.get(room.box, join.device)?.takeIf { it.state == VmlsGrantState.ACTIVE && it.persona == join.guest && it.expiration > now() }
            if (granted == null) return
            val due = synchronized(this) {
                reanswered.values.removeAll { now() - it > JOIN_DEADLINE_SECONDS }
                val last = reanswered[join.requestId] ?: 0
                (now() - last >= REANSWER_SECONDS).also { if (it) reanswered[join.requestId] = now() }
            }
            if (!due) return
            val rz = rendezvousKey(link.persona) ?: return
            val again = VmlsJoinAnswer.Admitted(request.requestId, link.persona, rz, join.counter, room.box, room.name)
            val linkKey = current.key.hexToBytes()
            try { carrier.publish(encodeVmlsJoinAnswer(invitation, linkKey, request.requester, again, now())) } finally { linkKey.fill(0) }
            return
        }
        // The guest stopped waiting: no prompt for a request no one awaits.
        if (now() - request.since >= VMLS_JOIN_WAIT_SECONDS) return
        val id = deriveInvitationId(invitation)
        val verdict = gate.offer(store, link.persona, link.session, id, request.requestId, request.device, now())
        if (verdict != VmlsConsentGate.Verdict.Ask) return
        try {
            val room = store.room(link.persona, link.session) ?: return gate.answered(link.session, request.requestId)
            val boxName = store.route(link.persona, room.box)?.boxName ?: "your box"
            val ask = VmlsJoinAsk(link.persona, link.session, room.name, room.box, boxName, request.persona, request.device, request.requestId)
            synchronized(this) { asks[request.requestId] = Asked(ask, request, request.since, id); _joinAsk.value = asks.values.first().ask }
        } catch (error: Exception) {
            // Not shown, so the room's prompt is not held open, and the device may be asked about again.
            gate.answered(link.session, request.requestId)
            runCatching { store.update(link.persona, link.session) { it.copy(asked = it.asked - request.device) } }
            throw error
        }
    }

    private suspend fun ownDevice(persona: String): String? = synchronized(this) { ownDevices[persona] }
        ?: (vault.device(vault.sessionContext(PRINCIPAL, persona)) as? VaultResult.Ok)?.value?.device?.also { synchronized(this) { ownDevices[persona] = it } }

    private fun lapse() {
        val lapsed = synchronized(this) {
            val gone = asks.values.filter { now() - it.at >= VMLS_JOIN_WAIT_SECONDS }
            gone.forEach { asks.remove(it.ask.requestId) }
            _joinAsk.value = asks.values.firstOrNull()?.ask
            gone
        }
        lapsed.forEach { gate.answered(it.ask.session, it.ask.requestId) }
    }

    override fun admit(signer: ParticipantSigner, ask: VmlsJoinAsk, approve: Boolean) = act(ask.persona) { admitting(signer, ask, approve) }

    /**
     * [admit], awaited. Approved: the guest's device is granted at the box,
     * dated by its clock, before the guest is answered with the keeper's
     * rendezvous key and a fresh counter; its capability is then awaited by
     * the rounds, which add it.
     */
    internal suspend fun admitting(signer: ParticipantSigner, ask: VmlsJoinAsk, approve: Boolean) {
        val asked = synchronized(this) { asks.remove(ask.requestId).also { _joinAsk.value = asks.values.firstOrNull()?.ask } }
            ?: throw IllegalStateException("This request has lapsed.")
        try {
            check(now() - asked.at < VMLS_JOIN_WAIT_SECONDS) { "This request has lapsed." }
            check(signer.pubkey == ask.persona) { "Sign in as the room's keeper to answer." }
            check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
            val link = invites.link(ask.persona, ask.session) ?: throw IllegalStateException("This room's link was retired.")
            val invitation = invitationOf(link)
            // Answered over the link it was asked over, or not at all: a replaced link's guest listens on the old one.
            check(deriveInvitationId(invitation) == asked.invite) { "This request came over a link since replaced." }
            val room = store.room(ask.persona, ask.session)?.takeIf { it.invite == asked.invite }
                ?: throw IllegalStateException("This room's link was retired.")
            val request = asked.request
            val key = link.key.hexToBytes()
            try {
                carriers(link.relays).use { carrier ->
                    if (!approve) {
                        carrier.publish(encodeVmlsJoinAnswer(invitation, key, request.requester, VmlsJoinAnswer.Declined(request.requestId), now()))
                        return
                    }
                    check(room.canSend) { "This room cannot add anyone now." }
                    val route = store.route(ask.persona, room.box) ?: throw IllegalStateException("This room's box is not paired.")
                    val rz = rendezvousKey(ask.persona) ?: throw IllegalStateException("This account has no rendezvous key.")
                    val counter = random.nextLong() ushr 11
                    val answer = VmlsJoinAnswer.Admitted(request.requestId, ask.persona, rz, counter, room.box, room.name)
                    // The pending join is kept first, so a guest granted is always one the rounds wait for, and one
                    // whose answer is lost is answered again when it asks again (the gate asks no device twice).
                    val at = now()
                    val pending = VmlsPendingJoin(request.requestId, request.persona, request.device, request.rendezvous, counter, at + JOIN_DEADLINE_SECONDS)
                    var earlier: VmlsPendingJoin? = null
                    invites.update(ask.persona, ask.session) { stored ->
                        earlier = stored.joins.firstOrNull { it.device == request.device }
                        val kept = stored.joins.filter { it.deadline > at && it.device != request.device }
                        check(kept.size < VmlsLink.MAX_JOINS) { "Too many guests are still joining this room. Try again once they have." }
                        stored.with(kept + pending)
                    } ?: throw IllegalStateException("This room's link was retired.")
                    // The guest's device is granted before it is answered: its capability goes to the box at once.
                    try {
                        val boxNow = boxClock(route) ?: now()
                        ledger.prune(boxNow, now())
                        val plan = ledger.plan(signer, request.persona, room.box, request.device, boxNow, now())
                        // Admitted again: the record just stored carries no removal, so the keeper keeps this device. It is
                        // marked until the box takes it, so a failed publish is sent again by the rounds (P3-03b-3d).
                        ledger.record(VmlsGrantRecord(room.box, plan, unconfirmed = true), now())
                        publish(route, signer, listOf(plan.active))
                        ledger.confirmed(room.box, request.device)
                    } catch (failure: Exception) {
                        // Not granted: no join is awaited (an earlier one is kept), and the device may be asked about again.
                        runCatching {
                            invites.update(ask.persona, ask.session) { stored ->
                                stored.with(stored.joins.filterNot { it.requestId == pending.requestId } + listOfNotNull(earlier))
                            }
                        }
                        runCatching { store.update(ask.persona, ask.session) { it.copy(asked = it.asked - request.device) } }
                        throw failure
                    }
                    check(carrier.publish(encodeVmlsJoinAnswer(invitation, key, request.requester, answer, now()))) {
                        "The guest could not be answered: no relay took the answer. It is answered again when it asks again."
                    }
                }
            } finally {
                key.fill(0)
            }
        } finally {
            gate.answered(ask.session, ask.requestId)
        }
    }

    /**
     * The keeper's rounds: each admitted guest's capability, fetched from its
     * introduction mailbox, checked, its package registered, and added
     * (decisions 12 and 13). One commit at a time; the Welcome follows.
     */
    private suspend fun addJoins(engine: Engine, route: VmlsBoxRoute, room: VmlsRoom): VmlsRoom {
        val link = invites.link(room.persona, room.session) ?: return room
        val at = now()
        if (link.joins.any { it.deadline <= at }) invites.update(room.persona, room.session) { it.with(it.joins.filter { j -> j.deadline > at }) }
        forgetSpent(room)
        var current = room
        for (join in link.joins.filter { it.deadline > at }) {
            if (!current.canSend || current.sending) break
            val introduction = try {
                engine.join.introduction(room.persona, join.rendezvous.hexToBytes(), join.counter, at)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { continue }
            introduction.use { intro ->
                val client = client(engine, route)
                val page = (client.fetch(listOf(intro.mailbox())) as? BoxAnswer.Ok) ?: return@use
                val boxNow = page.serverTime ?: at
                for (record in page.value.records) {
                    val item = AckItem(record.mailbox, record.receipt)
                    // One that does not open, or another device's: dropped. One the box would not keep by its clock
                    // (or the phone's, when the box gave none) is left there: the clocks may agree later, and it lapses.
                    val opened = try { intro.openCapability(now().toULong(), record.envelope) } catch (_: VmlsException) { null }
                    if (opened == null || !opened.info().device.contentEquals(join.device.hexToBytes())) {
                        opened?.close(); client.ack(listOf(item)); continue
                    }
                    opened.close()
                    val capability = admissible(intro, record.envelope, join.device.hexToBytes(), boxNow, now()) ?: continue
                    capability.use { cap ->
                        val info = cap.info()
                        val registered = client.registerPackage(info.packageId, info.welcomeMailbox, info.expiresAt.toLong(), VmlsBoxClient.packageCiphertext(info.packageId, info.welcomeMailbox))
                        if (registered !is BoxAnswer.Ok) return@use
                        val added = engine.host.step(room.persona, room.session.hexToBytes()) { s ->
                            try {
                                val step = s.inner.add(now().toULong(), listOf(cap))
                                hostedStep(step).let { EngineStep(it.snapshot, Removal(step.events, null)) }
                            } catch (refused: VmlsException.Engine) {
                                EngineStep(null, Removal(emptyList(), refused.code))
                            }
                        }
                        val outcome = (added as? Hosted.Released)?.value ?: return@use
                        if (outcome.refused in DEFERRED) return@use
                        // Added, or refused for good: the capability is spent either way.
                        client.ack(listOf(item))
                        invites.update(room.persona, room.session) { it.with(it.joins.filterNot { j -> j.requestId == join.requestId }) }
                        forgetSpent(room)
                        if (outcome.refused == null) current = apply(current.committing(), outcome.events)
                    }
                    break
                }
            }
        }
        return current
    }

    /** One join at a time, apart from the page's actions: its wait of up to ten minutes holds none of them. */
    private val joiningLock = Mutex()

    override fun join(signer: ParticipantSigner, url: String, code: String) {
        if (!joiningLock.tryLock()) { _state.update { it.copy(error = "Already asking to join a room.") }; return }
        // Started even on a scope being cancelled, so the lock is always released.
        scope.launch(begun(signer.pubkey), start = CoroutineStart.ATOMIC) {
            try { joinNow(signer, url, code) } finally { joiningLock.unlock() }
        }
    }

    private suspend fun joinNow(signer: ParticipantSigner, url: String, code: String) {
        _state.update { it.copy(error = null, notice = "Asked to join. Waiting up to ten minutes for the keeper…") }
        try {
            val room = joining(signer, url, code)
            _state.update { it.copy(notice = "${room.name}'s keeper let you in: the room joins at the next rounds.") }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            _state.update { it.copy(error = describe(error), notice = null) }
        }
    }

    /** The route to [box], paired by [code] when none is kept, under the page's actions' lock (pairing and forgetting take it too). */
    private suspend fun guestRoute(persona: String, box: String, code: String): VmlsBoxRoute = acting.withLock {
        store.route(persona, box)?.takeIf { it.routeId in link.routeIds() } ?: run {
            val pairing = try { BothyPairing.parse(code.trim(), now()) } catch (error: IllegalArgumentException) {
                throw IllegalStateException("Pair with the room's box first: ask its keeper's box for a pairing code.")
            }
            try {
                // Decision 15: the invite names the box, and the pairing must reach that box.
                check(pairing.linkNodeId == box) { "This pairing code is from another box than the room's." }
                pairedRoute(persona, pairing)
            } finally {
                pairing.pairingSecret.fill(0)
            }
        }
    }

    /**
     * The guest's side of a VMLS link (decisions 15 and 17): this phone's
     * route to the link's box (by the [code] the box shows, unless one is
     * kept), the request, the keeper's answer within ten minutes, and the
     * pending join, whose capability the rounds deposit. The room is stored
     * as joining.
     */
    internal suspend fun joining(signer: ParticipantSigner, url: String, code: String): VmlsRoom {
        val persona = signer.pubkey
        check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
        need(persona)?.let { throw IllegalStateException(it) }
        val payload = try { decodeVmlsInvitationUrl(url.trim()) } catch (error: JoinUrlException) {
            throw IllegalStateException(error.message ?: "The link is not valid.")
        } ?: throw IllegalStateException("This is not a VMLS room's link.")
        (signer as? Nip55Signer)?.requestPermissions(listOf(AUTH_KIND, LeafBinding.DEVICE_CREDENTIAL_KIND))
        val device = enrolled(persona, signer)
        val credential = device.credential ?: throw IllegalStateException("This phone's MLS device has no credential.")
        val route = guestRoute(persona, payload.box, code)
        val rz = rendezvousKey(persona) ?: throw IllegalStateException("This account has no rendezvous key.")
        val requester = secretKey()
        val sent = HashSet<String>()
        val since = now()
        val answer = try {
            carriers(payload.relays).use { carrier ->
                withTimeoutOrNull(VMLS_JOIN_WAIT_SECONDS * 1000) {
                    coroutineScope {
                        // Listening before the first request: the answer is ephemeral.
                        val answered = async(start = CoroutineStart.UNDISPATCHED) {
                            carrier.subscribe(listOf(Filter(
                                kinds = listOf(KIND_INVITATION_GRANT),
                                tags = mapOf("#d" to listOf(deriveInvitationId(payload.invitation)), "#p" to listOf(Schnorr.publicKeyHex(requester))),
                            ))).mapNotNull { event ->
                                decodeVmlsJoinAnswer(event, payload.invitation, requester, synchronized(sent) { sent.toSet() }, now())
                            }.first()
                        }
                        // Asked again until answered: a request is fresh for 90 s, and both phones must be online.
                        val asking = launch {
                            while (true) {
                                check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
                                val request = encodeVmlsJoinRequest(payload.invitation, requester, credential, rz, now(), since = minOf(since, now()))
                                synchronized(sent) { sent += request.id }
                                carrier.publish(request)
                                delay(REQUEST_REPEAT_MILLIS)
                            }
                        }
                        answered.await().also { asking.cancel() }
                    }
                }
            }
        } finally {
            requester.fill(0)
        } ?: throw IllegalStateException("The keeper did not answer within ten minutes. Ask them to look, then try again.")
        val admitted = answer as? VmlsJoinAnswer.Admitted ?: throw IllegalStateException("The keeper did not let this device in.")
        check(admitted.box == payload.box) { "The keeper's answer names another box than the link." }
        return rounding.withLock {
            // Forgotten while the keeper answered: the join would reach no box.
            check(store.route(persona, payload.box)?.routeId == route.routeId) { "This phone's pairing with the room's box was removed. Pair again and ask again." }
            val engine = engine(persona) ?: throw IllegalStateException("This account cannot hold a VMLS room yet.")
            val at = now()
            val binding = binding(engine.device, payload.box, at)
            // Bothy keeps a package at most seven days by its own clock, and the keeper opens it only inside its binding.
            val boxNow = boxClock(route) ?: at
            val expiresAt = minOf(boxNow + CAPABILITY_SECONDS, binding.expiresAt.toLong() - CAPABILITY_MARGIN_SECONDS)
            val hosted = engine.join.requestJoin(engine.host, persona, binding, admitted.rendezvous.hexToBytes(), admitted.counter, expiresAt, at)
            if (hosted !is Hosted.Released) throw IllegalStateException(
                if (hosted is Hosted.Held) "Your box has not confirmed this account's vault yet. Ask to join again shortly." else "This account's vault refused the join.",
            )
            val session = hosted.value.snapshot?.session ?: throw IllegalStateException("The engine made no session.")
            VmlsRoom(persona, session.toHex(), admitted.name, payload.box, VmlsRole.GUEST, joined = false).also { store.put(it) }
        }
    }

    private fun idOf(link: VmlsLink) = deriveInvitationId(invitationOf(link))

    /** A retired link with no join left to add: its keys are kept no longer. */
    private fun forgetSpent(room: VmlsRoom) {
        val link = invites.link(room.persona, room.session) ?: return
        if (link.joins.isEmpty() && store.room(room.persona, room.session)?.invite != idOf(link)) invites.forget(room.persona, room.session)
    }

    private fun invitationOf(link: VmlsLink): RoomInvitation {
        val key = link.key.hexToBytes()
        try { return RoomInvitation(link.bearer.hexToBytes(), Schnorr.publicKeyHex(key)) } finally { key.fill(0) }
    }

    private fun secretKey(): ByteArray {
        while (true) { val k = ByteArray(32).also(random::nextBytes); if (runCatching { Schnorr.publicKey(k) }.isSuccess) return k; k.fill(0) }
    }

    // ---- driving ----

    private suspend fun drive(engine: Engine, route: VmlsBoxRoute, stored: VmlsRoom) {
        val persona = stored.persona
        val session = stored.session.hexToBytes()
        val driven = driver(engine, route)
        var room = synchronized(this) { live[key(persona, stored.session)] } ?: seed(engine, stored) ?: return
        synchronized(driven.events) { driven.events.clear() }
        val round = try {
            driven.driver.round(persona, session, now())
        } catch (cancelled: CancellationException) {
            synchronized(this) { live.remove(key(persona, stored.session)) }
            throw cancelled
        } catch (fault: Exception) {
            // Steps released before the fault raised events the room must still take (a message, a commit's
            // outcome); the engine is then asked again, since what it holds is no longer known here.
            val events = synchronized(driven.events) { driven.events.toList().also { driven.events.clear() } }
            runCatching { apply(room, events) }
            synchronized(this) { live.remove(key(persona, stored.session)) }
            throw fault
        }
        val events = synchronized(driven.events) { driven.events.toList().also { driven.events.clear() } }
        room = apply(room, events)
        when (round) {
            is Round.Done -> room = room.settled()
            // The phase moved under the round: the engine's word is taken again.
            is Round.Stopped, is Round.Fenced -> room = seed(engine, room) ?: room
            else -> Unit
        }
        if (room.canSend && round is Round.Done && room.role == VmlsRole.KEEPER) {
            room = removeDue(engine, room)
            room = addJoins(engine, route, room)
        }
        save(room)
    }

    /** Applies a step's events to [room], keeps its messages and runs its actions; saves it. */
    private suspend fun apply(room: VmlsRoom, events: List<Any>): VmlsRoom {
        if (events.isEmpty()) return room
        val applied = room.apply(events.map(::roomSignal), now())
        if (applied.messages.isNotEmpty()) synchronized(this) {
            val kept = received.getOrPut(key(room.persona, room.session)) { ArrayDeque() }
            applied.messages.forEach { kept.addLast(it); if (kept.size > MAX_MESSAGES) kept.removeFirst() }
            applied.messages.forEach { said(room.persona, room.session, VmlsMessageView(false, it.senderIdentity, String(it.body, Charsets.UTF_8), now())) }
        }
        var next = save(applied.room)
        // A keeper's members removed (by its Remove or another member's): their grants are revoked after the grace
        // (D1 R2). Noted once the room is saved; a mark that cannot be written is left to the room's close.
        if (room.role == VmlsRole.KEEPER) room.devicesGone(applied.room).forEach { runCatching { ledger.removed(room.box, it, now()) } }
        if (RoomAction.StartUpdate in applied.actions) next = update(next)
        return next
    }

    /** The engine's own Update, its binding renewed (decision 22: `UpdateDue`). */
    private suspend fun update(room: VmlsRoom): VmlsRoom {
        val engine = engine(room.persona) ?: return room
        val session = room.session.hexToBytes()
        val at = now()
        // Denied lately: not asked again (each Update is a new operation, and a denial is journalled by the vault).
        if (cooling(ConsentScope(PRINCIPAL, room.persona, engine.device.device, room.box, MlsVault.SIGN_METHOD))) return room
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
        return removeLeaves(engine, marked, due)
    }

    /** One Remove for [due], already marked as removing on [marked]: a lost one is offered again, as decision 19's are. */
    private suspend fun removeLeaves(engine: Engine, marked: VmlsRoom, due: List<String>): VmlsRoom {
        val room = marked
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
        publishRooms()
        return saved ?: room
    }

    // ---- the persona's engine, routes and grants ----

    /** Null while the persona lacks a witness-confirmed vault, an enrolled device or a rendezvous key. */
    private suspend fun engine(persona: String): Engine? {
        if (need(persona) != null) return null
        val device = (vault.device(vault.sessionContext(PRINCIPAL, persona)) as? VaultResult.Ok)?.value ?: return null
        val rz = rendezvousKey(persona) ?: return null
        synchronized(this) {
            engines[persona]?.let { kept ->
                if (kept.device == device && kept.rz == rz) return kept
                kept.host.closeAll()
                ownDevices.remove(persona)
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

    /** Nothing is signed for the box while a Tor-only room is open (C7), so a round under way stops at its next request. */
    private fun client(persona: String, route: VmlsBoxRoute) = VmlsBoxClient(link, route.routeId, route.box, {
        if (quiet.get()) VaultResult.Refused(VaultRefusal.Busy) else vault.signBoxRequestV1(vault.sessionContext(PRINCIPAL, persona), it, prompt)
    })

    /** Closes every engine but [persona]'s: its decrypted sessions go with it. */
    private fun retain(persona: String?) = synchronized(this) {
        val gone = engines.keys.filter { it != persona }
        for (other in gone) {
            engines.remove(other)?.host?.closeAll()
            drivers.keys.removeAll { it.startsWith("$other:") }
            live.keys.removeAll { it.startsWith("$other:") }
            received.keys.removeAll { it.startsWith("$other:") }
            chat.keys.removeAll { it.startsWith("$other:") }
            answered.keys.removeAll { it.startsWith("$other:") }
        }
    }

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
        val ctx = vault.sessionContext(PRINCIPAL, persona)
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
        val boxNow = boxClock(route) ?: now()
        ledger.prune(boxNow, now())
        val live = ledger.get(route.box, device)?.takeIf { it.state == VmlsGrantState.ACTIVE && it.expiration - boxNow > GRANT_RENEW_SECONDS }
        val plan = live?.plan ?: ledger.plan(signer, route.persona, route.box, device, boxNow, now())
            .also { ledger.record(VmlsGrantRecord(route.box, it, unconfirmed = true), now()) }
        publish(route, signer, listOf(plan.active))
        ledger.confirmed(route.box, device)
    }

    /**
     * The box's clock. The capabilities answer carries none, so an empty
     * mailbox no one uses is read: granted, the box answers with its time;
     * not yet granted, its refusal carries it. Null when it says neither.
     */
    private suspend fun boxClock(route: VmlsBoxRoute): Long? = when (val answer = client(route.persona, route).fetch(listOf(ByteArray(32).also(random::nextBytes)))) {
        is BoxAnswer.Ok -> answer.serverTime
        is BoxAnswer.Refused -> answer.serverTime
        else -> null
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
                check(!quiet.get()) { "Close the Tor-only room first: box traffic is paused while it is open." }
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
        val device = (vault.device(vault.sessionContext(PRINCIPAL, persona)) as? VaultResult.Ok)?.value?.device
        val rooms = store.rooms().filter { it.persona == persona }
        val boxes = store.routes().filter { it.persona == persona }.map { route ->
            VmlsBoxView(route.box, route.boxName, synchronized(this) { answered[key(persona, route.box)] }, rooms.count { it.box == route.box })
        }
        _state.update { it.copy(persona = persona, needs = needs, device = device, boxes = boxes) }
    }

    private fun act(persona: String?, work: suspend (String) -> Unit) {
        val begun = persona?.let(::begun) ?: EmptyCoroutineContext
        scope.launch(begun) {
            acting.withLock {
                // Asked in a session since ended: dropped before it asks for anything.
                if (begun[VaultSession]?.context?.let(vault::isCurrent) == false) return@withLock
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

    /**
     * The session work for [persona] was asked in: a sign-out or account switch while
     * it waits, or while it runs, makes it stale (§6.2). Every vault call it makes takes
     * this context ([VaultSession]), never the next session's. A vault that cannot
     * answer leaves the work to fail itself, as before.
     */
    private fun begun(persona: String): CoroutineContext =
        runCatching { vault.context(PRINCIPAL, persona) }.getOrNull()?.let(::VaultSession) ?: EmptyCoroutineContext

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
        const val GRANT_RENEW_SECONDS = 7L * 86_400
        /** The phone's clock may differ from a box's by this much before the box is asked for its own. */
        private const val CLOCK_SLACK_SECONDS = 86_400L
        private const val GRANT_CHECKS = 10
        private const val GRANT_CHECK_MILLIS = 2_000L
        private const val PUBLISH_ATTEMPTS = 10
        private const val RELAY_READY_MILLIS = 30_000L
        /** The bridge's pairing rendezvous allows 60 s. */
        private const val PAIR_TIMEOUT_MILLIS = 90_000L
        /** How long an admitted guest's capability is awaited. */
        private const val JOIN_DEADLINE_SECONDS = 86_400L
        /** A guest's capability lasts six days: inside Bothy's seven, by the box's clock. */
        private const val CAPABILITY_SECONDS = 6L * 86_400
        private const val CAPABILITY_MARGIN_SECONDS = 3_600L
        private const val REQUEST_REPEAT_MILLIS = 5_000L
        private const val LAPSE_CHECK_MILLIS = 15_000L
        private const val QUIET_CHECK_MILLIS = 2_000L
        /** A lost answer is sent again at most this often per join. */
        private const val REANSWER_SECONDS = 60L
        /** At most this many requests a link are opened each window: a guest asks every five seconds. */
        private const val MAX_OPENED = 60
        private const val OPEN_WINDOW_SECONDS = 60L
        /** A denied scope is not asked about again for this long. */
        private const val DENIED_SECONDS = 10L * 60
        private const val REVOKE_RETRY_SECONDS = 10L * 60
        const val REMOVED_GRACE_SECONDS = 24L * 60 * 60
        private const val MAX_MESSAGES = 200
        /** The engine holds one commit at a time, and asks for an Update before this phone may commit. */
        private val DEFERRED = setOf("CommitInFlight", "UpdateRequired")

        private fun shortHex(hex: String) = "${hex.take(8)}…${hex.takeLast(8)}"

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
