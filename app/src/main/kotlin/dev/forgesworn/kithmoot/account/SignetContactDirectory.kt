package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Entropy
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.signet.contacts.ContactsSigner
import dev.forgesworn.signet.contacts.RelayIo
import dev.forgesworn.signet.contacts.RelaySubscription
import dev.forgesworn.signet.contacts.SignetContactsClient
import dev.forgesworn.signet.contacts.StorageIo
import dev.forgesworn.signet.contacts.wire.Capability
import dev.forgesworn.signet.contacts.wire.ContactsState
import dev.forgesworn.signet.contacts.wire.DirectoryKind
import dev.forgesworn.signet.contacts.wire.NostrFilter
import dev.forgesworn.signet.contacts.wire.PairingCodeInput
import dev.forgesworn.signet.contacts.wire.PairingV2
import dev.forgesworn.signet.contacts.wire.ProjectedTier
import dev.forgesworn.signet.contacts.wire.ProjectedVerification
import dev.forgesworn.signet.contacts.wire.SignedNostrEvent
import dev.forgesworn.signet.contacts.wire.UnsignedNostrEvent
import dev.forgesworn.signet.contacts.wire.emptyContactsState
import dev.forgesworn.signet.contacts.wire.formatPairingCode
import dev.forgesworn.signet.contacts.wire.pairingCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One person in the Signet directory. [tier] (Kin, Kith or Ken) and [checked]
 *  come only with a grant that shares them, and only until the directory goes
 *  stale: they are what people act on, so they go first. */
data class SignetContact(val pubkey: String, val name: String?, val tier: String? = null, val checked: Boolean = false)
data class SignetDirectoryView(val status: String = "disconnected", val contacts: List<SignetContact> = emptyList(),
    val blocked: Set<String> = emptySet(), val truncated: Boolean = false, val error: String? = null)
/** A pairing request, with every permission it asked Signet for. */
data class SignetContactPairing(val uri: String, val challenge: String, val scopes: List<Capability>)

/** What KithMoot asks Signet for beyond names, keys and blocks, only when the
 *  person ticks it: Kin, Kith or Ken, and whether a key has been checked.
 *  Android shows no check methods or dates, so it does not ask for them. */
fun signetContactExtras(tiers: Boolean, checks: Boolean): List<Capability> =
    listOfNotNull(Capability.READ_TIER.takeIf { tiers }, Capability.READ_CHECKS.takeIf { checks })

/** Same names-only expiry rule as the browser. A directory is not a room
 * admission grant, and stale names carry no tier or verification authority. */
fun signetDirectoryView(account: String, pairing: PairingV2?, state: ContactsState, healthy: Boolean, now: Long): SignetDirectoryView {
    val blocked = state.blockedPubkeys.toSet()
    if (!healthy || blocked.any { !HEX.matches(it) }) return SignetDirectoryView("unavailable", blocked = blocked)
    if (pairing == null) return SignetDirectoryView()
    if (state.revoked) return SignetDirectoryView("revoked", blocked = blocked)
    val p = state.projection ?: return SignetDirectoryView("waiting", blocked = blocked)
    if (p.grantId != pairing.grantId || state.grantId != pairing.grantId || p.issuedAt > now || p.frontier.publishedAt > now ||
        p.expiresAt - p.issuedAt > pairing.maxStalenessSeconds || p.scopes.any { it !in pairing.grantedCapabilities } ||
        !p.scopes.containsAll(BASIC_SCOPES) || !pairing.grantedCapabilities.containsAll(BASIC_SCOPES)) return SignetDirectoryView("unavailable", blocked = blocked)
    if (p.revoked) return SignetDirectoryView("revoked", blocked = blocked)
    val allBlocked = blocked + p.contacts.filter { it.blocked == true }.flatMap { it.identities.orEmpty().map { i -> i.pubkey } }
    if (now >= p.expiresAt + 30 * 86400) return SignetDirectoryView("lapsed", blocked = allBlocked)
    val expired = now >= p.expiresAt
    fun trusted(scope: Capability) = !expired && scope in p.scopes && scope in pairing.grantedCapabilities
    val contacts = linkedMapOf<String, SignetContact>()
    for (row in p.contacts) for (identity in row.identities.orEmpty()) {
        if (identity.pubkey == account || identity.pubkey in allBlocked) continue
        val previous = contacts[identity.pubkey]
        val tier = row.effectiveTier.takeIf { trusted(Capability.READ_TIER) }
        val checked = trusted(Capability.READ_CHECKS) &&
            identity.verification in setOf(ProjectedVerification.PROVEN, ProjectedVerification.MUTUAL)
        contacts[identity.pubkey] = if (previous == null) SignetContact(identity.pubkey, row.displayName, tierLabel(tier), checked)
            // One key under two contacts: anything they disagree on is dropped, and the lower tier stands (as on the web).
            else SignetContact(identity.pubkey, previous.name.takeIf { it == row.displayName },
                lowerTier(previous.tier, tierLabel(tier)), previous.checked && checked)
    }
    return SignetDirectoryView(if (now >= p.expiresAt) "stale" else "ready", contacts.values.toList(), allBlocked, p.truncated == true)
}

