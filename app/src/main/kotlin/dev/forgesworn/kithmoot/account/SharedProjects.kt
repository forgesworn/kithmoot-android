package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import dev.forgesworn.kithmoot.relay.RelayHistoryException
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
    /** Waits out [AccountWriteHold] before each relay publish. */
    private val beforePublish: suspend () -> Unit = {},
) {
    private data class Pending(val inner: NostrEvent, val outer: NostrEvent, val recipient: String)
    private data class Receipt(val intent: String, val head: String)
    private data class LogoRefresh(val event: NostrEvent, val outgoing: List<Pending>)
    private val gate = Mutex()
    private val syncGate = Mutex()
    private val sendGate = Mutex()
    private var directory = ProjectDirectoryState(signer.pubkey)
    private var pending = linkedMapOf<String, Pending>()
    private var receipts = linkedMapOf<String, Receipt>()
    private var seen = linkedSetOf<String>()
    private var logos = ProjectLogoDirectoryState()
    private var logoPending = linkedMapOf<String, Pending>()
    private var logoReceipts = linkedMapOf<String, Receipt>()
    private var logoWaiting = linkedMapOf<String, NostrEvent>()
    // Relay guards read only this committed immutable snapshot, never the account mutex.
    @Volatile private var eligibleLogoOuters: Set<String> = emptySet()
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
    private fun contexts(next: ProjectDirectoryState = directory): List<ProjectLogoContext> = next.projects().mapNotNull { project ->
        project.definition?.takeIf { !project.withdrawn && !project.conflicted &&
            it.getValue("members").jsonArray.any { member -> text(member.jsonObject, "pubkey") == identity } }
            ?.let { ProjectLogoContext(project.reference, it) }
    }
    private fun context(ref: ProjectReference, next: ProjectDirectoryState = directory) = contexts(next).find { it.reference == ref }
    private fun logoRef(event: NostrEvent) = ProjectReference(event.pubkey, text(body(event), "project"))
    private fun pruneLogos(next: ProjectDirectoryState = directory, images: ProjectLogoDirectoryState = logos,
        out: Map<String, Pending> = logoPending): LinkedHashMap<String, Pending> {
        val contexts = contexts(next).associateBy { it.reference.key }
        return LinkedHashMap(out.filterValues { p ->
            val context = contexts[logoRef(p.inner).key]
            context != null && p.inner.pubkey == identity && images.current(context).heads.singleOrNull() == p.inner.id &&
                context.definition.getValue("members").jsonArray.any { text(it.jsonObject, "pubkey") == p.recipient }
        })
    }
    private fun emit() {
        eligibleLogoOuters = if (closed || fatal != null) emptySet() else pruneLogos().keys.toSet()
        val contexts = contexts().associateBy { it.reference.key }
        val projects = directory.projects().map { project ->
            val projection = contexts[project.key]?.let(logos::current)
            project.copy(logo = projection?.image?.takeUnless { project.archived }, logoHeads = projection?.heads.orEmpty(),
                logoVersion = projection?.version ?: 0, logoConflicted = projection?.conflicted ?: false)
        }
        mutable.value = ProjectAccountSnapshot(projects, ready && !closed && fatal == null,
            syncing && !closed, pending.size + logoPending.size, fatal ?: networkError)
    }
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
        images: ProjectLogoDirectoryState = logos, logoOut: Map<String, Pending> = logoPending,
        logoRequests: Map<String, Receipt> = logoReceipts, waiting: Map<String, NostrEvent> = logoWaiting,
    ) {
        live()
        check(out.size + logoOut.size <= 1024 && requests.size + logoRequests.size <= 4096 && seenIds.size <= 2048 &&
            images.events().size <= 1024 && waiting.size <= 128) { "Project journal is full" }
        val value = buildJsonObject {
            put("v", 1); put("identity", identity); put("events", JsonArray(next.events().map { it.toJson() }))
            put("pending", JsonArray(out.values.map { p -> buildJsonObject {
                put("inner", p.inner.toJson()); put("outer", p.outer.toJson()); put("recipient", p.recipient)
            } }))
            put("requests", JsonObject(requests.mapValues { (_, r) -> buildJsonObject { put("intent", r.intent); put("head", r.head) } }))
            put("seen", JsonArray(seenIds.map(::JsonPrimitive)))
            put("logoEvents", JsonArray(images.events().map { it.toJson() }))
            put("logoPending", JsonArray(logoOut.values.map(::pendingJson)))
            put("logoRequests", JsonObject(logoRequests.mapValues { (_, r) -> receiptJson(r) }))
            put("logoWaiting", JsonArray(waiting.values.map { it.toJson() }))
        }.toString()
        try { check(value.toByteArray().size <= MAX_CACHE_BYTES) { "Project cache is full" }; storage.save(value) } catch (e: Exception) {
            if (e is CancellationException) throw e
            fatal = "Projects could not be saved. Your existing data has been kept."; emit(); throw e
        }
        live()
    }

    private fun pendingJson(p: Pending) = buildJsonObject {
        put("inner", p.inner.toJson()); put("outer", p.outer.toJson()); put("recipient", p.recipient)
    }
    private fun receiptJson(r: Receipt) = buildJsonObject { put("intent", r.intent); put("head", r.head) }
    private fun readReceipt(request: String, value: JsonElement): Receipt {
        val r = value.jsonObject
        check(r.keys == setOf("intent", "head") && REQUEST.matches(request) &&
            HEX.matches(text(r, "intent")) && HEX.matches(text(r, "head"))) { "Invalid project receipt" }
        return Receipt(text(r, "intent"), text(r, "head"))
    }

    suspend fun open() {
        check(!opened && !closed) { "Projects already opened or closed" }; opened = true
        try {
            gate.withLock {
                storage.acquire(); acquired = true; live()
                storage.load()?.let { raw ->
                    check(raw.toByteArray().size <= MAX_CACHE_BYTES) { "Project cache is too large" }
                    val p = Json.parseToJsonElement(raw).jsonObject
                    val required = setOf("v", "identity", "events", "pending", "requests", "seen")
                    val optional = setOf("logoEvents", "logoPending", "logoRequests", "logoWaiting")
                    check(p.keys.containsAll(required) && p.keys.all { it in required || it in optional } &&
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
                        receipts[request] = readReceipt(request, value)
                    }
                    for (id in ids) { check(id is JsonPrimitive && id.isString && HEX.matches(id.content)); seen.add(id.content) }
                    val images = p["logoEvents"]?.jsonArray.orEmpty()
                    val logoOut = p["logoPending"]?.jsonArray.orEmpty()
                    val logoRequests = p["logoRequests"]?.jsonObject.orEmpty()
                    val waiting = p["logoWaiting"]?.jsonArray.orEmpty()
                    check(images.size <= 1024 && out.size + logoOut.size <= 1024 &&
                        requests.size + logoRequests.size <= 4096 && waiting.size <= 128) { "Project logo cache exceeds its limits" }
                    logos = ProjectLogoDirectoryState.restore(images.map { requireNotNull(Projects.decodeEvent(it)) }, contexts(), now())
                    for (value in logoOut) {
                        val entry = value.jsonObject
                        check(entry.keys == setOf("inner", "outer", "recipient")) { "Invalid project logo outbox" }
                        val inner = requireNotNull(Projects.decodeEvent(entry.getValue("inner")))
                        val outer = requireNotNull(Projects.decodeEvent(entry.getValue("outer")))
                        val recipient = text(entry, "recipient")
                        check(inner.pubkey == identity && HEX.matches(recipient) && inner.content.toByteArray().size <= ProjectLogos.MAX_BYTES &&
                            Events.verify(inner) && ProjectLogos.isWrap(outer, recipient, now())) { "Invalid project logo outbox" }
                        check(logoPending.put(outer.id, Pending(inner, outer, recipient)) == null) { "Duplicate project logo outbox entry" }
                    }
                    // Valid but obsolete envelopes never regain dispatch permission on restart.
                    logoPending = pruneLogos()
                    for ((request, value) in logoRequests) {
                        check(request !in receipts) { "A project request belongs to two changes" }
                        logoReceipts[request] = readReceipt(request, value)
                    }
                    for (value in waiting) {
                        val event = requireNotNull(Projects.decodeEvent(value))
                        check(ProjectLogos.isWrap(event, identity, now()) && logoWaiting.put(event.id, event) == null) { "Invalid waiting project logo envelope" }
                    }
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
        val filters = listOf(Filter(kinds = listOf(Projects.WRAP_KIND), tags = mapOf("#p" to listOf(identity), "#l" to listOf(Projects.APP, ProjectLogos.APP))))
        try {
            collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try { transport.subscribe(filters).collect { receive(it) } }
                catch (e: Exception) {
                    if (e is CancellationException && !currentCoroutineContext().isActive) throw e
                    gate.withLock { networkError = "Project sync stopped. Try syncing again."; ready = false; emit() }
                }
            }
            for (event in transport.queryStored(filters)) receive(event)
            gate.withLock { live(); drainLogoWaiting(); ready = fatal == null && collector?.isActive == true; syncing = false
                if (ready) networkError = null; emit() }
        } catch (e: Exception) {
            gate.withLock {
                ready = false; syncing = false
                networkError = when (e) {
                    is RelayHistoryException -> if (e.authenticationRequired)
                        "${e.relay} requires authentication to read projects. Account sync cannot authenticate here yet. Change this relay in Relay settings, then tap Retry project sync."
                    else "${e.relay} refused project history. Check Relay settings, then tap Retry project sync."
                    is SignerException -> "Your signer did not authorise opening projects. Check the signer's connection and decryption permissions, then tap Retry project sync."
                    else -> "Projects could not finish syncing. Check your connection and Relay settings, then tap Retry project sync."
                }
                emit()
            }
            if (e is CancellationException) throw e
        }
    }

    private suspend fun receive(outer: NostrEvent) = gate.withLock {
        live(); if (fatal != null || outer.id in seen) return@withLock
        if (ProjectLogos.isWrap(outer, identity, now())) {
            val waiting = LinkedHashMap(logoWaiting).apply {
                put(outer.id, outer.copy(tags = outer.tags.map(List<String>::toList)))
                while (size > 128) remove(keys.first())
            }
            val ids = seenWith(outer.id)
            save(seenIds = ids, waiting = waiting); logoWaiting = waiting; seen = ids
            drainLogoWaiting(); emit(); return@withLock
        }
        if (!Projects.isWrap(outer, identity)) return@withLock
        val inner = try { Projects.unwrap(outer, identity, now(), signer::nip44Decrypt) }
        catch (e: CancellationException) { throw e }
        catch (e: SignerException) { throw e }
        catch (_: Exception) { null }
        live()
        val next = inner?.let { directory.accept(it, now()) } ?: directory
        val ids = seenWith(outer.id)
        val out = pruneLogos(next)
        save(next, seenIds = ids, logoOut = out); directory = next; seen = ids; logoPending = out; emit()
        drainLogoWaiting()
    }

    private fun seenWith(id: String) = LinkedHashSet(seen).apply { add(id); while (size > 2048) remove(first()) }

    /** Called with the account mutex. Only verified current directories permit decryption. */
    private suspend fun drainLogoWaiting() {
        val contexts = contexts()
        if (contexts.isEmpty() || logoWaiting.isEmpty()) return
        var next = logos
        val waiting = LinkedHashMap(logoWaiting)
        for ((id, outer) in logoWaiting) {
            val inner = try { ProjectLogos.unwrap(outer, identity, contexts, now(), signer::nip44Decrypt) }
            catch (e: CancellationException) { throw e }
            catch (e: SignerException) { throw e }
            catch (_: Exception) { null }
            live()
            if (inner == null) continue
            waiting.remove(id)
            val context = contexts.find { it.reference == logoRef(inner) } ?: continue
            next = next.accept(inner, context, now())
        }
        if (waiting.size == logoWaiting.size && next === logos) return
        val out = pruneLogos(images = next)
        save(images = next, logoOut = out, waiting = waiting)
        logos = next; logoPending = out; logoWaiting = waiting; emit()
    }

    private suspend fun sign(value: JsonObject): NostrEvent = Projects.sign(identity, value, now(), signer::sign).also { live() }
    private fun existing(request: String, hash: String): String? {
        check(REQUEST.matches(request)) { "Invalid project request" }
        check(request !in logoReceipts) { "This request belongs to a project logo change" }
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
            val previousLogo = context(ref)?.let(logos::current)?.takeIf { it.heads.size == 1 && !it.conflicted }
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
            val nextAuthority = Projects.authority(ref, canonical)
            val refresh = if (previousLogo != null && current?.authority != nextAuthority) {
                val context = ProjectLogoContext(ref, canonical)
                val refreshRequest = Digests.sha256(buildJsonObject { put("refresh", request); put("authority", nextAuthority) }
                    .toString().toByteArray()).toHex()
                val logo = signLogo(context, previousLogo.image, 1, refreshRequest)
                LogoRefresh(logo, logoOutgoing(logo, context))
            } else null
            commit(primary, outgoing, request, hash, refresh)
        }
        sendInBackground(); return result
    }

    /** Owner-only artwork; directory and companion heads bind the user's reviewed context. */
    suspend fun updateLogo(ref: ProjectReference, expectedHeads: List<String>, expectedLogoHeads: List<String>,
        image: LogoImage?, request: String, stillAllowed: () -> Boolean = { true }): String {
        val heads = expectedHeads.sorted(); val logoHeads = expectedLogoHeads.sorted()
        val chosen = image?.let { requireNotNull(LogoImages.read(it.toJson())) { "Invalid project logo" } }
        val result = gate.withLock {
            requireReady(); check(ref.owner == identity) { "Only the project owner can change its logo" }
            check(stillAllowed()) { "This project changed or its preview closed. Open the logo editor again." }
            check(REQUEST.matches(request)) { "Invalid project logo request" }
            check(request !in receipts) { "This request belongs to a project directory change" }
            val context = context(ref) ?: error("Review the current project before changing its logo")
            val authority = Projects.authority(ref, context.definition)
            val hash = intent(ref, "logo", buildJsonObject {
                put("authority", authority); put("image", chosen?.toJson() ?: JsonNull)
                put("logoHeads", JsonArray(logoHeads.map(::JsonPrimitive)))
            }, heads)
            logoReceipts[request]?.let { check(it.intent == hash) { "This request belongs to a different project logo" }; return@withLock it.head }
            val current = directory.projects().first { it.reference == ref }
            val projection = logos.current(context)
            check(current.heads == heads && projection.heads == logoHeads) { "The project or logo changed. Review it before sharing." }
            val primary = signLogo(context, chosen, Math.addExact(projection.version, 1), request)
            check(stillAllowed()) { "This project changed or its preview closed. Open the logo editor again." }
            val next = logos.accept(primary, context, now())
            check(primary.id in next.current(context).heads) { "Project logo directory is full" }
            val out = pruneLogos(images = next)
            logoOutgoing(primary, context).forEach { out[it.outer.id] = it }
            val requests = LinkedHashMap(logoReceipts).apply { put(request, Receipt(hash, primary.id)) }
            save(images = next, logoOut = out, logoRequests = requests)
            logos = next; logoPending = out; logoReceipts = requests; emit(); primary.id
        }
        sendInBackground(); return result
    }

    private suspend fun signLogo(context: ProjectLogoContext, image: LogoImage?, version: Int, request: String): NostrEvent =
        ProjectLogos.sign(identity, context, buildJsonObject {
            put("v", 1); put("op", "logo"); put("project", context.reference.project)
            put("authority", Projects.authority(context.reference, context.definition)); put("version", version)
            put("request", request); put("image", image?.toJson() ?: JsonNull)
        }, now(), signer::sign).also { live() }

    private fun logoOutgoing(event: NostrEvent, context: ProjectLogoContext): List<Pending> =
        context.definition.getValue("members").jsonArray.map {
            val recipient = text(it.jsonObject, "pubkey")
            Pending(event, ProjectLogos.wrap(event, recipient, context, now()), recipient)
        }

    private fun sendInBackground() { scope.launch {
        try { retry() } catch (e: CancellationException) { throw e } catch (_: Exception) { /* save already records the failure */ }
    } }

    private suspend fun commit(primary: NostrEvent, outgoing: List<Pending>, request: String, hash: String,
        refresh: LogoRefresh? = null): String {
        live(); val next = directory.accept(primary, now())
        check(next.events().any { it.id == primary.id }) { "Project directory is full" }
        val recipients = outgoing.map { it.recipient }.toSet()
        val out = LinkedHashMap(pending.filterValues { group(it.inner) != group(primary) || it.recipient !in recipients })
        outgoing.forEach { out[it.outer.id] = it }
        val requests = LinkedHashMap(receipts).apply { put(request, Receipt(hash, primary.id)) }
        val images = refresh?.let { logos.accept(it.event, requireNotNull(context(logoRef(it.event), next)), now()) } ?: logos
        if (refresh != null) check(images.events().any { it.id == refresh.event.id }) { "Project logo directory is full" }
        val logoOut = pruneLogos(next, images)
        refresh?.outgoing?.forEach { logoOut[it.outer.id] = it }
        save(next, out, requests, images = images, logoOut = logoOut)
        directory = next; pending = out; receipts = requests; logos = images; logoPending = logoOut; emit()
        drainLogoWaiting(); return primary.id
    }

    /** Publishes only durable exact envelopes. Opening a cache never automatically replays them. */
    suspend fun retry() {
        if (!sendGate.tryLock()) return
        var failed = false
        try {
            val generation = transport.publicationGeneration()
            val batch = gate.withLock { if (closed || !state.value.ready) emptyList() else pending.values.toList() + logoPending.values.toList() }
            for (chunk in batch.chunked(4)) {
                if (closed) break
                beforePublish()
                if (closed) break
                val accepted = coroutineScope { chunk.map { p -> async {
                    val logo = p.outer.tags == listOf(listOf("p", p.recipient), listOf("l", ProjectLogos.APP))
                    if (gate.withLock { closed || fatal != null || p.outer.id !in (if (logo) logoPending else pending) }) return@async null
                    val allowed = { !closed && p.outer.id in eligibleLogoOuters }
                    try {
                        val confirmed = withTimeout(15_000) {
                            if (logo) transport.publishConfirmedGuarded(p.outer, generation, allowed)
                            else transport.publishConfirmed(p.outer)
                        }
                        if (confirmed && (!logo || allowed())) p.outer.id else null
                    }
                    catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e; null }
                    catch (_: Exception) { null }
                } }.awaitAll() }
                gate.withLock {
                    if (!closed) {
                        val out = LinkedHashMap(pending).apply { accepted.filterNotNull().forEach(::remove) }
                        val logoOut = pruneLogos().apply { accepted.filterNotNull().forEach(::remove) }
                        // A superseded or withdrawn envelope is intentionally withheld,
                        // rather than an update still waiting for relay acceptance.
                        if (chunk.zip(accepted).any { (p, receipt) -> receipt == null && (p.outer.id in out || p.outer.id in logoOut) }) failed = true
                        save(out = out, logoOut = logoOut); pending = out; logoPending = logoOut
                        networkError = if (failed) "Some project changes are waiting to send." else null; emit()
                    }
                }
            }
        } catch (e: Exception) { failed = true; throw e }
        finally {
            withContext(NonCancellable) { gate.withLock {
                sendGate.unlock()
                if (!failed && !closed && state.value.ready && (pending.isNotEmpty() || logoPending.isNotEmpty())) sendInBackground()
            } }
        }
    }

    suspend fun close() {
        closed = true; eligibleLogoOuters = emptySet(); collector?.cancel()
        withContext(NonCancellable) { gate.withLock {
            ready = false; syncing = false; directory = ProjectDirectoryState(identity); pending.clear(); receipts.clear(); seen.clear()
            logos = ProjectLogoDirectoryState(); logoPending.clear(); logoReceipts.clear(); logoWaiting.clear(); emit()
            if (acquired) { storage.release(); acquired = false }
        } }
    }

    companion object {
        const val MAX_CACHE_BYTES = 32 * 1024 * 1024
        private val HEX = Regex("[0-9a-f]{64}")
        private val REQUEST = Regex("[a-zA-Z0-9_-]{16,80}")
    }
}
