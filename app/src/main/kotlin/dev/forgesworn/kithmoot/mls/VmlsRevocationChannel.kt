package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.LinkRelayAddress
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** A keeper's own ledger is the only authority for a request's identity and scope. */
fun authorisedRevocation(request: VmlsRevocationRequest, keeper: String, ledger: List<VmlsGrantRecord>, members: List<VmlsRoomMember>): Boolean =
    request.keeper == keeper && request.sender != keeper &&
        ledger.any { it.issuer == keeper && it.persona == request.sender && it.device == request.device } &&
        members.filter { it.device == request.device }.all { it.identity == request.sender }

/** Terminal decisions stay deduped; approval is retained before any box mutation. */
enum class RevocationDecision { PENDING, APPROVED, DONE, DENIED, UNAVAILABLE }
data class RevocationEntry(
    val request: VmlsRevocationRequest,
    val decision: RevocationDecision = RevocationDecision.PENDING,
    val sent: Boolean = false,
    val deferredUntil: Long = 0,
    val privateDestination: VmlsPrivateRequestDestination? = null,
    val earlierPublicAttempt: Boolean = privateDestination == null,
    val privateEventId: String? = null,
)
class RevocationBook(
    val inbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val outbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val seen: MutableMap<String, Long> = linkedMapOf(),
    val scanUntil: MutableMap<String, Long> = linkedMapOf(),
    val privateScanUntil: MutableMap<String, Long> = linkedMapOf(),
    var nextRelay: Int = 0,
    var nextPollAt: Long = 0,
    val promptAfter: MutableMap<String, Long> = linkedMapOf(),
    /** Known public attempts survive expired transport entries; absence is not proof of no older traffic. */
    val publicAttempts: MutableSet<String> = linkedSetOf(),
) {
    fun encode(): ByteArray = buildJsonObject {
        put("version", 2)
        fun entries(values: Map<String, RevocationEntry>) = buildJsonObject {
            values.forEach { (key, e) -> put(key, buildJsonObject {
                put("rumor", e.request.rumor()); put("decision", e.decision.name); put("sent", e.sent); put("deferredUntil", e.deferredUntil)
                e.privateDestination?.let { put("privateDestination", it.json()) }
                put("earlierPublicAttempt", e.earlierPublicAttempt || e.privateDestination == null)
                e.privateEventId?.let { put("privateEventId", it) }
            }) }
        }
        put("inbox", entries(inbox)); put("outbox", entries(outbox))
        put("publicAttempts", JsonArray((publicAttempts + outbox.filterValues { it.earlierPublicAttempt || it.privateDestination == null }.keys).sorted().map(::JsonPrimitive)))
        put("scanUntil", buildJsonObject { scanUntil.forEach { (relay, until) -> put(relay, until) } })
        put("privateScanUntil", buildJsonObject { privateScanUntil.forEach { (route, until) -> put(route, until) } })
        put("nextRelay", nextRelay); put("nextPollAt", nextPollAt)
        put("promptAfter", buildJsonObject { promptAfter.forEach { (sender, at) -> put(sender, at) } })
        put("seen", buildJsonObject { seen.forEach { (id, at) -> put(id, at) } })
    }.toString().encodeToByteArray()

    companion object {
        fun key(request: VmlsRevocationRequest) = "${request.sender}:${request.device}:${request.keeper}"
        fun decode(bytes: ByteArray?): RevocationBook {
            if (bytes == null) return RevocationBook()
            require(bytes.size <= MlsVault.MAX_REQUEST_BYTES)
            val o = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val version = o.getValue("version").jsonPrimitive.also { require(!it.isString) }.int
            require(version == 1 || version == 2)
            // Version 1 readers ignore these fields and would treat private sends as Public.
            // Only the historical Public-only schema may migrate from that version.
            if (version == 1) require("publicAttempts" !in o)
            fun entries(name: String): MutableMap<String, RevocationEntry> = o.getValue(name).jsonObject.also { require(it.size <= 128) }.mapValuesTo(linkedMapOf()) { (key, value) ->
                val e = value.jsonObject
                if (version == 1) require(e.keys.none { it in setOf("privateDestination", "privateEventId", "earlierPublicAttempt") })
                val r = e.getValue("rumor").jsonObject
                val sender = r.getValue("pubkey").jsonPrimitive.content
                val keeper = r.getValue("tags").jsonArray.single { it.jsonArray[0].jsonPrimitive.content == "p" }.jsonArray[1].jsonPrimitive.content
                val request = VmlsRevocationRequest.parse(r.toString(), sender, keeper, r.getValue("created_at").jsonPrimitive.long)
                require(key == key(request))
                val destination = e["privateDestination"]?.let { VmlsPrivateRequestDestination.decode(it.jsonObject) }
                require(destination == null || (name == "outbox" && destination.keeper == request.keeper))
                val privateEvent = e["privateEventId"]?.jsonPrimitive?.also { require(it.isString) }?.content
                require(privateEvent == null || (destination != null && Regex("[0-9a-f]{64}").matches(privateEvent)))
                require(destination == null || e["earlierPublicAttempt"]?.jsonPrimitive?.booleanOrNull != null)
                val sent = e.getValue("sent").jsonPrimitive.boolean
                require(destination == null || !sent || privateEvent != null)
                RevocationEntry(request, RevocationDecision.valueOf(e.getValue("decision").jsonPrimitive.content), sent,
                    e["deferredUntil"]?.jsonPrimitive?.long ?: 0, destination,
                    destination == null || e["earlierPublicAttempt"]?.jsonPrimitive?.boolean == true, privateEvent)
            }
            val seen = o.getValue("seen").jsonObject.also { require(it.size <= 1024) }.mapValuesTo(linkedMapOf()) { (id, at) ->
                require(Regex("[0-9a-f]{64}").matches(id)); at.jsonPrimitive.long.also { require(it >= 0) }
            }
            val prompts = o["promptAfter"]?.jsonObject.orEmpty().also { require(it.size <= 128) }.mapValuesTo(linkedMapOf()) { (sender, at) ->
                require(Regex("[0-9a-f]{64}").matches(sender)); at.jsonPrimitive.long
            }
            val cursors = (o["scanUntil"] as? JsonObject).orEmpty().also { require(it.size <= MAX_DM_RELAYS) }
                .mapValuesTo(linkedMapOf()) { (_, until) -> until.jsonPrimitive.long }
            val privateCursors = o["privateScanUntil"]?.jsonObject.orEmpty().also { require(it.size <= 32) }
                .mapValuesTo(linkedMapOf()) { (key, until) ->
                    val route = Json.parseToJsonElement(key).jsonArray
                    require(route.size == 3 && route.take(2).all { Regex("[0-9a-f]{64}").matches(it.jsonPrimitive.content) } &&
                        Regex("[A-Za-z0-9._:-]{1,128}").matches(route[2].jsonPrimitive.content))
                    until.jsonPrimitive.long.also { require(it >= 0) }
                }
            val outbox = entries("outbox")
            val publicAttempts = o["publicAttempts"]?.jsonArray?.also { require(it.size <= 128) }?.mapTo(linkedSetOf()) {
                val key = it.jsonPrimitive.also { p -> require(p.isString) }.content
                require(Regex("[0-9a-f]{64}:[0-9a-f]{64}:[0-9a-f]{64}").matches(key)); key
            } ?: linkedSetOf()
            publicAttempts.addAll(outbox.filterValues { it.earlierPublicAttempt || it.privateDestination == null }.keys)
            require(publicAttempts.size <= 128)
            outbox.replaceAll { key, entry -> if (key in publicAttempts) entry.copy(earlierPublicAttempt = true) else entry }
            return RevocationBook(entries("inbox"), outbox, seen, cursors, privateCursors,
                (o["nextRelay"]?.jsonPrimitive?.int ?: 0).also { require(it in 0 until MAX_DM_RELAYS) },
                o["nextPollAt"]?.jsonPrimitive?.long ?: 0, prompts, publicAttempts)
        }
    }
}