private val HEX = Regex("^[0-9a-f]{64}$")
private val TIER_ORDER = listOf("Ken", "Kith", "Kin")
/** Kin, Kith or Ken as People shows it; none at all when Signet says none or nothing. */
private fun tierLabel(tier: ProjectedTier?): String? = when (tier) {
    ProjectedTier.KIN -> "Kin"; ProjectedTier.KITH -> "Kith"; ProjectedTier.KEN -> "Ken"; else -> null
}
private fun lowerTier(a: String?, b: String?): String? = if (a == null || b == null) null else minOf(a, b, compareBy(TIER_ORDER::indexOf))
private val BASIC_SCOPES = listOf(Capability.READ_DIRECTORY, Capability.BLOCKS_READ)
private val OPTIONAL_SCOPES = setOf(Capability.READ_TIER, Capability.READ_CHECKS)
private fun NostrEvent.sdk() = SignedNostrEvent(id, kind, pubkey, createdAt, tags, content, sig)
private fun SignedNostrEvent.native() = NostrEvent(kind, createdAt, tags, content, pubkey, id, sig)
private fun NostrFilter.native() = Filter(kinds = kinds, authors = authors, tags = tags, since = since, until = until, limit = limit)

data class ContactsRelay(val transport: RoomTransport, val close: () -> Unit)

/** The shared contacts SDK owns the wire. This adapter supplies KithMoot's
 * existing signer, verified relay transport and exclusive encrypted vault. */
