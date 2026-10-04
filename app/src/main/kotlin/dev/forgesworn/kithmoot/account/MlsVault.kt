package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.storage.RoomStorage
import dev.forgesworn.kithmoot.vmls.BindingErrorCode
import dev.forgesworn.kithmoot.vmls.BindingException
import dev.forgesworn.kithmoot.vmls.LeafBinding
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Android's MLS device vault (Vennel MLS contract §6, ticket P3-02), the
 * equivalent of KithMoot web's `app/src/mls-vault.ts`.
 *
 * It holds one person-scoped secp256k1 device key per persona, enrolled with
 * a kind-20460 person credential from the identity signer, and does exactly
 * two things with it: signs a validated unsigned leaf binding
 * ([signLeafBindingV1], §6.2) and answers a rendezvous ECDH through the
 * separate [RendezvousVault]'s child ([rendezvousEcdhV1], §6.3). There is no
 * scalar getter, no generic digest signing and no identity-key fallback.
 *
 * Storage is one sealed record per persona (device, consent policy and
 * decision journal together, so a signature's approval and journal entry
 * commit at once) plus one installation record, each in its own
 * [MlsVaultStores] store. On Android those are [RollbackResistantRoomStorage]:
 * a Keystore AES-GCM key per committed version, so a restored older file
 * cannot be opened. That makes rollback a refusal and re-enrolment, never a
 * replay. The Keystore wraps the scalar; the scalar itself is not
 * hardware-backed. Every read and write runs under one process-wide lock.
 *
 * Interim limits until P3-03: the clock is the device's, and nothing here is
 * wired to the MLS engine or to any UI yet.
 *
 * **Coordinated mode** (P3-03b-2, built with [coordinated]): each persona is
 * its own restore-witness installation in one [CoordinatedPersonaStore] file
 * with its own lock, and every covered write (enrolment, approve, withdraw,
 * revoke, and every signature or durable denial) is staged, advanced at the
 * persona's witness and promoted before its result leaves the vault. A held
 * write is [VaultRefusal.WitnessPending]; a fenced persona is
 * [VaultRefusal.RestoreFenced]. Signing generations follow a durable session
 * epoch, so an identical retry after a restart replays while a logout does
 * not; ECDH keeps the per-process generation. Nothing in production builds a
 * coordinated vault yet.
 */
