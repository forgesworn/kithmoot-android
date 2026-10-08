package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
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
data class RevocationEntry(val request: VmlsRevocationRequest, val decision: RevocationDecision = RevocationDecision.PENDING, val sent: Boolean = false, val deferredUntil: Long = 0)
class RevocationBook(
    val inbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val outbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val seen: MutableMap<String, Long> = linkedMapOf(),
    val scanUntil: MutableMap<String, Long> = linkedMapOf(),
    var nextRelay: Int = 0,
    var nextPollAt: Long = 0,
    val promptAfter: MutableMap<String, Long> = linkedMapOf(),
) {
    fun encode(): ByteArray = buildJsonObject {
        put("version", 1)
        fun entries(values: Map<String, RevocationEntry>) = buildJsonObject {
            values.forEach { (key, e) -> put(key, buildJsonObject {
                put("rumor", e.request.rumor()); put("decision", e.decision.name); put("sent", e.sent); put("deferredUntil", e.deferredUntil)
            }) }
        }
        put("inbox", entries(inbox)); put("outbox", entries(outbox))
        put("scanUntil", buildJsonObject { scanUntil.forEach { (relay, until) -> put(relay, until) } })
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
            require(o.getValue("version").jsonPrimitive.int == 1)
            fun entries(name: String): MutableMap<String, RevocationEntry> = o.getValue(name).jsonObject.also { require(it.size <= 128) }.mapValuesTo(linkedMapOf()) { (key, value) ->
                val e = value.jsonObject; val r = e.getValue("rumor").jsonObject
                val sender = r.getValue("pubkey").jsonPrimitive.content
                val keeper = r.getValue("tags").jsonArray.single { it.jsonArray[0].jsonPrimitive.content == "p" }.jsonArray[1].jsonPrimitive.content
                val request = VmlsRevocationRequest.parse(r.toString(), sender, keeper, r.getValue("created_at").jsonPrimitive.long)
                require(key == key(request))
                RevocationEntry(request, RevocationDecision.valueOf(e.getValue("decision").jsonPrimitive.content), e.getValue("sent").jsonPrimitive.boolean, e["deferredUntil"]?.jsonPrimitive?.long ?: 0)
            }
            val seen = o.getValue("seen").jsonObject.also { require(it.size <= 1024) }.mapValuesTo(linkedMapOf()) { (id, at) ->
                require(Regex("[0-9a-f]{64}").matches(id)); at.jsonPrimitive.long.also { require(it >= 0) }
            }
            val prompts = o["promptAfter"]?.jsonObject.orEmpty().also { require(it.size <= 128) }.mapValuesTo(linkedMapOf()) { (sender, at) ->
                require(Regex("[0-9a-f]{64}").matches(sender)); at.jsonPrimitive.long
            }
            val cursors = (o["scanUntil"] as? JsonObject).orEmpty().also { require(it.size <= MAX_DM_RELAYS) }
                .mapValuesTo(linkedMapOf()) { (_, until) -> until.jsonPrimitive.long }
            return RevocationBook(entries("inbox"), entries("outbox"), seen, cursors,
                (o["nextRelay"]?.jsonPrimitive?.int ?: 0).also { require(it in 0 until MAX_DM_RELAYS) },
                o["nextPollAt"]?.jsonPrimitive?.long ?: 0, prompts)
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
    private fun check(ctx: VaultContext) { check(allowed() && vault.isCurrent(ctx)) { "This account session ended or relay traffic is paused." } }
    private suspend fun read(ctx: VaultContext): RevocationBook {
        check(ctx)
        val read = vault.revocationRequests(ctx) as? VaultResult.Ok ?: error("The restore witness has not confirmed the request journal.")
        return RevocationBook.decode(read.value)
    }
    private suspend fun write(ctx: VaultContext, book: RevocationBook) {
        check(ctx)
        check(vault.keepRevocationRequests(ctx, book.encode()) is VaultResult.Ok) { "The request journal could not be witnessed. Try again." }
    }
    suspend fun entries(ctx: VaultContext): RevocationBook = lock.withLock { read(ctx) }
    private fun guarded(ctx: VaultContext, signer: ParticipantSigner): ParticipantSigner = object : ParticipantSigner by signer {
        override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
            check(ctx)
            return signer.sign(kind, createdAt, tags, content).also { check(ctx) }
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
        book.outbox.entries.removeAll { it.value.request.expiration <= now() }
        check(key in book.outbox || book.outbox.size < 128) { "The request journal is full." }
        val old = book.outbox[key]
        if (!resend && old?.sent == true && old.request.expiration > now()) return@withLock true
        val combined = request.copy(sessions = (old?.request?.sessions.orEmpty() + request.sessions).distinct().takeLast(64),
            boxes = (old?.request?.boxes.orEmpty() + request.boxes).distinct().takeLast(64))
        book.outbox[key] = RevocationEntry(combined)
        check(book.encode().size <= MlsVault.MAX_REQUEST_BYTES - RESERVE_BYTES) { "The request journal is full. Try again after older requests expire." }
        write(ctx, book)
        val relays = dmRelays(ctx, request.keeper)
        check(relays.isNotEmpty()) { "The keeper has no DM relay list. No revocation request was sent." }
        val wrap = VmlsRequestEnvelope.wrap(combined, signer, { allowed() && vault.isCurrent(ctx) })
        check(ctx)
        val sent = carriers(relays, null).use { it.publish(wrap) } // Never disclose the sender through identity AUTH.
        check(ctx)
        if (sent) { book.outbox[key] = RevocationEntry(combined, sent = true); write(ctx, book) }
        sent
    }

    /** Foreground caller only: at most eight new wraps and two decryptions per wrap per minute. */
    suspend fun poll(ctx: VaultContext, signer: ParticipantSigner, authorise: suspend (VmlsRevocationRequest) -> Boolean): RevocationBook = lock.withLock {
        check(signer.pubkey == ctx.persona)
        val book = read(ctx)
        // A corrected fast clock must not suppress requests indefinitely.
        if (book.nextPollAt > now() + 60) book.nextPollAt = 0
        book.inbox.replaceAll { _, entry -> if (entry.deferredUntil > now() + 3600) entry.copy(deferredUntil = now() + 3600) else entry }
        book.promptAfter.replaceAll { _, at -> at.coerceAtMost(now() + 3600) }
        if (book.nextPollAt > now()) return@withLock book
        book.nextPollAt = now() + 60
        val relays = dmRelays(ctx, ctx.persona)
        if (relays.isEmpty()) { write(ctx, book); return@withLock book }
        val since = now() - 9 * 86400
        book.seen.entries.removeAll { it.value < since }
        book.inbox.entries.removeAll { it.value.request.expiration <= now() && it.value.decision != RevocationDecision.APPROVED }
        book.outbox.entries.removeAll { it.value.request.expiration <= now() }
        book.promptAfter.entries.removeAll { it.value <= now() }
        // One relay/page per minute: independent cursors cannot skip a slower relay's events.
        book.scanUntil.keys.retainAll(relays.toSet())
        val relay = relays[book.nextRelay % relays.size]
        book.nextRelay = (book.nextRelay + 1) % relays.size
        val until = book.scanUntil[relay]?.coerceAtMost(now())?.takeIf { it >= since } ?: now()
        write(ctx, book) // Also persist rate/fairness before a timeout or cancellation.
        carriers(listOf(relay), guarded(ctx, signer)).use { carrier ->
                val page = carrier.readPage(Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(ctx.persona)), since = since, until = until, limit = 64))
                    ?: return@use
                check(ctx)
                val events = page.filter { event -> event.createdAt in since..until && event.kind == 1059 &&
                    event.tags == listOf(listOf("p", ctx.persona)) && event.content.length <= 40_000 && Events.verify(event) }.distinctBy { it.id }
                val ordered = events.sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id })
                val unseen = ordered.filter { it.id !in book.seen }
                for (wrap in unseen.take(8)) {
                    check(ctx)
                    // Bounded FIFO: keep making progress rather than stopping all requests for nine days.
                    while (book.seen.size >= 1024) book.seen.remove(book.seen.keys.first())
                    book.seen[wrap.id] = now()
                    write(ctx, book) // A verified attempt is durable before external-signer work.
                    val request = try { VmlsRequestEnvelope.unwrap(wrap, signer, now(), { allowed() && vault.isCurrent(ctx) }) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { continue }
                    val authorised = try { authorise(request) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { book.seen.remove(wrap.id); continue } // A temporarily unreadable roster defers, not loses, the request.
                    if (!authorised) continue
                    val key = RevocationBook.key(request)
                    val old = book.inbox[key]
                    if (old?.decision in listOf(RevocationDecision.APPROVED, RevocationDecision.DONE, RevocationDecision.UNAVAILABLE)) continue
                    if (old != null && request.createdAt <= old.request.createdAt) continue
                    if (old == null && book.inbox.size >= 128) continue
                    val deferred = if (old?.decision == RevocationDecision.PENDING) old.deferredUntil
                        else maxOf(old?.deferredUntil ?: 0, book.promptAfter[request.sender] ?: 0)
                    val latest = request.copy(expiration = maxOf(request.expiration, old?.request?.expiration ?: 0))
                    book.inbox[key] = RevocationEntry(latest, deferredUntil = deferred)
                    if (book.encode().size > MlsVault.MAX_REQUEST_BYTES - RESERVE_BYTES) {
                        if (old == null) book.inbox.remove(key) else book.inbox[key] = old
                        continue
                    }
                    if (request.sender in book.promptAfter || book.promptAfter.size < 128)
                        book.promptAfter[request.sender] = now() + 3600
                }
                if (unseen.any { it.id !in book.seen }) book.scanUntil[relay] = until
                else if (page.size < 64 || ordered.isEmpty()) book.scanUntil.remove(relay)
                else {
                    val oldest = ordered.last().createdAt
                    // Re-read the boundary second so a page split within it does not lose the remainder.
                    // If the relay returns a full page all at that second and all seen, NIP-01 has no further cursor.
                    book.scanUntil[relay] = if (ordered.first().createdAt == oldest && unseen.isEmpty()) oldest - 1 else oldest
                }
        }
        write(ctx, book)
        book
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

    private companion object {
        // Headroom for the complete 1024-ID seen set, prompt cooldowns and scan metadata.
        const val RESERVE_BYTES = 110 * 1024
    }

}