class SignetContactDirectory(private val actor: ParticipantSigner, private val storage: ProjectStorage,
    private val scope: CoroutineScope, private val relay: (List<String>) -> ContactsRelay,
    private val current: () -> Boolean, private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val elapsed: () -> Long = { System.nanoTime() / 1_000_000 },
    private val beforeNetwork: suspend () -> Unit = {}) {
    private val gate = Mutex()
    private val storeGate = Mutex()
    private val mutable = MutableStateFlow(SignetDirectoryView())
    val state = mutable.asStateFlow()
    private var values = emptyMap<String, String>()
    private var pairing: PairingV2? = null
    @Volatile private var healthy = true
    @Volatile private var closed = false
    private var opened = false
    private var poll: Job? = null
    private var pairingJob: Job? = null
    private fun live() = check(!closed && current()) { "The signed-in account changed" }
    private val signer = object : ContactsSigner {
        override val pubkey get() = actor.pubkey
        override suspend fun nip44Encrypt(peer: String, plaintext: String): String { live(); val result = actor.nip44Encrypt(peer, plaintext); live(); return result }
        override suspend fun nip44Decrypt(peer: String, payload: String): String { live(); val result = actor.nip44Decrypt(peer, payload); live(); return result }
        override suspend fun signEvent(event: UnsignedNostrEvent): SignedNostrEvent {
            live(); val result = checkedSignedEvent(actor.sign(event.kind, event.createdAt, event.tags, event.content), actor.pubkey,
                event.kind, event.createdAt, event.tags, event.content); live(); return result.sdk()
        }
    }
    private val sdk: SignetContactsClient = SignetContactsClient(signer, object : RelayIo {
        override suspend fun fetchMany(filter: NostrFilter, relays: List<String>, author: String?): List<SignedNostrEvent> {
            beforeNetwork(); live(); val connection = relay(relays)
            return try {
                connection.transport.queryAvailable(listOf(filter.native())).filter { Events.verify(it) && (author == null || it.pubkey == author) }
                    .sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).distinctBy { it.id }.take(filter.limit ?: 256).map { it.sdk() }
            } finally { connection.close() }
        }
        override suspend fun fetchNewest(filter: NostrFilter, relays: List<String>, author: String?): SignedNostrEvent? = fetchMany(filter, relays, author).firstOrNull()
        override suspend fun publish(event: SignedNostrEvent, relays: List<String>): Boolean {
            beforeNetwork(); live(); val connection = relay(relays)
            return try { connection.transport.publishConfirmedGuarded(event.native(), connection.transport.publicationGeneration(), { !closed && current() }) }
            finally { connection.close() }
        }
        override fun subscribe(filter: NostrFilter, relays: List<String>, onEvent: (SignedNostrEvent) -> Unit): RelaySubscription {
            live(); val connection = relay(relays)
            val job = scope.launch { connection.transport.subscribe(listOf(filter.native())).collect { event -> if (!closed && current() && Events.verify(event)) onEvent(event.sdk()) } }
            return RelaySubscription { job.cancel(); connection.close() }
        }
    }, object : StorageIo {
        override suspend fun get(key: String): String? { live(); return storeGate.withLock { values[key] } }
        override suspend fun set(key: String, value: String) {
            try { storeGate.withLock { live(); save(values + (key to value), pairing) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { healthy = false; throw e }
        }
    }, now = now, elapsedMs = elapsed, scope = scope)

    private suspend fun save(next: Map<String, String>, selected: PairingV2?) {
        live()
        check(next.size <= 32 && next.values.sumOf { it.length } <= 2 * 1024 * 1024) { "Contact cache limit reached" }
        val text = buildJsonObject { put("v", 1); put("account", actor.pubkey); put("pairing", selected?.toJson()?.stringify()?.let(::JsonPrimitive) ?: JsonNull)
            put("values", JsonObject(next.mapValues { JsonPrimitive(it.value) })) }.toString()
        storage.save(text); live(); values = next; pairing = selected
    }
    private fun paint() { if (!closed && current()) mutable.value = signetDirectoryView(actor.pubkey, pairing, sdk.getState(), healthy, now()) }
    suspend fun open() = gate.withLock {
        check(!opened); live(); storage.acquire(); opened = true
        try {
            storage.load()?.let { raw ->
                check(raw.length <= 3 * 1024 * 1024)
                val saved = Json.parseToJsonElement(raw).jsonObject
                check(saved["v"] == JsonPrimitive(1) && saved["account"] == JsonPrimitive(actor.pubkey))
                val rawPairing = saved["pairing"]
                pairing = if (rawPairing == JsonNull) null else PairingV2.fromJson(rawPairing!!.jsonPrimitive.content).also { check(it != null) }
                values = saved.getValue("values").jsonObject.mapValues { it.value.jsonPrimitive.content }
                check(values.size <= 32)
            }
            pairing?.let {
                values["signet-contacts:state:${it.grantId}"]?.let { raw ->
                    val row = Json.parseToJsonElement(raw).jsonObject
                    check(row["grantId"] == JsonPrimitive(it.grantId))
                    check(row.getValue("blockedPubkeys").let { blocks -> blocks is kotlinx.serialization.json.JsonArray && blocks.all { key -> HEX.matches(key.jsonPrimitive.content) } })
                    check(row["revoked"] in listOf(JsonPrimitive(true), JsonPrimitive(false)))
                }
                sdk.load(it.grantId)
            }
            paint()
            poll = scope.launch { while (isActive && !closed && current()) { refresh(); delay(30_000) } }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { healthy = false; mutable.value = SignetDirectoryView("unavailable", error = "Your saved Signet contacts need attention. No contact permissions are being used.") }
    }
    suspend fun refresh() = gate.withLock {
        live(); if (!healthy) { paint(); return@withLock }
        pairing?.let { sdk.fetchProjection(it) }; paint()
    }
    fun beginPairing(rendezvousRelay: String, extras: List<Capability> = emptyList()): SignetContactPairing {
        require(extras.all { it in OPTIONAL_SCOPES }) { "Unsupported contact permission" }
        live(); check(opened && healthy)
        val challenge = Entropy.bytes(16).toHex()
        val scopes = (BASIC_SCOPES + extras).distinct()
        return SignetContactPairing(sdk.buildPairingUri("KithMoot", scopes, DirectoryKind.OWNER, rendezvousRelay, now(), challenge), challenge, scopes)
    }
    suspend fun awaitPairing(request: SignetContactPairing, rendezvousRelay: String, confirm: suspend (String) -> Boolean): Boolean {
        live()
        pairingJob?.cancel()
        val operation = currentCoroutineContext()[Job]
        pairingJob = operation
        try {
        val next = sdk.awaitPairingAck(request.challenge, listOf(rendezvousRelay), requestedCapabilities = request.scopes) ?: return false
        live(); check(next.grantedCapabilities.containsAll(BASIC_SCOPES)) { "Share names, keys and blocks in Signet." }
        val code = formatPairingCode(pairingCode(PairingCodeInput(actor.pubkey, request.challenge, next.grantId, next.railPubkey)))
        if (!confirm(code)) return false
        live()
        gate.withLock {
            storeGate.withLock {
                // SDK.load intentionally leaves live state alone when no row
                // exists. Seed a newly approved grant so the previous grant
                // cannot reject its projection. Keep known blocks until the
                // new grant supplies its own authenticated block projection.
                val stateKey = "signet-contacts:state:${next.grantId}"
                val pendingKey = "signet-contacts:pending:${next.grantId}"
                val selected = values.filterKeys { it == stateKey || it == pendingKey }.toMutableMap()
                selected.putIfAbsent(stateKey, ContactsState(next.grantId, null, 0, sdk.getState().blockedPubkeys, false).toJson().stringify())
                save(selected, next)
            }
            sdk.load(next.grantId)
        }
        refresh(); return healthy
        } finally { if (pairingJob === operation) pairingJob = null }
    }
    fun cancelPairing() { pairingJob?.cancel(); pairingJob = null }
    fun allowed(peer: String): Boolean {
        if (closed || !current()) return false
        val view = signetDirectoryView(actor.pubkey, pairing, sdk.getState(), healthy, now())
        return view.status in setOf("ready", "stale") && peer !in view.blocked && view.contacts.any { it.pubkey == peer }
    }
    fun blocked(peer: String): Boolean {
        val view = signetDirectoryView(actor.pubkey, pairing, sdk.getState(), healthy, now())
        return view.status == "unavailable" || peer in view.blocked
    }
    suspend fun close() {
        closed = true; pairingJob?.cancel(); poll?.cancel(); sdk.close()
        gate.withLock { storeGate.withLock { if (opened) storage.release(); opened = false; values = emptyMap(); pairing = null } }
        mutable.value = SignetDirectoryView()
    }
}