/**
 * One channel per runtime. All mutations serialize, and every signer reply and publication
 * is checked against the account session and Tor-only pause. Nothing here approves a request.
 */
class VmlsRevocationChannel(
    private val vault: MlsVault,
    private val carriers: (List<String>, ParticipantSigner?) -> VmlsCarrier,
    private val directoryRelays: () -> List<String>,
    private val allowed: () -> Boolean,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private val directories = mutableMapOf<String, Pair<Long, List<String>>>()
    private fun check(ctx: VaultContext, current: () -> Boolean = { true }) { check(current() && allowed() && vault.isCurrent(ctx)) { "This account session ended or relay traffic is paused." } }
    private suspend fun read(ctx: VaultContext, current: () -> Boolean = { true }): RevocationBook {
        check(ctx, current)
        val read = vault.revocationRequests(ctx) as? VaultResult.Ok ?: error("The restore witness has not confirmed the request journal.")
        check(ctx, current)
        return RevocationBook.decode(read.value)
    }
    private suspend fun write(ctx: VaultContext, book: RevocationBook, current: () -> Boolean = { true }, expectedDevice: String? = null): EnrolledDevice? {
        check(ctx, current)
        val kept = if (expectedDevice == null) vault.keepRevocationRequests(ctx, book.encode(), current)
            else vault.keepRevocationRequests(ctx, book.encode(), expectedDevice, current)
        check(kept is VaultResult.Ok) { "The request journal could not be witnessed. Try again." }
        check(ctx, current)
        return if (expectedDevice == null) null else (kept as VaultResult.Ok).value as EnrolledDevice
    }
    suspend fun entries(ctx: VaultContext): RevocationBook = lock.withLock { read(ctx) }
    private fun guarded(ctx: VaultContext, signer: ParticipantSigner, current: () -> Boolean = { true }): ParticipantSigner = object : ParticipantSigner by signer {
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
            check(ctx, current)
            return signer.sign(kind, createdAt, tags, content).also { check(ctx, current) }
        }
    }


    private suspend fun dmRelays(ctx: VaultContext, identity: String): List<String> {
        check(ctx)
        directories[identity]?.takeIf { it.first > now() }?.let { return it.second }
        val directories = directoryRelays().distinct().take(12)
        if (directories.isEmpty()) return emptyList()
        val events = mutableListOf<NostrEvent>()
        carriers(directories, null).use { carrier ->
            withTimeoutOrNull(5_000) {
                carrier.subscribe(listOf(Filter(authors = listOf(identity), kinds = listOf(KIND_DM_RELAYS), limit = 8)))
                    .take(32).collect { if (it.createdAt <= now() + 600) events += it }
            }
        }
        check(ctx)
        return latestDmRelayList(events, identity).also { this.directories[identity] = (now() + 900) to it }
    }

    /** False is never recorded as sent; no relay list means no fallback publication. */
    suspend fun send(ctx: VaultContext, signer: ParticipantSigner, request: VmlsRevocationRequest, resend: Boolean = false): Boolean = lock.withLock {
        check(signer.pubkey == ctx.persona && request.sender == ctx.persona)
        val book = read(ctx); val key = RevocationBook.key(request)
        val prepared = reserveSend(book, request, null, resend)
        if (prepared.sent && !resend) return@withLock true
        write(ctx, book)
        val combined = prepared.request
        val relays = dmRelays(ctx, request.keeper)
        check(relays.isNotEmpty()) { "The keeper has no DM relay list. No revocation request was sent." }
        val wrap = VmlsRequestEnvelope.wrap(combined, signer, { allowed() && vault.isCurrent(ctx) })
        check(ctx)
        val sent = carriers(relays, null).use { it.publish(wrap) } // Never disclose the sender through identity AUTH.
        check(ctx)
        if (sent) { book.outbox[key] = prepared.copy(sent = true); write(ctx, book) }
        sent
    }

    /** Explicit private send. It cannot reach public directories or carrier factories. */
    suspend fun sendPrivately(ctx: VaultContext, signer: ParticipantSigner, request: VmlsRevocationRequest,
        target: VmlsPrivateInboxTarget, prompt: KeeperInboxConsentPrompt, publisher: VmlsPrivateInboxPublisher,
        operationCurrent: () -> Boolean, resend: Boolean = false): Boolean {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val frozenRequest = request.copy(sessions = request.sessions.toList(), boxes = request.boxes.toList())
        require(signer.pubkey == ctx.persona && frozenRequest.sender == ctx.persona && target.route.persona == ctx.persona && target.keeper == frozenRequest.keeper)
        val permission = snapshotKeeperInboxPermission(target.permission) ?: error("The private inbox permission is malformed.")
        require(keeperInboxPermissionRefusal(target.route.box, target.keeper, permission, now()) == null) { "The private inbox permission is invalid or expired." }
        val selectedRoute = target.route.copy()
        val destination = VmlsPrivateRequestDestination(selectedRoute.routeId, LinkRelayAddress.canonicalForNode(selectedRoute.box),
            selectedRoute.box, target.keeper, permission.id, permission.tagValue("device")!!)
        val clock = PrivateSenderClock().also { check(it.observe(now())) }
        var credentialValidBefore = Long.MAX_VALUE
        val current = {
            caller.ensureActive()
            val selected = keeperOperationCurrent(operationCurrent) && allowed() && vault.isCurrent(ctx) && publisher.routeCurrent(destination)
            val at = now()
            selected && clock.observe(at) && at < credentialValidBefore && frozenRequest.expiration > at &&
                keeperInboxPermissionRefusal(destination.node, destination.keeper, permission, at) == null
        }
        return lock.withLock {
            val book = read(ctx, current)
            val key = RevocationBook.key(frozenRequest)
            val retained = book.outbox[key]?.takeIf { it.request.expiration > now() }
            val prepared = reserveSend(book, frozenRequest, destination, resend)
            // The same protected change validates the current device and records intent before root crypto or Link IO.
            val device = checkNotNull(write(ctx, book, current, destination.transmittingDevice))
            credentialValidBefore = device.credentialExpiresAt
            check(ctx, current)
            if (retained != null && !resend) return@withLock prepared.sent && prepared.privateEventId != null
            val wrap = VmlsRequestEnvelope.wrap(prepared.request, signer, current)
            check(ctx, current)
            require(wrap.tags == listOf(listOf("p", destination.keeper)))
            val receipt = publisher.publish(ctx, vault, destination, permission, prompt, wrap, current)
            check(ctx, current)
            if (receipt == null || !publisher.accepts(destination, wrap, receipt)) return@withLock false
            book.outbox[key] = prepared.copy(sent = true, privateEventId = receipt.eventId)
            write(ctx, book, current, destination.transmittingDevice)
            check(ctx, current)
            true
        }
    }

    /** Both explicit paths share this bounded protected outbox and the channel lock. */
    private fun reserveSend(book: RevocationBook, request: VmlsRevocationRequest, destination: VmlsPrivateRequestDestination?, resend: Boolean): RevocationEntry {
        book.outbox.entries.removeAll { it.value.request.expiration <= now() }
        val key = RevocationBook.key(request)
        check(key in book.outbox || book.outbox.size < 128) { "The request journal is full." }
        val old = book.outbox[key]
        check(old == null || old.privateDestination == destination || resend) { "This request already attempted a different transport. Choose an explicit retry." }
        check(resend || destination == null || old == null || old.request == request) { "This private request changed. Choose an explicit retry." }
        if (!resend && old != null && destination != null) return old
        if (!resend && old?.sent == true && old.request.expiration > now()) return old
        if (destination == null) {
            check(key in book.publicAttempts || book.publicAttempts.size < 128) { "The public request history is full." }
            book.publicAttempts.add(key)
        }
        val combined = request.copy(sessions = (old?.request?.sessions.orEmpty() + request.sessions).distinct().takeLast(64),
            boxes = (old?.request?.boxes.orEmpty() + request.boxes).distinct().takeLast(64))
        val entry = RevocationEntry(combined, privateDestination = destination,
            earlierPublicAttempt = destination == null || key in book.publicAttempts || old?.earlierPublicAttempt == true || (old != null && old.privateDestination == null))
        book.outbox[key] = entry
        check(book.encode().size <= MlsVault.MAX_REQUEST_BYTES - RESERVE_BYTES) { "The request journal is full. Try again after older requests expire." }
        return entry
    }

    /** Explicit public check. Latest probes do not overwrite retained backfill. */
    suspend fun poll(ctx: VaultContext, signer: ParticipantSigner, mode: VmlsInboxMode = VmlsInboxMode.LATEST,
        authorise: suspend (VmlsRevocationRequest) -> Boolean): RevocationBook = pollPublic(ctx, signer, mode, authorise).book

    suspend fun pollPublic(ctx: VaultContext, signer: ParticipantSigner, mode: VmlsInboxMode = VmlsInboxMode.LATEST,
        authorise: suspend (VmlsRevocationRequest) -> Boolean, current: () -> Boolean = { true }): VmlsInboxResult = lock.withLock {
        check(signer.pubkey == ctx.persona)
        val book = read(ctx, current)
        if (!reserve(ctx, book, current)) return@withLock VmlsInboxResult(book, false, limited = true)
        val relays = dmRelays(ctx, ctx.persona)
        if (relays.isEmpty()) return@withLock VmlsInboxResult(book, false)
        book.scanUntil.keys.retainAll(relays.toSet())
        val relay = relays[book.nextRelay % relays.size]
        book.nextRelay = (book.nextRelay + 1) % relays.size
        val retained = book.scanUntil.getOrPut(relay) { now() }
        val until = if (mode == VmlsInboxMode.LATEST) now() else retained
        write(ctx, book, current)
        val query = filter(ctx, until)
        if (until < query.since!!) return@withLock VmlsInboxResult(book, false)
        check(ctx, current)
        val page = carriers(listOf(relay), guarded(ctx, signer, current)).use { carrier -> carrier.readPage(query) }
        val complete = process(ctx, book, signer, page, query, authorise, current)
        if (complete && mode == VmlsInboxMode.RETAINED) book.scanUntil[relay] = oldest(pageEvents(page!!, ctx.persona, query.since, until))
        write(ctx, book, current)
        VmlsInboxResult(book, complete)
    }

    /** This branch cannot call the public directory/carrier factories. */
    suspend fun pollPrivate(ctx: VaultContext, signer: ParticipantSigner, consent: VmlsPrivateInboxConsent,
        mode: VmlsInboxMode, reader: VmlsPrivateInboxReader,
        authorise: suspend (VmlsRevocationRequest) -> Boolean): VmlsInboxResult = lock.withLock {
        val current = consent::allowed
        check(consent.route.persona == ctx.persona && signer.pubkey == ctx.persona)
        val book = read(ctx, current)
        if (!reserve(ctx, book, current)) return@withLock VmlsInboxResult(book, false, limited = true)
        val key = privateCursorKey(consent.route)
        check(key in book.privateScanUntil || book.privateScanUntil.size < 32) { "The private inbox cursor journal is full." }
        val retained = book.privateScanUntil.getOrPut(key) { now() }
        val until = if (mode == VmlsInboxMode.LATEST) now() else retained
        write(ctx, book, current)
        val query = filter(ctx, until)
        if (until < query.since!!) return@withLock VmlsInboxResult(book, false)
        val page = reader.page(consent.route, signer, query) { allowed() && vault.isCurrent(ctx) && current() }
        val complete = process(ctx, book, signer, page, query, authorise, current)
        if (complete && mode == VmlsInboxMode.RETAINED) book.privateScanUntil[key] = oldest(pageEvents(page!!, ctx.persona, query.since, until))
        write(ctx, book, current)
        VmlsInboxResult(book, complete)
    }

    private suspend fun reserve(ctx: VaultContext, book: RevocationBook, current: () -> Boolean): Boolean {
        check(ctx, current)
        if (book.nextPollAt > now() + 60) book.nextPollAt = 0
        if (book.nextPollAt > now()) return false
        book.nextPollAt = now() + 60
        book.inbox.replaceAll { _, entry -> if (entry.deferredUntil > now() + 3600) entry.copy(deferredUntil = now() + 3600) else entry }
        book.promptAfter.replaceAll { _, at -> at.coerceAtMost(now() + 3600) }
        val since = now() - 9 * 86400
        book.seen.entries.removeAll { it.value < since }
        book.inbox.entries.removeAll { it.value.request.expiration <= now() && it.value.decision != RevocationDecision.APPROVED }
        book.outbox.entries.removeAll { it.value.request.expiration <= now() }
        book.promptAfter.entries.removeAll { it.value <= now() }
        write(ctx, book, current) // The shared allowance survives timeout, cancellation and restart.
        return true
    }
    private fun filter(ctx: VaultContext, until: Long) = Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(ctx.persona)),
        since = (now() - 9 * 86400).coerceAtLeast(0), until = until, limit = 64)
    private fun pageEvents(page: List<NostrEvent>, keeper: String, since: Long, until: Long) = page.filter {
        it.kind == 1059 && it.createdAt in since..until && it.tags.any { tag -> tag.size >= 2 && tag[0] == "p" && tag[1] == keeper } && Events.verify(it)
    }.distinctBy { it.id }.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
    private fun oldest(page: List<NostrEvent>) = page.minOf { it.createdAt }

    /** Signed unsupported shapes move paging without signer work. Only all
     * processed, EOSE-complete pages with an older signed boundary can advance.
     * A saturated timestamp, empty page or expired boundary holds. */
    private suspend fun process(ctx: VaultContext, book: RevocationBook, signer: ParticipantSigner, page: List<NostrEvent>?,
        query: Filter, authorise: suspend (VmlsRevocationRequest) -> Boolean, current: () -> Boolean): Boolean {
        check(ctx, current)
        if (page == null) return false
        check(page.size <= 64) { "The keeper inbox page is too large." }
        val until = query.until!!
        val ordered = pageEvents(page, ctx.persona, query.since!!, until)
        val candidates = ordered.filter { it.tags == listOf(listOf("p", ctx.persona)) && it.content.length <= 40_000 && it.content.toByteArray().size <= 40_000 }
        val guarded = object : ParticipantSigner by signer {
            override suspend fun nip44Decrypt(peer: String, payload: String): String {
                check(ctx, current)
                return signer.nip44Decrypt(peer, payload).also { check(ctx, current) }
            }
        }
        for (wrap in candidates.filter { it.id !in book.seen }.take(8)) {
            check(ctx, current)
            while (book.seen.size >= 1024) book.seen.remove(book.seen.keys.first())
            book.seen[wrap.id] = now()
            write(ctx, book, current)
            val request = try { VmlsRequestEnvelope.unwrap(wrap, guarded, now(), { allowed() && vault.isCurrent(ctx) && current() }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { check(ctx, current); continue }
            check(ctx, current)
            val authorised = try { authorise(request).also { check(ctx, current) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { check(ctx, current); book.seen.remove(wrap.id); continue }
            if (!authorised) continue
            val key = RevocationBook.key(request)
            val old = book.inbox[key]
            if (old?.decision in listOf(RevocationDecision.APPROVED, RevocationDecision.DONE, RevocationDecision.UNAVAILABLE)) continue
            if (old != null && request.createdAt <= old.request.createdAt) continue
            if (old == null && book.inbox.size >= 128) continue
            val deferred = if (old?.decision == RevocationDecision.PENDING) old.deferredUntil
                else maxOf(old?.deferredUntil ?: 0, book.promptAfter[request.sender] ?: 0)
            book.inbox[key] = RevocationEntry(request.copy(expiration = maxOf(request.expiration, old?.request?.expiration ?: 0)), deferredUntil = deferred)
            if (book.encode().size > MlsVault.MAX_REQUEST_BYTES - RESERVE_BYTES) {
                if (old == null) book.inbox.remove(key) else book.inbox[key] = old
                continue
            }
            if (request.sender in book.promptAfter || book.promptAfter.size < 128) book.promptAfter[request.sender] = now() + 3600
        }
        check(ctx, current)
        write(ctx, book, current)
        return candidates.all { it.id in book.seen } && ordered.isNotEmpty() && ordered.last().createdAt < until
    }

    suspend fun decide(ctx: VaultContext, key: String, decision: RevocationDecision): RevocationEntry = lock.withLock {
        val book = read(ctx); val entry = book.inbox[key] ?: error("This request is no longer kept.")
        check(entry.request.expiration > now() || entry.decision == RevocationDecision.APPROVED)
        check(when (entry.decision) {
            RevocationDecision.PENDING -> decision == RevocationDecision.APPROVED || decision == RevocationDecision.DENIED
            RevocationDecision.APPROVED -> decision in listOf(RevocationDecision.APPROVED, RevocationDecision.DONE, RevocationDecision.UNAVAILABLE)
            else -> false
        })
        val next = entry.copy(decision = decision); book.inbox[key] = next; write(ctx, book); next
    }
    suspend fun defer(ctx: VaultContext, key: String) = lock.withLock {
        val book = read(ctx); val sender = book.inbox[key]?.request?.sender ?: return@withLock
        book.inbox.replaceAll { _, entry -> if (entry.request.sender == sender) entry.copy(deferredUntil = now() + 3600) else entry }
        if (sender in book.promptAfter || book.promptAfter.size < 128) book.promptAfter[sender] = now() + 3600
        write(ctx, book)
    }

    private class PrivateSenderClock {
        private var floor = 0L
        @Synchronized fun observe(at: Long): Boolean {
            if (at < floor || at < 0 || at > 9_007_199_254_740_991L) return false
            floor = at
            return true
        }
    }

    private companion object {
        // Headroom for the complete 1024-ID seen set, prompt cooldowns and scan metadata.
        const val RESERVE_BYTES = 110 * 1024
    }

}
