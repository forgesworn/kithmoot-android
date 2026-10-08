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
enum class RevocationDecision { PENDING, APPROVED, DONE, DENIED }
data class RevocationEntry(val request: VmlsRevocationRequest, val decision: RevocationDecision = RevocationDecision.PENDING, val sent: Boolean = false)
class RevocationBook(
    val inbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val outbox: MutableMap<String, RevocationEntry> = linkedMapOf(),
    val seen: MutableMap<String, Long> = linkedMapOf(),
) {
    fun encode(): ByteArray = buildJsonObject {
        put("version", 1)
        fun entries(values: Map<String, RevocationEntry>) = buildJsonObject {
            values.forEach { (key, e) -> put(key, buildJsonObject {
                put("rumor", e.request.rumor()); put("decision", e.decision.name); put("sent", e.sent)
            }) }
        }
        put("inbox", entries(inbox)); put("outbox", entries(outbox))
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
                RevocationEntry(request, RevocationDecision.valueOf(e.getValue("decision").jsonPrimitive.content), e.getValue("sent").jsonPrimitive.boolean)
            }
            val seen = o.getValue("seen").jsonObject.also { require(it.size <= 1024) }.mapValuesTo(linkedMapOf()) { (id, at) ->
                require(Regex("[0-9a-f]{64}").matches(id)); at.jsonPrimitive.long.also { require(it >= 0) }
            }
            return RevocationBook(entries("inbox"), entries("outbox"), seen)
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
    private val nextPoll = mutableMapOf<String, Long>()
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
        return latestDmRelayList(events, identity)
    }

    /** False is never recorded as sent; no relay list means no fallback publication. */
    suspend fun send(ctx: VaultContext, signer: ParticipantSigner, request: VmlsRevocationRequest, resend: Boolean = false): Boolean = lock.withLock {
        check(signer.pubkey == ctx.persona && request.sender == ctx.persona)
        val book = read(ctx); val key = RevocationBook.key(request)
        check(key in book.outbox || book.outbox.size < 128) { "The request journal is full." }
        val old = book.outbox[key]
        if (!resend && old?.sent == true && old.request.expiration > now()) return@withLock true
        book.outbox[key] = RevocationEntry(request)
        write(ctx, book)
        val relays = dmRelays(ctx, request.keeper)
        check(relays.isNotEmpty()) { "The keeper has no DM relay list. No revocation request was sent." }
        val wrap = VmlsRequestEnvelope.wrap(request, signer, { allowed() && vault.isCurrent(ctx) })
        check(ctx)
        val sent = carriers(relays, guarded(ctx, signer)).use { it.publish(wrap) }
        check(ctx)
        if (sent) { book.outbox[key] = RevocationEntry(request, sent = true); write(ctx, book) }
        sent
    }

    /** Foreground caller only: at most eight new wraps and two decryptions per wrap per minute. */
    suspend fun poll(ctx: VaultContext, signer: ParticipantSigner, authorise: suspend (VmlsRevocationRequest) -> Boolean): RevocationBook = lock.withLock {
        check(signer.pubkey == ctx.persona)
        val book = read(ctx)
        if ((nextPoll[ctx.persona] ?: 0) > now()) return@withLock book
        nextPoll[ctx.persona] = now() + 60
        val relays = dmRelays(ctx, ctx.persona)
        if (relays.isEmpty()) return@withLock book
        // Nine days covers a seven-day rumor plus the two-day randomized wrapper stamp.
        book.seen.entries.removeAll { it.value < now() - 9 * 86400 }
        book.inbox.entries.removeAll { it.value.request.expiration <= now() && it.value.decision != RevocationDecision.APPROVED }
        val wraps = mutableListOf<NostrEvent>()
        carriers(relays, guarded(ctx, signer)).use { carrier ->
            withTimeoutOrNull(5_000) {
                carrier.subscribe(listOf(Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(ctx.persona)), since = now() - 9 * 86400, limit = 64)))
                    .take(64).collect { event ->
                        if (wraps.size < 8 && event.id !in book.seen && wraps.none { it.id == event.id }) wraps += event
                    }
            }
        }
        for (wrap in wraps) {
            check(ctx)
            if (book.seen.size >= 1024) break // Do not evict an unexpired dedup record under a flood.
            if (!Regex("[0-9a-f]{64}").matches(wrap.id)) continue
            book.seen[wrap.id] = now()
            // Record the attempt before invoking an external signer; restart never repeats a prompt storm.
            write(ctx, book)
            val request = try { VmlsRequestEnvelope.unwrap(wrap, signer, now(), { allowed() && vault.isCurrent(ctx) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
            if (!authorise(request)) continue
            val key = RevocationBook.key(request)
            if (key !in book.inbox && book.inbox.size < 128) book.inbox[key] = RevocationEntry(request)
        }
        write(ctx, book)
        book
    }

    suspend fun decide(ctx: VaultContext, key: String, decision: RevocationDecision): RevocationEntry = lock.withLock {
        val book = read(ctx); val entry = book.inbox[key] ?: error("This request is no longer kept.")
        check(entry.request.expiration > now() || entry.decision == RevocationDecision.APPROVED)
        check(when (entry.decision) {
            RevocationDecision.PENDING -> decision == RevocationDecision.APPROVED || decision == RevocationDecision.DENIED
            RevocationDecision.APPROVED -> decision == RevocationDecision.APPROVED || decision == RevocationDecision.DONE
            else -> false
        })
        val next = entry.copy(decision = decision); book.inbox[key] = next; write(ctx, book); next
    }
}