class MlsVault(
    private val stores: MlsVaultStores,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    /**
     * The app's own account generation, if it has one. The vault's generation
     * follows it as well as its own [bump] and a value fixed per process, so
     * a restart also makes earlier operations stale (§6.3).
     */
    private val appGeneration: () -> Long = { 0L },
    private val random: SecureRandom = SecureRandom(),
    private val lock: Mutex = MlsVaultLocks.named(stores.lockName),
    private val coordination: VaultCoordination? = null,
) {
    init { require(coordination == null || coordination.stores === stores) }

    private val boot = random.nextLong()
    @Volatile private var bumps = 0L
    @Volatile private var installationCache: ByteArray? = null
    private val epochs: SessionEpoch? = coordination?.let {
        SessionEpoch(stores.open(EPOCH, "$AAD_PREFIX|epoch||$RECORD_VERSION".toByteArray(Charsets.US_ASCII)), random)
    }
    @Volatile private var epoch: String? = null
    private val personas = ConcurrentHashMap<String, PersonaCoordination<EnrolledDevice>>()

    private fun generation(): Generation = Generation(boot, appGeneration(), bumps, epoch())

    /** The durable session epoch in coordinated mode; empty otherwise. */
    private fun epoch(): String {
        val store = epochs ?: return ""
        epoch?.let { return it }
        return synchronized(this) { epoch ?: store.load().also { epoch = it } }
    }

    // ---- generation (S25, E06) ----

    /** The context for an operation by [persona] under app [principal]. */
    fun context(principal: String, persona: String): VaultContext = VaultContext(principal, persona, generation())

    /**
     * Login, logout, account switch or vault clear: every operation started
     * before, and every reply made before, is now stale.
     */
    fun bump() {
        synchronized(this) {
            val store = epochs
            if (store == null) { bumps++; return }
            // Coordinated: the fresh epoch is written durably before the
            // in-memory generation changes. The change happens regardless, so
            // this process cancels even when the write fails, and the store is
            // then reset so the next start fails safe as well.
            val (fresh, durable) = store.renew()
            epoch = fresh
            bumps++
            if (!durable) throw MlsVaultUnavailableException(IOException("The session epoch could not be written"))
        }
    }

    private fun current(ctx: VaultContext): Boolean = ctx.generation == generation()

    /** A journalled decision is this session's: the whole generation, or in coordinated mode the epoch and app generation. */
    private fun sameSession(entry: Generation, ctx: Generation): Boolean =
        if (coordination == null) entry == ctx else entry.epoch.isNotEmpty() && entry.epoch == ctx.epoch && entry.app == ctx.app

    // ---- enrolment ----

    /**
     * Makes a fresh device key for `ctx.persona`, asks the identity signer for
     * a person credential naming it, checks that credential against the
     * engine's rules and seals both. An earlier device for the persona is
     * replaced only with [replace]: a new device is a new leaf (§6.1).
     */
    suspend fun enrol(ctx: VaultContext, signer: ParticipantSigner, expiresAt: Long, replace: Boolean = false): VaultResult<EnrolledDevice> =
        refusing { enrolChecked(ctx, signer, expiresAt, replace) }

    private suspend fun enrolChecked(ctx: VaultContext, signer: ParticipantSigner, expiresAt: Long, replace: Boolean): VaultResult<EnrolledDevice> {
        if (!current(ctx)) return refuse(VaultRefusal.Stale)
        if (!HEX64.matches(ctx.persona) || signer.pubkey != ctx.persona) return refuse(VaultRefusal.Unauthorised)
        // The engine's lifetime rule, before the person is asked to sign.
        val at = now()
        if (expiresAt <= at || expiresAt - at > LeafBinding.MAX_PERSON_CREDENTIAL_SECONDS) return refuse(VaultRefusal.Malformed)
        val enrolled = look(ctx.persona) { it?.device != null }
        if (enrolled && !replace) return refuse(VaultRefusal.Unauthorised)
        val scalar = newScalar()
        try {
            val device = Schnorr.publicKeyHex(scalar)
            val createdAt = now()
            val tags = listOf(
                listOf("d", ctx.persona),
                listOf("device", device),
                listOf("expiration", expiresAt.toString()),
                listOf("scope", "person"),
            )
            val event = try {
                checkedSignedEvent(
                    signer.sign(LeafBinding.DEVICE_CREDENTIAL_KIND, createdAt, tags, ""),
                    ctx.persona, LeafBinding.DEVICE_CREDENTIAL_KIND, createdAt, tags, "",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return refuse(VaultRefusal.Denied)
            }
            if (!current(ctx)) return refuse(VaultRefusal.Stale)
            val credential = try {
                LeafBinding.verifyPersonCredential(event, now(), ctx.persona)
            } catch (error: BindingException) {
                return refuse(refusalOf(error))
            }
            if (credential.device != device) return refuse(VaultRefusal.Unauthorised)
            return change(ctx.persona) { stored ->
                if (!current(ctx)) return@change Change.Keep(refuse(VaultRefusal.Stale))
                val record = stored ?: PersonaRecord(ctx.persona)
                if (!replace && record.device != null) return@change Change.Keep(refuse(VaultRefusal.Unauthorised))
                record.device?.wipe()
                record.device = DeviceRecord(scalar.copyOf(), device, event, credential.id, credential.expiresAt)
                Change.Write(record, VaultResult.Ok(EnrolledDevice(ctx.persona, device, credential.id, credential.expiresAt)))
            }
        } finally {
            scalar.fill(0)
        }
    }

    /** The persona's enrolled device, without its key. */
    suspend fun device(ctx: VaultContext): VaultResult<EnrolledDevice> {
        if (coordination != null) return coordinatedDevice(ctx)
        return legacyDevice(ctx)
    }

    /**
     * Coordinated: a read that releases nothing, served without the lock from
     * the snapshot taken at the last promotion, so it stays available while
     * the persona is pending. The first read in a process opens the persona.
     */
    private suspend fun coordinatedDevice(ctx: VaultContext): VaultResult<EnrolledDevice> {
        if (!current(ctx)) return refuse(VaultRefusal.Stale)
        val persona = coord(ctx.persona)
        val snapshot = persona.snapshot ?: persona.store.lock.withLock { persona.open(); persona.snapshot }
        if (snapshot?.fenced != null) return refuse(VaultRefusal.RestoreFenced)
        return snapshot?.view?.let { VaultResult.Ok(it) } ?: refuse(VaultRefusal.Unauthorised)
    }

    private suspend fun legacyDevice(ctx: VaultContext): VaultResult<EnrolledDevice> = locked {
        if (!current(ctx)) return@locked refuse(VaultRefusal.Stale)
        val record = open(ctx.persona)
        try {
            val device = record?.device ?: return@locked refuse(VaultRefusal.Unauthorised)
            VaultResult.Ok(EnrolledDevice(ctx.persona, device.device, device.credentialId, device.credentialExpiresAt))
        } finally {
            record?.device?.wipe()
        }
    }

    // ---- policy: consent scopes and known revocations ----

    suspend fun approve(ctx: VaultContext, scope: ConsentScope): VaultResult<Unit> = refusing {
        update(ctx.persona) { record ->
            if (!current(ctx)) return@update refuse(VaultRefusal.Stale)
            if (!scope.wellFormed() || scope.persona != ctx.persona || scope.principal != ctx.principal) return@update refuse(VaultRefusal.Malformed)
            if (scope !in record.approved) record.approved.add(scope)
            null
        }
    }

    /** Withdraws an approval; later requests in that scope need consent again. */
    suspend fun withdraw(ctx: VaultContext, scope: ConsentScope): VaultResult<Unit> = refusing {
        update(ctx.persona) { record ->
            if (!current(ctx)) return@update refuse(VaultRefusal.Stale)
            record.approved.remove(scope)
            null
        }
    }

    /** Records a credential as revoked (a root-signed tombstone the app saw). */
    suspend fun revokeCredential(ctx: VaultContext, credentialId: String): VaultResult<Unit> = refusing {
        update(ctx.persona) { record ->
            if (!current(ctx)) return@update refuse(VaultRefusal.Stale)
            if (!HEX64.matches(credentialId)) return@update refuse(VaultRefusal.Malformed)
            if (credentialId !in record.revoked) record.revoked.add(credentialId)
            null
        }
    }

    // ---- signLeafBindingV1 (§6.2) ----

    suspend fun signLeafBindingV1(ctx: VaultContext, request: JsonElement, consent: ConsentPrompt): VaultResult<SignLeafBindingReply> =
        refusing { signChecked(ctx, request, consent) }

    private suspend fun signChecked(ctx: VaultContext, request: JsonElement, consent: ConsentPrompt): VaultResult<SignLeafBindingReply> {
        if (!current(ctx)) return refuse(VaultRefusal.Stale)
        val req = when (val shape = signRequestShape(request)) {
            is VaultResult.Refused -> return shape
            is VaultResult.Ok -> shape.value
        }
        val started = now()
        if (req.expiresAt < started) return refuse(VaultRefusal.Expired)
        if (req.expiresAt > started + MAX_OPERATION_SECONDS) return refuse(VaultRefusal.Malformed)
        val body = base64Decode(req.body) ?: return refuse(VaultRefusal.Malformed)
        val bodyHash = Digests.sha256(body).toHex()

        // Everything before the prompt, under the lock: a retry replays its
        // decision, and a new request is checked in full.
        // In coordinated mode a replay reads only the promoted record, and
        // only while the witness confirms it.
        val look = when (val first = look<FirstLook>(ctx.persona) { record ->
            if (!current(ctx)) return@look FirstLook.Done(refuse(VaultRefusal.Stale))
            val device = record?.device ?: return@look FirstLook.Done(refuse(VaultRefusal.Unauthorised))
            record.entry(ctx.principal, device.device, req.operation)?.let { earlier ->
                return@look FirstLook.Done(replay(ctx, req, body, bodyHash, earlier, record))
            }
            if (LeafBinding.digest(body).toHex() != req.digest) return@look FirstLook.Done(refuse(VaultRefusal.Malformed))
            when (val checked = check(body, ctx, record, started)) {
                is VaultResult.Refused -> FirstLook.Done(checked)
                is VaultResult.Ok -> ConsentScope(ctx.principal, ctx.persona, device.device, checked.value, SIGN_METHOD)
                    .let { FirstLook.Ask(it, approved = it in record.approved) }
            }
        }) {
            is FirstLook.Done -> return first.result
            is FirstLook.Ask -> first
        }
        val scope = look.scope
        val approvedBefore = look.approved

        // Consent, outside the lock: it is a prompt.
        if (!approvedBefore) {
            val decision = try {
                consent.ask(scope)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ConsentDecision.Deny
            }
            if (!current(ctx)) return refuse(VaultRefusal.Stale)
            if (decision != ConsentDecision.Approve) {
                // A durable denial is a covered write too.
                return change(ctx.persona) { record ->
                    if (!current(ctx)) return@change Change.Keep(refuse(VaultRefusal.Stale))
                    if (record == null) return@change Change.Keep(refuse(VaultRefusal.Unauthorised))
                    record.entry(ctx.principal, scope.device, req.operation)?.let { earlier ->
                        return@change Change.Keep(replay(ctx, req, body, bodyHash, earlier, record))
                    }
                    val entry = JournalEntry(ctx.generation, ctx.principal, scope.device, req.operation, bodyHash, req.digest, req.expiresAt, null, null)
                    when (val recorded = record(record, entry)) {
                        is VaultResult.Refused -> Change.Keep(recorded)
                        is VaultResult.Ok -> Change.Write(record, refuse(VaultRefusal.Denied))
                    }
                }
            }
        }

        return change(ctx.persona) { record ->
            if (!current(ctx)) return@change Change.Keep(refuse(VaultRefusal.Stale))
            val device = record?.device ?: return@change Change.Keep(refuse(VaultRefusal.Unauthorised))
            record.entry(ctx.principal, device.device, req.operation)?.let { earlier ->
                return@change Change.Keep(replay(ctx, req, body, bodyHash, earlier, record))
            }
            // Everything rechecked under the lock, at the time of signing.
            val at = now()
            when (val recheck = check(body, ctx, record, at)) {
                is VaultResult.Refused -> return@change Change.Keep(recheck)
                is VaultResult.Ok -> if (recheck.value != scope.homeBox || device.device != scope.device) return@change Change.Keep(refuse(VaultRefusal.Unauthorised))
            }
            if (req.expiresAt < at) return@change Change.Keep(refuse(VaultRefusal.Expired))
            if (approvedBefore && scope !in record.approved) return@change Change.Keep(refuse(VaultRefusal.Unauthorised))
            if (scope !in record.approved) record.approved.add(scope)
            val digest = req.digest.hexToBytes()
            val aux = ByteArray(32).also(random::nextBytes)
            val signature = Schnorr.sign(digest, device.scalar, aux)
            if (!Schnorr.verify(signature, digest, device.device.hexToBytes())) return@change Change.Keep(refuse(VaultRefusal.Malformed))
            val entry = JournalEntry(ctx.generation, ctx.principal, device.device, req.operation, bodyHash, req.digest, req.expiresAt, signature.toHex(), scope.homeBox)
            when (val recorded = record(record, entry)) {
                is VaultResult.Refused -> Change.Keep(recorded)
                // Built here, released only once the change is promoted.
                is VaultResult.Ok -> Change.Write(record, VaultResult.Ok(signReply(ctx, req, device.device, signature.toHex())))
            }
        }
    }

    /** The body's checks, mapped to refusals; returns the approved home box. */
    private fun check(body: ByteArray, ctx: VaultContext, record: PersonaRecord, at: Long): VaultResult<String> {
        val device = record.device ?: return refuse(VaultRefusal.Unauthorised)
        val homeBox = try {
            val binding = LeafBinding.readUnsigned(body)
            // The body must name this vault's device for this persona (S19),
            // and the credential must be the person form for them (S18).
            if (binding.device.toHex() != device.device) return refuse(VaultRefusal.Unauthorised)
            LeafBinding.checkUnsigned(binding, at, ctx.persona, record.revoked.toSet())
            binding.homeBox.toHex()
        } catch (error: BindingException) {
            return refuse(refusalOf(error))
        }
        if (device.credentialId in record.revoked) return refuse(VaultRefusal.Revoked)
        if (device.credentialExpiresAt <= at) return refuse(VaultRefusal.Expired)
        return VaultResult.Ok(homeBox)
    }

    private fun replay(ctx: VaultContext, req: SignRequest, body: ByteArray, bodyHash: String, entry: JournalEntry, record: PersonaRecord): VaultResult<SignLeafBindingReply> {
        if (entry.bodyHash != bodyHash || entry.digest != req.digest || entry.deadline != req.expiresAt || entry.principal != ctx.principal) {
            return refuse(VaultRefusal.Replay)
        }
        if (!sameSession(entry.generation, ctx.generation)) return refuse(VaultRefusal.Stale)
        val signature = entry.signature ?: return refuse(VaultRefusal.Denied)
        // A cached success is rechecked: still unexpired, still approved,
        // still not revoked (S22).
        val at = now()
        if (entry.deadline < at) return refuse(VaultRefusal.Expired)
        if (!current(ctx)) return refuse(VaultRefusal.Stale)
        val device = record.device ?: return refuse(VaultRefusal.Unauthorised)
        when (val checked = check(body, ctx, record, at)) {
            is VaultResult.Refused -> return checked
            is VaultResult.Ok -> Unit
        }
        val scope = ConsentScope(ctx.principal, ctx.persona, device.device, entry.homeBox ?: "", SIGN_METHOD)
        if (scope !in record.approved) return refuse(VaultRefusal.Unauthorised)
        return VaultResult.Ok(signReply(ctx, req, device.device, signature))
    }

    private fun signReply(ctx: VaultContext, req: SignRequest, device: String, signature: String): SignLeafBindingReply =
        SignLeafBindingReply(req.operation, req.digest, device, signature).also {
            made[it] = Origin(this, ctx.generation, req.key)
        }

    /**
     * The adapter's check before it completes the engine call: the reply was
     * made by this vault, in the current generation, for exactly [request],
     * and its signature verifies. Anything else is refused (E04, E06).
     */
    fun acceptSignReply(request: JsonElement, reply: Any?): VaultResult<SignLeafBindingReply> {
        val origin = reply?.let { made[it] }
        if (origin == null || origin.vault !== this || reply !is SignLeafBindingReply) return refuse(VaultRefusal.Unauthorised)
        if (origin.generation != generation()) return refuse(VaultRefusal.Stale)
        val req = (signRequestShape(request) as? VaultResult.Ok)?.value ?: return refuse(VaultRefusal.Malformed)
        if (origin.request != req.key) return refuse(VaultRefusal.Replay)
        if (!Schnorr.verify(reply.signature.hexToBytes(), req.digest.hexToBytes(), reply.device.hexToBytes())) return refuse(VaultRefusal.Unauthorised)
        return VaultResult.Ok(reply)
    }

    // ---- rendezvousEcdhV1 (§6.3) ----

    /**
     * ECDH between this persona's provisioned rendezvous child and `peer_rz`.
     * [child] resolves the child from the [RendezvousVault] for the current
     * account; this vault reads it there and wipes its copy. No response is
     * cached or logged.
     */
    suspend fun rendezvousEcdhV1(ctx: VaultContext, request: JsonElement, child: suspend () -> StoredRendezvousChild?): VaultResult<RendezvousEcdhReply> {
        if (!current(ctx)) return refuse(VaultRefusal.Stale)
        val req = when (val shape = ecdhRequestShape(request)) {
            is VaultResult.Refused -> return shape
            is VaultResult.Ok -> shape.value
        }
        val at = now()
        if (req.expiresAt < at) return refuse(VaultRefusal.Expired)
        if (req.expiresAt > at + MAX_OPERATION_SECONDS) return refuse(VaultRefusal.Malformed)
        if (!LeafBinding.isPoint(req.peer.hexToBytes())) return refuse(VaultRefusal.Malformed)
        val stored = child()
        try {
            if (!current(ctx)) return refuse(VaultRefusal.Stale)
            if (stored == null) return refuse(VaultRefusal.Unauthorised)
            if (stored.receipt.identity != ctx.persona) return refuse(VaultRefusal.Unauthorised)
            if (stored.receipt.expiresAt <= at) return refuse(VaultRefusal.Expired)
            val own = stored.receipt.rendezvousPubkey
            if (own == req.peer) return refuse(VaultRefusal.Malformed)
            val scalar = stored.copyScalar()
            val shared = try { Schnorr.sharedPointX(scalar, req.peer.hexToBytes()) } finally { scalar.fill(0) }
            try {
                if (shared.all { it == 0.toByte() }) return refuse(VaultRefusal.Malformed)
                val reply = RendezvousEcdhReply(req.operation, req.peer, own, shared.toHex())
                made[reply] = Origin(this, ctx.generation, req.key)
                return VaultResult.Ok(reply)
            } finally {
                shared.fill(0)
            }
        } finally {
            stored?.wipe()
        }
    }

    /** As [acceptSignReply], for ECDH: a forged or substituted value, another
     * peer, or a reply from a previous vault generation is refused. */
    fun acceptEcdhReply(request: JsonElement, reply: Any?): VaultResult<RendezvousEcdhReply> {
        val origin = reply?.let { made[it] }
        if (origin == null || origin.vault !== this || reply !is RendezvousEcdhReply) return refuse(VaultRefusal.Unauthorised)
        if (origin.generation != generation()) return refuse(VaultRefusal.Stale)
        val req = (ecdhRequestShape(request) as? VaultResult.Ok)?.value ?: return refuse(VaultRefusal.Malformed)
        if (origin.request != req.key) return refuse(VaultRefusal.Replay)
        return VaultResult.Ok(reply)
    }

    // ---- clearing ----

    /** Removes the persona's device, journal and policy, and makes every
     * pending operation stale. */
    suspend fun clear(persona: String) {
        if (coordination == null) {
            bump()
            locked { stores.open(personaName(persona), personaAad(persona)).reset() }
            return
        }
        // Logout never waits for the witness: the epoch is not covered.
        val bumped = runCatching { bump() }
        val coordinated = coord(persona)
        coordinated.store.lock.withLock { coordinated.clear() }
        bumped.getOrThrow()
    }

    // ---- coordination (P3-03b-2) ----

    /**
     * Mints and persists the persona's own installation id before any pairing;
     * answers it in hex. Refused while an earlier installation's marker
     * stands that was not superseded.
     */
    suspend fun prepareCoordination(persona: String): VaultResult<String> {
        val coordinated = coord(persona)
        return coordinated.store.lock.withLock { coordinated.prepare()?.toHex() }
            ?.let { VaultResult.Ok(it) } ?: refuse(VaultRefusal.Unauthorised)
    }

    /**
     * The keeper confirms that the persona's old subject was retired at the
     * box (`bothyd witness retire --subject …`). This is the keeper's word,
     * not proof, accepted for v1 debug builds only. Only a cleared or fenced
     * persona whose marker names exactly [retiredSubject], and with no retiring
     * duty standing, proceeds: its old installation is deleted, its ids are
     * kept as tombstones never to be reused, and a fresh enrolment is allowed.
     */
    suspend fun keeperConfirmsRetired(persona: String, retiredSubject: String): VaultResult<Unit> {
        val coordinated = coord(persona)
        return if (coordinated.store.lock.withLock { coordinated.keeperConfirmsRetired(retiredSubject) }) VaultResult.Ok(Unit) else refuse(VaultRefusal.Unauthorised)
    }

    /**
     * Genesis over the empty persona for the keeper's explicit enrolment (B3):
     * [subject] is the enrolment's, [witnessKey] the box's pinned node id.
     * Device enrolment is then the first witnessed advance. A persona that
     * already has a genesis is refused.
     */
    suspend fun beginCoordination(persona: String, subject: ByteArray, witnessKey: ByteArray): VaultResult<CoordinationGenesis> {
        val coordinated = coord(persona)
        return coordinated.store.lock.withLock { coordinated.genesis(subject, witnessKey) }
            ?.let { VaultResult.Ok(it) } ?: refuse(VaultRefusal.Unauthorised)
    }

    /** Whether [persona] is active, pending or fenced; [check] also asks the witness again ("Check now"). */
    suspend fun coordinationStatus(persona: String, check: Boolean = false): CoordinationStatus {
        val coordinated = coord(persona)
        return coordinated.store.lock.withLock { coordinated.status(check) }
    }

    /**
     * The retiring duty for every coordinated persona on disk, found by its
     * sealed coordination index: for the app's foreground timer (it also runs
     * at every open). A persona that fails does not stop the others; failures
     * are answered by file name, each with its marker's subject (when it has
     * one) so the keeper can still be shown the retire line.
     */
    suspend fun runRetiringDuties(): Map<String, RetiringDutyFailure> {
        val coordinated = coordination ?: throw IllegalStateException("This vault is not coordinated")
        val failures = linkedMapOf<String, RetiringDutyFailure>()
        val index = try {
            locked { readIndex() } ?: throw IllegalStateException("The coordination index is corrupt")
        } catch (error: Exception) {
            // Fail closed: nothing is started afresh; every file is reported.
            failures[INDEX] = RetiringDutyFailure(null, error)
            emptyMap()
        }
        for (name in guarded { coordinated.stores.coordinatedNames() }) {
            try {
                val persona = index[name] ?: throw IllegalStateException("The coordination index names no persona for this file")
                check(locked { coordName(persona) } == name) { "The marker names another persona" }
                val entry = coord(persona)
                entry.store.lock.withLock { entry.duty() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val subject = runCatching { coordinated.stores.marker(name).read()?.let(Marker::decode)?.subject }.getOrNull()
                failures[name] = RetiringDutyFailure(subject, error)
            }
        }
        return failures
    }

    private suspend fun coord(persona: String): PersonaCoordination<EnrolledDevice> {
        val coordinated = coordination ?: throw IllegalStateException("This vault is not coordinated")
        personas[persona]?.let { return it }
        val name = locked { coordName(persona).also { indexed(it, persona) } }
        val aad = locked { coordAad(persona) }
        val stores = coordinated.stores
        return personas.getOrPut(persona) {
            PersonaCoordination(persona, CoordinatedPersonaStore(persona, name, stores, aad), coordinated.witness, coordinated.channels, random) { plain ->
                plain?.let { bytes ->
                    val record = guarded { PersonaRecord.decode(bytes, persona) }
                    try {
                        record.device?.let { EnrolledDevice(persona, it.device, it.credentialId, it.credentialExpiresAt) }
                    } finally {
                        record.device?.wipe()
                    }
                }
            }
        }
    }

    // ---- the coordination index: sealed, so no unsealed file names a persona ----

    private fun indexStore() = stores.open(INDEX, "$AAD_PREFIX|coord-index|${installation().toHex()}|$RECORD_VERSION".toByteArray(Charsets.US_ASCII))

    /** Coordinated file name -> persona. Call under the vault lock. */
    /**
     * Null when the index is definitively corrupt: its seal is lost (an absent
     * key, a failed tag) or its contents do not decode. A transient Keystore
     * or provider failure throws [MlsVaultUnavailableException] instead.
     */
    private fun readIndex(): Map<String, String>? {
        val bytes = try {
            indexStore().read()
        } catch (error: Exception) {
            if (CoordinatedPersonaStore.definitive(error)) return null
            if (error is MlsVaultUnavailableException) throw error
            throw MlsVaultUnavailableException(error)
        } ?: return emptyMap()
        try {
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject.mapValues { it.value.jsonPrimitive.content }
                .onEach { (name, persona) -> require(HEX64.matches(persona) && coordName(persona) == name) }
        } catch (_: MlsVaultUnavailableException) {
            throw MlsVaultUnavailableException(IllegalStateException("The coordination index could not be checked"))
        } catch (_: Exception) {
            return null
        } finally {
            bytes.fill(0)
        }
    }

    /** Records [persona] under [name] before any coordinated file can exist for it. Call under the vault lock. */
    private fun indexed(name: String, persona: String) {
        // A transient failure throws and leaves the index as it is. Only a
        // definitively corrupt index is replaced; personas it named are then
        // reported by runRetiringDuties, with their subjects, until opened again.
        val index = readIndex() ?: run {
            guarded { indexStore().reset() }
            emptyMap()
        }
        if (index[name] == persona) return
        val next = buildJsonObject { (index + (name to persona)).toSortedMap().forEach { (k, v) -> put(k, v) } }.toString().toByteArray(Charsets.UTF_8)
        try { guarded { indexStore().write(next) } } finally { next.fill(0) }
    }

    /** Fixed per persona: re-enrolment never moves a file. Rooted in the vault-wide installation id. */
    private fun coordName(persona: String): String {
        require(HEX64.matches(persona)) { "A persona is a lower-case public key." }
        return "coord." + Digests.sha256(installation() + "coord|$persona".toByteArray(Charsets.US_ASCII)).toHex().take(32)
    }

    private fun coordAad(persona: String): ByteArray = "$AAD_PREFIX|coord|$persona|${installation().toHex()}|$RECORD_VERSION".toByteArray(Charsets.US_ASCII)

    // ---- the journal (§6.2) ----

    /** Persists an entry before its outcome is released. Expired entries go;
     * live ones are never evicted: at the cap the operation is `busy`. */
    private fun record(record: PersonaRecord, entry: JournalEntry): VaultResult<Unit> {
        val at = now()
        record.journal.removeAll { it.deadline < at }
        if (record.journal.size >= MAX_JOURNAL_RECORDS) return refuse(VaultRefusal.Busy)
        record.journal.add(entry)
        // Persisted by the caller's change: sealed under the vault lock, or
        // in coordinated mode staged and witnessed. Never written here.
        return VaultResult.Ok(Unit)
    }

    /** A policy change: [mutate] answers a refusal, or null to write the record. */
    private suspend fun update(persona: String, mutate: (PersonaRecord) -> VaultResult.Refused?): VaultResult<Unit> =
        change(persona) { stored ->
            val record = stored ?: PersonaRecord(persona)
            mutate(record)?.let { Change.Keep(it) } ?: Change.Write(record, VaultResult.Ok(Unit))
        }

    // ---- reads and changes, uncoordinated or coordinated ----

    /** A refusal raised by the coordinated path, answered at the public method. */
    private class Refusal(val refusal: VaultRefusal) : Exception(null, null, false, false)

    private inline fun <T> refusing(block: () -> VaultResult<T>): VaultResult<T> = try {
        block()
    } catch (refusal: Refusal) {
        VaultResult.Refused(refusal.refusal)
    }

    /**
     * Reads the persona record under the lock, without writing. Coordinated:
     * the promoted record only, and only while the witness confirms it.
     */
    private suspend fun <T> look(persona: String, block: (PersonaRecord?) -> T): T {
        if (coordination == null) return locked {
            val record = open(persona)
            try { block(record) } finally { record?.device?.wipe() }
        }
        val coordinated = coord(persona)
        return coordinated.store.lock.withLock {
            val (gate, plain) = coordinated.confirmed()
            when (gate) {
                Gate.Ready -> Unit
                // No genesis yet: nothing can be witnessed, so nothing is asked.
                Gate.Pending, Gate.NotEnrolled -> throw Refusal(VaultRefusal.WitnessPending)
                is Gate.Fenced -> throw Refusal(VaultRefusal.RestoreFenced)
            }
            val record = plain?.let { bytes -> try { guarded { PersonaRecord.decode(bytes, persona) } } finally { bytes.fill(0) } }
            try { block(record) } finally { record?.device?.wipe() }
        }
    }

    /**
     * A covered write. Uncoordinated: sealed under the vault lock. Coordinated:
     * built from the promoted record only, staged, advanced at the witness and
     * promoted before its result is returned (the note's seven steps).
     */
    private suspend fun <T> change(persona: String, block: (PersonaRecord?) -> Change<T>): VaultResult<T> {
        if (coordination == null) return locked {
            val record = open(persona)
            var written: PersonaRecord? = null
            try {
                when (val step = block(record)) {
                    is Change.Keep -> step.result
                    is Change.Write -> { written = step.record; seal(step.record); step.result }
                }
            } finally {
                record?.device?.wipe()
                written?.device?.wipe()
            }
        }
        val coordinated = coord(persona)
        return coordinated.store.lock.withLock {
            var result: VaultResult<T>? = null
            var written: PersonaRecord? = null
            val outcome = try {
                coordinated.commit { plain ->
                    val record = plain?.let { guarded { PersonaRecord.decode(it, persona) } }
                    try {
                        when (val step = block(record)) {
                            is Change.Keep -> { result = step.result; null }
                            is Change.Write -> { result = step.result; written = step.record; step.record.encode() }
                        }
                    } finally {
                        record?.device?.wipe()
                    }
                }
            } finally {
                written?.device?.wipe()
            }
            when (outcome) {
                CommitOutcome.Written, CommitOutcome.Unchanged -> result ?: refuse(VaultRefusal.WitnessPending)
                CommitOutcome.Pending -> refuse(VaultRefusal.WitnessPending)
                is CommitOutcome.Fenced -> refuse(VaultRefusal.RestoreFenced)
            }
        }
    }

    private sealed class Change<out T> {
        class Write<T>(val record: PersonaRecord, val result: VaultResult<T>) : Change<T>()
        class Keep<T>(val result: VaultResult<T>) : Change<T>()
    }

    // ---- sealing ----

    private suspend fun <T> locked(work: suspend () -> T): T = lock.withLock { work() }

    /** Opens the persona's record; a missing one is null, an unreadable one
     * throws: never a silent fallback (§6.1). Call under the lock. */
    private fun open(persona: String): PersonaRecord? {
        check(coordination == null) { "A coordinated vault never reads the uncoordinated persona store." }
        val bytes = guarded { stores.open(personaName(persona), personaAad(persona)).read() } ?: return null
        try {
            return guarded { PersonaRecord.decode(bytes, persona) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun seal(record: PersonaRecord) {
        check(coordination == null) { "A coordinated vault never writes the uncoordinated persona store." }
        val bytes = record.encode()
        try {
            guarded { stores.open(personaName(record.persona), personaAad(record.persona)).write(bytes) }
        } finally {
            bytes.fill(0)
        }
    }

    private fun personaName(persona: String): String {
        require(HEX64.matches(persona)) { "A persona is a lower-case public key." }
        return "persona." + Digests.sha256(installation() + persona.toByteArray(Charsets.US_ASCII)).toHex().take(32)
    }

    private fun personaAad(persona: String): ByteArray = "$AAD_PREFIX|persona|$persona|${installation().toHex()}|$RECORD_VERSION".toByteArray(Charsets.US_ASCII)

    /** This installation's vault id: 32 random bytes made with the vault,
     * sealed in it and never exported. Not Bothy's 0xF0B3. Call under the lock. */
    private fun installation(): ByteArray {
        installationCache?.let { return it.copyOf() }
        val store = stores.open(INSTALLATION, "$AAD_PREFIX|installation||$RECORD_VERSION".toByteArray(Charsets.US_ASCII))
        val existing = guarded { store.read() }
        val id = if (existing != null) {
            if (existing.size != 32) { existing.fill(0); throw MlsVaultUnavailableException(IllegalStateException("The MLS vault installation is invalid.")) }
            existing
        } else if (coordination != null && guarded { coordination.stores.coordinatedNames() }.isNotEmpty()) {
            // Never mint a new id over coordinated persona files.
            throw MlsVaultUnavailableException(IllegalStateException("The MLS vault installation is missing."))
        } else {
            ByteArray(32).also(random::nextBytes).also { guarded { store.write(it) } }
        }
        installationCache = id.copyOf()
        return id
    }

    /** This installation's vault id, for diagnostics and tests only. */
    suspend fun installationId(): String = locked { installation().toHex() }

    private fun newScalar(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            if (runCatching { Schnorr.publicKey(candidate) }.isSuccess) return candidate
            candidate.fill(0)
        }
    }

    private inline fun <T> guarded(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is MlsVaultUnavailableException) throw error
        throw MlsVaultUnavailableException(error)
    }

    companion object {
        const val SIGN_METHOD = "signLeafBindingV1/1"
        /** Live journal records per persona-installation (§6.2). */
        const val MAX_JOURNAL_RECORDS = 1024
        /** An operation may be at most this far in the future (inclusive). */
        const val MAX_OPERATION_SECONDS = 600L
        /** The largest unsigned body (8,125 bytes) is 10,836 base64 characters. */
        internal const val MAX_BODY_BASE64 = 10_836
        private const val RECORD_VERSION = 1
        private const val AAD_PREFIX = "kithmoot.mls-vault.v1"
        internal const val INSTALLATION = "installation"
        internal const val EPOCH = "epoch"
        internal const val INDEX = "coord-index"

        /** A coordinated vault (P3-03b-2) over [coordination]'s stores. */
        fun coordinated(
            coordination: VaultCoordination,
            now: () -> Long = { System.currentTimeMillis() / 1000 },
            appGeneration: () -> Long = { 0L },
            random: SecureRandom = SecureRandom(),
        ): MlsVault = MlsVault(coordination.stores, now, appGeneration, random, coordination = coordination)
        internal val HEX64 = Regex("^[0-9a-f]{64}$")

        /** The replies a vault made, by identity, and what each was made for. */
        private val made: MutableMap<Any, Origin> = Collections.synchronizedMap(WeakHashMap())
    }
}

/**
 * What a coordinated vault needs besides its stores: the witness seam (the
 * engine in debug builds, a refusing stub in release) and each persona's
 * witness channel.
 */
class VaultCoordination(
    val stores: CoordinatedVaultStores,
    val channels: WitnessChannels,
    val witness: VaultWitness = vaultWitness(),
)

/** A retiring duty that could not run: [subject] is the marker's, for the keeper's retire line. */
class RetiringDutyFailure(val subject: String?, val error: Exception)

/** A sealed store per name. [lockName] names the process-wide lock its vault shares. */
interface MlsVaultStores {
    val lockName: String
    /** The store for [name]; every value in it is sealed with [aad] bound in. */
    fun open(name: String, aad: ByteArray): RoomStorage
}

/** One lock per name for the whole process, so two vault objects over one store never write at once (S24). */
object MlsVaultLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()
    fun named(name: String): Mutex = locks.getOrPut(name) { Mutex() }
}

/** A sealed record could not be read or written: refuse, and re-enrol. Never a plaintext fallback. */
class MlsVaultUnavailableException(cause: Exception) : Exception("The MLS vault is unavailable", cause)

// ---- public shapes ----

/** The stable refusal strings of §6.2. */
enum class VaultRefusal(
    /** The §6.2 refusal string; null for an Android-only refusal that never goes on any wire. */
    val wire: String?,
) {
    Unsupported("unsupported"), Malformed("malformed"), Unauthorised("unauthorised"), Expired("expired"),
    Revoked("revoked"), Denied("denied"), Busy("busy"), Stale("stale"), Replay("replay"), RestoreFenced("restore-fenced"),
    /**
     * Coordinated mode, Kotlin-only: the witness has not confirmed this write
     * (or the persona's state) yet; nothing was signed or released. It has no
     * §6.2 string, so an adapter must never serialise it as a refusal.
     */
    WitnessPending(null),
}

sealed class VaultResult<out T> {
    data class Ok<T>(val value: T) : VaultResult<T>()
    data class Refused(val refusal: VaultRefusal) : VaultResult<Nothing>()

    internal inline fun <R> map(transform: (T) -> R): VaultResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Refused -> this
    }
}

/**
 * The vault generation an operation started under: per process, the app's and
 * the vault's own, and in coordinated mode the durable session epoch.
 */
internal data class Generation(val boot: Long, val app: Long, val own: Long, val epoch: String = "")

/**
 * Who is asking, taken from the authenticated app, never from a request: the
 * app principal, the selected persona (identity public key) and the vault
 * generation the operation started under.
 */
class VaultContext internal constructor(val principal: String, val persona: String, internal val generation: Generation)

/** One consent scope (§6.2): approved once, retained revocably. */
data class ConsentScope(val principal: String, val persona: String, val device: String, val homeBox: String, val method: String) {
    internal fun wellFormed(): Boolean = principal.isNotEmpty() && MlsVault.HEX64.matches(persona) &&
        MlsVault.HEX64.matches(device) && MlsVault.HEX64.matches(homeBox) && method == MlsVault.SIGN_METHOD
}

enum class ConsentDecision { Approve, Deny }

/** Asks the person about a new scope. Suspends, because it is a prompt. */
fun interface ConsentPrompt { suspend fun ask(scope: ConsentScope): ConsentDecision }

data class EnrolledDevice(val persona: String, val device: String, val credentialId: String, val credentialExpiresAt: Long)

/** `{v:1, operation, digest, device, signature}`. Only this vault makes one; equality is identity. */
class SignLeafBindingReply internal constructor(val operation: String, val digest: String, val device: String, val signature: String) {
    val v: Int get() = 1
    override fun toString(): String = "SignLeafBindingReply(operation=$operation, device=$device)"
}

/** `{v:1, operation, peer_rz, own_rz, shared_x}`. Only this vault makes one; equality is identity. */
class RendezvousEcdhReply internal constructor(val operation: String, val peerRz: String, val ownRz: String, val sharedX: String) {
    val v: Int get() = 1
    override fun toString(): String = "RendezvousEcdhReply(operation=$operation, peer_rz=$peerRz)"
}

// ---- requests ----

private data class SignRequest(val operation: String, val body: String, val digest: String, val expiresAt: Long) {
    val key get() = "$operation|$digest|$body|$expiresAt"
}

private data class EcdhRequest(val operation: String, val peer: String, val expiresAt: Long) {
    val key get() = "$operation|$peer|$expiresAt"
}

private class Origin(val vault: MlsVault, val generation: Generation, val request: String)

/** What the first locked look at a sign request found: a decision already made, or a scope to ask about. */
private sealed class FirstLook {
    class Done(val result: VaultResult<SignLeafBindingReply>) : FirstLook()
    class Ask(val scope: ConsentScope, val approved: Boolean) : FirstLook()
}

private val UINT = Regex("^(0|[1-9][0-9]{0,15})$")

/** A version field that is present and anything but the number 1 is `unsupported`. */
private fun unsupportedVersion(value: JsonObject): Boolean {
    val v = value["v"] ?: return false
    return !(v is JsonPrimitive && !v.isString && v.content == "1")
}

private fun JsonObject.hex64(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { MlsVault.HEX64.matches(it) }

private fun JsonObject.uint(name: String): Long? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString && UINT.matches(it.content) }?.content?.toLong()?.takeIf { it <= LeafBinding.MAX_SAFE }

private fun signRequestShape(value: JsonElement): VaultResult<SignRequest> {
    if (value !is JsonObject) return refuse(VaultRefusal.Malformed)
    if (unsupportedVersion(value)) return refuse(VaultRefusal.Unsupported)
    if (value.keys != setOf("v", "operation", "body", "digest", "expires_at")) return refuse(VaultRefusal.Malformed)
    val operation = value.hex64("operation") ?: return refuse(VaultRefusal.Malformed)
    val digest = value.hex64("digest") ?: return refuse(VaultRefusal.Malformed)
    val body = (value["body"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (body == null || body.isEmpty() || body.length > MlsVault.MAX_BODY_BASE64) return refuse(VaultRefusal.Malformed)
    val expiresAt = value.uint("expires_at") ?: return refuse(VaultRefusal.Malformed)
    return VaultResult.Ok(SignRequest(operation, body, digest, expiresAt))
}

private fun ecdhRequestShape(value: JsonElement): VaultResult<EcdhRequest> {
    if (value !is JsonObject) return refuse(VaultRefusal.Malformed)
    if (unsupportedVersion(value)) return refuse(VaultRefusal.Unsupported)
    if (value.keys != setOf("v", "operation", "peer_rz", "expires_at")) return refuse(VaultRefusal.Malformed)
    val operation = value.hex64("operation") ?: return refuse(VaultRefusal.Malformed)
    val peer = value.hex64("peer_rz") ?: return refuse(VaultRefusal.Malformed)
    val expiresAt = value.uint("expires_at") ?: return refuse(VaultRefusal.Malformed)
    return VaultResult.Ok(EcdhRequest(operation, peer, expiresAt))
}

/** Canonical padded base64 only: what decodes must re-encode identically. */
private fun base64Decode(text: String): ByteArray? {
    if (text.length % 4 != 0 || !BASE64.matches(text)) return null
    val bytes = try { Base64.getDecoder().decode(text) } catch (_: IllegalArgumentException) { return null }
    return bytes.takeIf { Base64.getEncoder().encodeToString(it) == text }
}

private val BASE64 = Regex("^[A-Za-z0-9+/]*={0,2}$")

private fun refusalOf(error: BindingException): VaultRefusal = when (error.code) {
    BindingErrorCode.UnsupportedVersion -> VaultRefusal.Unsupported
    BindingErrorCode.CredentialRevoked -> VaultRefusal.Revoked
    BindingErrorCode.CredentialExpired, BindingErrorCode.BindingExpired -> VaultRefusal.Expired
    BindingErrorCode.CredentialNotPersonScoped, BindingErrorCode.CredentialWrongIdentity, BindingErrorCode.CredentialWrongDevice,
    BindingErrorCode.CredentialNotYetValid, BindingErrorCode.CredentialLifetimeTooLong, BindingErrorCode.BindingOutlivesCredential,
    -> VaultRefusal.Unauthorised
    else -> VaultRefusal.Malformed
}

private fun refuse(refusal: VaultRefusal): VaultResult.Refused = VaultResult.Refused(refusal)

// ---- the sealed persona record ----

/** The device key, kept as bytes so it is never a `String`; wiped after each use. */
private class DeviceRecord(val scalar: ByteArray, val device: String, val credential: NostrEvent, val credentialId: String, val credentialExpiresAt: Long) {
    fun wipe() = scalar.fill(0)
}

private data class JournalEntry(
    /** The vault generation it was decided under: a retry from another
     * generation is stale, never a replay (S25, E06). */
    val generation: Generation,
    val principal: String,
    val handle: String,
    val operation: String,
    val bodyHash: String,
    val digest: String,
    val deadline: Long,
    /** The signature for an approval; null for a durable denial. */
    val signature: String?,
    val homeBox: String?,
)

/**
 * Everything the vault keeps for one persona, sealed as one value:
 * `version (1) | has device (1) | scalar (32, if any) | JSON`. The scalar sits
 * outside the JSON so it never becomes a string.
 */
private class PersonaRecord(
    val persona: String,
    var device: DeviceRecord? = null,
    val approved: MutableList<ConsentScope> = mutableListOf(),
    val revoked: MutableList<String> = mutableListOf(),
    val journal: MutableList<JournalEntry> = mutableListOf(),
) {
    fun entry(principal: String, handle: String, operation: String): JournalEntry? =
        journal.firstOrNull { it.principal == principal && it.handle == handle && it.operation == operation }

    fun encode(): ByteArray {
        val json = buildJsonObject {
            put("persona", persona)
            device?.let { d ->
                put("device", buildJsonObject {
                    put("device", d.device)
                    put("credential", d.credential.toJson())
                    put("credential_id", d.credentialId)
                    put("credential_expires_at", d.credentialExpiresAt)
                })
            }
            put("approved", buildJsonArray {
                for (s in approved) addJsonObject {
                    put("principal", s.principal); put("persona", s.persona); put("device", s.device)
                    put("home_box", s.homeBox); put("method", s.method)
                }
            })
            put("revoked", buildJsonArray { revoked.forEach { add(it) } })
            put("journal", buildJsonArray {
                for (e in journal) addJsonObject {
                    put("generation", buildJsonArray { add(e.generation.boot); add(e.generation.app); add(e.generation.own) })
                    if (e.generation.epoch.isNotEmpty()) put("epoch", e.generation.epoch)
                    put("principal", e.principal); put("handle", e.handle); put("operation", e.operation)
                    put("body_hash", e.bodyHash); put("digest", e.digest); put("deadline", e.deadline)
                    e.signature?.let { put("signature", it) }
                    e.homeBox?.let { put("home_box", it) }
                }
            })
        }.toString().toByteArray(Charsets.UTF_8)
        val scalar = device?.scalar
        val out = ByteArray(2 + (scalar?.size ?: 0) + json.size)
        out[0] = FORMAT
        out[1] = if (scalar != null) 1 else 0
        scalar?.copyInto(out, 2)
        json.copyInto(out, 2 + (scalar?.size ?: 0))
        json.fill(0)
        return out
    }

    companion object {
        const val FORMAT: Byte = 1

        fun decode(bytes: ByteArray, persona: String): PersonaRecord {
            require(bytes.size >= 2 && bytes[0] == FORMAT && (bytes[1] == 0.toByte() || bytes[1] == 1.toByte()))
            val hasDevice = bytes[1] == 1.toByte()
            val offset = if (hasDevice) 34 else 2
            require(bytes.size > offset)
            val json = Json.parseToJsonElement(String(bytes, offset, bytes.size - offset, Charsets.UTF_8)).jsonObject
            require(json.text("persona") == persona)
            val device = json["device"]?.jsonObject?.let { d ->
                require(hasDevice)
                val scalar = bytes.copyOfRange(2, 34)
                val record = DeviceRecord(
                    scalar, d.text("device"), NostrEvent.fromJson(d.getValue("credential")),
                    d.text("credential_id"), d.getValue("credential_expires_at").jsonPrimitive.long,
                )
                if (Schnorr.publicKeyHex(scalar) != record.device) { record.wipe(); throw IllegalStateException("The MLS device key does not match its record.") }
                record
            }
            require(hasDevice == (device != null))
            return PersonaRecord(
                persona,
                device,
                json.getValue("approved").jsonArray.map { it.jsonObject }
                    .map { ConsentScope(it.text("principal"), it.text("persona"), it.text("device"), it.text("home_box"), it.text("method")) }
                    .toMutableList(),
                json.getValue("revoked").jsonArray.map { it.jsonPrimitive.content }.toMutableList(),
                json.getValue("journal").jsonArray.map { it.jsonObject }.map { e ->
                    val g = e.getValue("generation").jsonArray.map { it.jsonPrimitive.long }
                    require(g.size == 3)
                    JournalEntry(
                        Generation(g[0], g[1], g[2], e["epoch"]?.jsonPrimitive?.content ?: ""), e.text("principal"), e.text("handle"), e.text("operation"),
                        e.text("body_hash"), e.text("digest"), e.getValue("deadline").jsonPrimitive.long,
                        e["signature"]?.jsonPrimitive?.content, e["home_box"]?.jsonPrimitive?.content,
                    )
                }.toMutableList(),
            )
        }

        private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.also { require(it.isString) }.content
    }
}
