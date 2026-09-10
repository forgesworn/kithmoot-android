package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** One writer per account. The production adapter encrypts these bytes with the device keystore. */
interface ProjectStorage {
    suspend fun acquire()
    suspend fun load(): String?
    suspend fun save(value: String)
    suspend fun release()
}

data class ProjectAccountSnapshot(val projects: List<SharedProject> = emptyList(), val ready: Boolean = false,
    val syncing: Boolean = false, val pendingSends: Int = 0, val error: String? = null)

/** Account directory only: never joins a room, retrieves context or starts an agent. */
class SharedProjects(
    private val signer: ParticipantSigner,
    private val transport: RoomTransport,
    private val storage: ProjectStorage,
    private val scope: CoroutineScope,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private data class Pending(val inner: NostrEvent, val outer: NostrEvent, val recipient: String)
    private data class Receipt(val intent: String, val head: String)
    private val gate = Mutex()
    private val syncGate = Mutex()
    private val sendGate = Mutex()
    private var directory = ProjectDirectoryState(signer.pubkey)
    private var pending = linkedMapOf<String, Pending>()
    private var receipts = linkedMapOf<String, Receipt>()
    private var seen = linkedSetOf<String>()
    private var collector: Job? = null
    private var opened = false
    private var acquired = false
    private var loaded = false
    private var ready = false
    private var syncing = false
    private var fatal: String? = null
    private var networkError: String? = null
    @Volatile private var closed = false
    private val mutable = MutableStateFlow(ProjectAccountSnapshot())
    val state: StateFlow<ProjectAccountSnapshot> = mutable.asStateFlow()
    val identity: String get() = signer.pubkey
    private fun emit() { mutable.value = ProjectAccountSnapshot(directory.projects(), ready && !closed && fatal == null,
        syncing && !closed, pending.size, fatal ?: networkError) }
    private fun live() = check(!closed) { "Projects have closed" }
    private fun requireReady() { live(); check(state.value.ready) { state.value.error ?: "Wait for projects to finish syncing" } }
    private fun body(e: NostrEvent) = Json.parseToJsonElement(e.content).jsonObject
    private fun text(p: JsonObject, key: String) = p.getValue(key).jsonPrimitive.content
    private fun ref(e: NostrEvent): ProjectReference {
        val p = body(e)
        return ProjectReference(if (text(p, "op") == "follow") text(p, "owner") else e.pubkey, text(p, "project"))
    }
    private fun group(e: NostrEvent) = "${if (text(body(e), "op") == "follow") "follow" else "project"}:${ref(e).key}"
    private fun intent(ref: ProjectReference, op: String, value: JsonElement, heads: List<String>): String =
        Digests.sha256(buildJsonObject { put("owner", ref.owner); put("project", ref.project); put("op", op)
            put("value", value); put("heads", JsonArray(heads.sorted().map(::JsonPrimitive))) }.toString().toByteArray()).toHex()

    private suspend fun save(next: ProjectDirectoryState = directory, out: Map<String, Pending> = pending,
        requests: Map<String, Receipt> = receipts, seenIds: Set<String> = seen,
    ) {
        live()
        check(out.size <= 1024 && requests.size <= 4096 && seenIds.size <= 2048) { "Project journal is full" }
        val value = buildJsonObject {
            put("v", 1); put("identity", identity); put("events", JsonArray(next.events().map { it.toJson() }))
            put("pending", JsonArray(out.values.map { p -> buildJsonObject {
                put("inner", p.inner.toJson()); put("outer", p.outer.toJson()); put("recipient", p.recipient)
            } }))
            put("requests", JsonObject(requests.mapValues { (_, r) -> buildJsonObject { put("intent", r.intent); put("head", r.head) } }))
            put("seen", JsonArray(seenIds.map(::JsonPrimitive)))
        }.toString()
        try { check(value.toByteArray().size <= MAX_CACHE_BYTES) { "Project cache is full" }; storage.save(value) } catch (e: Exception) {
            if (e is CancellationException) throw e
            fatal = "Projects could not be saved. Your existing data has been kept."; emit(); throw e
        }
        live()
    }

    suspend fun open() {
        check(!opened && !closed) { "Projects already opened or closed" }; opened = true
        try {
            gate.withLock {
                storage.acquire(); acquired = true; live()
                storage.load()?.let { raw ->
                    check(raw.toByteArray().size <= MAX_CACHE_BYTES) { "Project cache is too large" }
                    val p = Json.parseToJsonElement(raw).jsonObject
                    check(p.keys == setOf("v", "identity", "events", "pending", "requests", "seen") &&
                        p["v"] == JsonPrimitive(1) && p["identity"] == JsonPrimitive(identity)) { "Wrong project account or cache format" }
                    val events = p.getValue("events").jsonArray
                    directory = ProjectDirectoryState.restore(identity, events.map { requireNotNull(Projects.decodeEvent(it)) }, now())
                    val out = p.getValue("pending").jsonArray
                    val requests = p.getValue("requests").jsonObject
                    val ids = p.getValue("seen").jsonArray
                    check(out.size <= 1024 && requests.size <= 4096 && ids.size <= 2048) { "Project cache exceeds its limits" }
                    for (value in out) {
                        val entry = value.jsonObject
                        val inner = requireNotNull(Projects.decodeEvent(entry.getValue("inner")))
                        val outer = requireNotNull(Projects.decodeEvent(entry.getValue("outer")))
                        val recipient = text(entry, "recipient")
                        check(inner.pubkey == identity && Projects.forRecipient(inner, recipient, now()) != null && Projects.isWrap(outer, recipient)) { "Invalid project outbox" }
                        check(pending.put(outer.id, Pending(inner, outer, recipient)) == null) { "Duplicate project outbox entry" }
                    }
                    for ((request, value) in requests) {
                        val r = value.jsonObject
                        check(REQUEST.matches(request) && HEX.matches(text(r, "intent")) && HEX.matches(text(r, "head"))) { "Invalid project receipt" }
                        receipts[request] = Receipt(text(r, "intent"), text(r, "head"))
                    }
                    for (id in ids) { check(id is JsonPrimitive && id.isString && HEX.matches(id.content)); seen.add(id.content) }
                }
                loaded = true; emit()
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { gate.withLock { fatal = "Saved projects could not be verified. Your data has been kept."; emit() } }
            throw e
        }
        refresh()
    }

    /** Failed relay replay is retryable; an unauthenticated or unwritable cache is not discarded. */
    suspend fun refresh() = syncGate.withLock {
        gate.withLock { live(); check(loaded && fatal == null) { "Saved projects are unavailable" }; ready = false; syncing = true; networkError = null; emit() }
        collector?.cancelAndJoin()
        val filters = listOf(Filter(kinds = listOf(Projects.WRAP_KIND), tags = mapOf("#p" to listOf(identity), "#l" to listOf(Projects.APP))))
        try {
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { transport.subscribe(filters).collect { receive(it) } }
                catch (e: Exception) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    gate.withLock { networkError = "Project sync stopped. Try syncing again."; ready = false; emit() }
                }
            }
            for (event in transport.queryStored(filters)) receive(event)
            gate.withLock { live(); ready = fatal == null && collector?.isActive == true; syncing = false
                if (ready) networkError = null; emit() }
        } catch (e: Exception) {
            gate.withLock { ready = false; syncing = false; networkError = "Projects could not finish syncing. Try again."; emit() }
            if (e is CancellationException) throw e
        }
    }

    private suspend fun receive(outer: NostrEvent) = gate.withLock {
        live(); if (fatal != null || outer.id in seen || !Projects.isWrap(outer, identity)) return@withLock
        val inner = try { Projects.unwrap(outer, identity, now(), signer::nip44Decrypt) }
        catch (e: CancellationException) { throw e }
        catch (e: SignerException) { throw e }
        catch (_: Exception) { null }
        live()
        val next = inner?.let { directory.accept(it, now()) } ?: directory
        val ids = LinkedHashSet(seen).apply { add(outer.id); while (size > 2048) remove(first()) }
        save(next, seenIds = ids); directory = next; seen = ids; emit()
    }

    private suspend fun sign(value: JsonObject): NostrEvent = Projects.sign(identity, value, now(), signer::sign).also { live() }
    private fun existing(request: String, hash: String): String? {
        check(REQUEST.matches(request)) { "Invalid project request" }
        return receipts[request]?.let { check(it.intent == hash) { "This request belongs to a different project change" }; it.head }
    }
    private fun bases(ref: ProjectReference, follow: Boolean = false): List<NostrEvent> = directory.events()
        .filter { group(it) == "${if (follow) "follow" else "project"}:${ref.key}" }

    suspend fun follow(ref: ProjectReference, joined: Boolean, heads: List<String>, request: String): String {
        val result = gate.withLock {
            requireReady(); val hash = intent(ref, "follow", JsonPrimitive(joined), heads)
            existing(request, hash)?.let { return@withLock it }
            val current = directory.projects().find { it.key == ref.key }
            check(current != null && !current.withdrawn && !current.conflicted && current.heads == heads.sorted()) { "This project changed. Review its invitation again." }
            check(!joined || !current.archived) { "This project is archived" }
            val member = current.definition!!.getValue("members").jsonArray.map { it.jsonObject }.first { text(it, "pubkey") == identity }
            val previous = bases(ref, true)
            val primary = sign(buildJsonObject {
                put("v", 1); put("op", "follow"); put("owner", ref.owner); put("project", ref.project); put("joined", joined)
                put("membership", member.getValue("epoch")); put("invitation", current.heads.single())
                put("revision", (previous.firstOrNull()?.let { Projects.record(it, now())!!.revision } ?: 0) + 1)
                put("parents", JsonArray(previous.map { it.id }.sorted().map(::JsonPrimitive))); put("request", request)
            })
            commit(primary, listOf(Pending(primary, Projects.wrap(primary, identity, now()), identity)), request, hash)
        }
        sendInBackground(); return result
    }

    suspend fun create(definition: JsonObject, request: String): String = update(ProjectReference(identity, Projects.id(identity, request)), emptyList(), definition, request, true)

    suspend fun update(ref: ProjectReference, heads: List<String>, definition: JsonObject, request: String, create: Boolean = false): String {
        val result = gate.withLock {
            requireReady(); check(ref.owner == identity) { "Only the project owner can change its people and rooms" }
            val hash = intent(ref, "snapshot", definition, heads); existing(request, hash)?.let { return@withLock it }
            val current = directory.projects().find { it.key == ref.key }
            check(if (create) current == null && heads.isEmpty() else current != null && current.heads == heads.sorted()) { "The project changed. Review its people and rooms again." }
            val revision = (current?.revision ?: 0) + 1
            val previous = bases(ref).mapNotNull { Projects.record(it, now())?.definition }
            val members = definition.getValue("members").jsonArray.map { raw ->
                val member = raw.jsonObject
                val prior = previous.map { d -> d.getValue("members").jsonArray.map { it.jsonObject }
                    .find { text(it, "pubkey") == text(member, "pubkey") && text(it, "kind") == text(member, "kind") } }
                val epoch = if (prior.isNotEmpty() && prior.all { it != null && it["epoch"] == prior.first()?.get("epoch") }) prior.first()!!.getValue("epoch") else JsonPrimitive(revision)
                JsonObject(member + ("epoch" to epoch))
            }
            val authorities = previous.map { Projects.authority(ref, it) }
            val stable = authorities.firstOrNull()?.takeIf { candidate -> authorities.all { it == candidate } }
            var canonical = JsonObject(definition + mapOf("members" to JsonArray(members), "authorityRevision" to (if (stable != null) previous.first().getValue("authorityRevision") else JsonPrimitive(revision))))
            if (stable == null || Projects.authority(ref, canonical) != stable) canonical = JsonObject(canonical + ("authorityRevision" to JsonPrimitive(revision)))
            Projects.authority(ref, canonical)
            fun base(op: String) = buildJsonObject { put("v", 1); put("op", op); put("project", ref.project); put("revision", revision)
                put("request", request); put("parents", JsonArray(heads.sorted().map(::JsonPrimitive))) }
            val primary = sign(JsonObject(base("snapshot") + ("definition" to canonical)))
            val recipients = members.map { text(it, "pubkey") }.toSet()
            val outgoing = recipients.map { Pending(primary, Projects.wrap(primary, it, now()), it) }.toMutableList()
            for (recipient in previous.flatMap { it.getValue("members").jsonArray.map { m -> text(m.jsonObject, "pubkey") } }.toSet() - recipients) {
                val withdrawal = sign(JsonObject(base("withdraw") + ("recipient" to JsonPrimitive(recipient))))
                outgoing.add(Pending(withdrawal, Projects.wrap(withdrawal, recipient, now()), recipient))
            }
            commit(primary, outgoing, request, hash)
        }
        sendInBackground(); return result
    }

    private fun sendInBackground() { scope.launch {
        try { retry() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* save already records the failure */ }
    } }

    private suspend fun commit(primary: NostrEvent, outgoing: List<Pending>, request: String, hash: String): String {
        live(); val next = directory.accept(primary, now())
        check(next.events().any { it.id == primary.id }) { "Project directory is full" }
        val recipients = outgoing.map { it.recipient }.toSet()
        val out = LinkedHashMap(pending.filterValues { group(it.inner) != group(primary) || it.recipient !in recipients })
        outgoing.forEach { out[it.outer.id] = it }
        val requests = LinkedHashMap(receipts).apply { put(request, Receipt(hash, primary.id)) }
        save(next, out, requests); directory = next; pending = out; receipts = requests; emit(); return primary.id
    }

    /** Publishes only durable exact envelopes. Opening a cache never automatically replays them. */
    suspend fun retry() {
        if (!sendGate.tryLock()) return
        var failed = false
        try {
            val batch = gate.withLock { if (closed || !state.value.ready) emptyList() else pending.values.toList() }
            for (chunk in batch.chunked(4)) {
                if (closed) break
                val accepted = coroutineScope { chunk.map { p -> async {
                    if (gate.withLock { closed || fatal != null || p.outer.id !in pending }) return@async null
                    try { if (withTimeout(15_000) { transport.publishConfirmed(p.outer) }) p.outer.id else null }
                    catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; null }
                    catch (_: Exception) { null }
                } }.awaitAll() }
                if (accepted.any { it == null }) failed = true
                gate.withLock {
                    if (!closed) {
                        val out = LinkedHashMap(pending).apply { accepted.filterNotNull().forEach(::remove) }
                        save(out = out); pending = out; networkError = if (failed) "Some project changes are waiting to send." else null; emit()
                    }
                }
            }
        } catch (e: Exception) { failed = true; throw e }
        finally {
            withContext(NonCancellable) { gate.withLock {
                sendGate.unlock()
                if (!failed && !closed && state.value.ready && pending.isNotEmpty()) sendInBackground()
            } }
        }
    }

    suspend fun close() {
        closed = true; collector?.cancel()
        withContext(NonCancellable) { gate.withLock {
            ready = false; syncing = false; directory = ProjectDirectoryState(identity); pending.clear(); receipts.clear(); seen.clear(); emit()
            if (acquired) { storage.release(); acquired = false }
        } }
    }

    companion object {
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        private val HEX = Regex("[0-9a-f]{64}")
        private val REQUEST = Regex("[a-zA-Z0-9_-]{16,80}")
    }
}
