package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.protocol.spryEndpoint
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.relay.RoomTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Wire-compatible with the browser's SpryAccountSync. Only self-encrypted
 * account settings travel; room membership and device call keys stay local. */
internal class SpryAccountSync(
    private val signer: ParticipantSigner,
    private val transport: RoomTransport,
    private val storage: ProjectStorage,
    private val scope: CoroutineScope,
    private val current: () -> Boolean,
    private val adopt: suspend (SpryPairing?) -> Unit,
    private val status: (String) -> Unit,
    private val beforePublish: suspend () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    companion object {
        const val ADDRESS = "kithmoot.connections.v1"
        private val TAGS = listOf(listOf("d", ADDRESS), listOf("l", "kithmoot.private-settings.v1"))
        private fun newer(a: NostrEvent, b: NostrEvent?): Boolean = b == null || a.createdAt > b.createdAt || a.createdAt == b.createdAt && a.id < b.id
    }
    private val gate = Mutex()
    private var latest: NostrEvent? = null
    private var pending: NostrEvent? = null
    private var pendingValue: JsonObject? = null
    private var removalAt: Long? = null
    private var collector: Job? = null
    private var acquired = false
    @Volatile private var closed = false
    private val seen = linkedSetOf<String>()
    private fun live() = !closed && current()
    private fun valid(event: NostrEvent) = event.kind == 30078 && event.pubkey == signer.pubkey &&
        event.createdAt in 0..now() + 60 && event.content.length <= 16000 && event.tags == TAGS && Events.verify(event)
    private suspend fun persist() = storage.save(buildJsonObject {
        latest?.let { put("latest", it.toJson()) }; pending?.let { put("pending", it.toJson()) }
        removalAt?.let { put("removalAt", it) }
    }.toString())

    suspend fun open() {
        check(signer.canEncrypt && live())
        storage.acquire(); acquired = true
        val cached = storage.load()?.also { require(it.length <= 40000) }?.let { Json.parseToJsonElement(it).jsonObject }
        cached?.get("removalAt")?.jsonPrimitive?.let { require(!it.isString && it.long in 0..now() + 60); removalAt = it.long }
        cached?.get("pending")?.let { NostrEvent.fromJson(it) }?.let { require(valid(it)); pending = it }
        cached?.get("latest")?.let { NostrEvent.fromJson(it) }?.let { require(valid(it)); receive(it) }
        if (!live()) return
        val filters = listOf(Filter(kinds = listOf(30078), authors = listOf(signer.pubkey), tags = mapOf("#d" to listOf(ADDRESS))))
        collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { transport.subscribe(filters).collect { receive(it) } }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (live()) status("Spry sync stopped. Check your signer and relays, then tap Sync Spry.") }
        }
        try { lookup(filters) }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live()) status("Spry lookup could not finish. Tap Sync Spry to retry.") }
        if (live()) retry()
    }

    private suspend fun lookup(filters: List<Filter>) {
        transport.queryAvailable(filters).sortedWith(compareByDescending<NostrEvent> { it.createdAt }.thenBy { it.id }).forEach { receive(it) }
    }
    suspend fun refresh() {
        gate.withLock { seen.clear() }
        val filters = listOf(Filter(kinds = listOf(30078), authors = listOf(signer.pubkey), tags = mapOf("#d" to listOf(ADDRESS))))
        lookup(filters)
        retry()
    }

    suspend fun receive(event: NostrEvent) = gate.withLock {
        if (!live() || !valid(event) || event.id in seen) return@withLock
        if (seen.size >= 64) seen.remove(seen.first())
        seen.add(event.id)
        apply(event)
    }
    private suspend fun apply(event: NostrEvent) {
        if (!live() || !valid(event) || !newer(event, latest)) return
        val waiting = pending
        if (waiting != null && event.id != waiting.id && !newer(event, waiting)) return
        removalAt?.let { if (event.createdAt < it || event.createdAt == it && event.id != waiting?.id) return }
        try {
            val text = if (event.id == waiting?.id && pendingValue != null) pendingValue.toString() else signer.nip44Decrypt(signer.pubkey, event.content)
            if (!live() || text.length > 8192) return
            val value = Json.parseToJsonElement(text).jsonObject
            require(value.getValue("v").jsonPrimitive.let { !it.isString && it.int == 1 })
            require(value.getValue("at").jsonPrimitive.let { !it.isString && it.long == event.createdAt })
            val raw = value.getValue("pairing")
            val pair = if (raw == JsonNull) null else parsePair(raw.jsonObject)
            adopt(pair)
            if (!live()) return
            latest = event
            if (waiting != null && (waiting.id == event.id || newer(event, waiting))) { pending = null; pendingValue = null }
            removalAt?.let { if (event.createdAt >= it) removalAt = null }
            persist()
            status(if (pair == null) "Spry was unpaired for your Nostr account." else "Spry loaded from your encrypted Nostr account. Call access is shared automatically.")
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live()) status("Spry account data could not be opened. Your local pairing is kept.") }
    }

    private fun parsePair(raw: JsonObject): SpryPairing {
        require(raw.getValue("v").jsonPrimitive.let { !it.isString && it.int == 1 })
        val id = raw.getValue("id").jsonPrimitive.content
        val key = raw.getValue("secretKey").jsonPrimitive.content
        require(Regex("^[A-Za-z0-9_-]{43}$").matches(id) && Regex("^[a-f0-9]{64}$").matches(key))
        return SpryPairing(spryEndpoint(raw.getValue("endpoint").jsonPrimitive.content), id, key, signer.pubkey)
    }
    suspend fun save(pair: SpryPairing?): Boolean = gate.withLock { change(pair) }
    private suspend fun change(pair: SpryPairing?, retainedRemovalAt: Long? = null): Boolean {
        if (!live()) return false
        try {
            require(pair == null || pair.account == signer.pubkey)
            val at = retainedRemovalAt ?: maxOf(now(), (latest?.createdAt ?: -1) + 1, (pending?.createdAt ?: -1) + 1)
            require(at <= now() + 60)
            removalAt = if (pair == null) at else null
            pending = null; pendingValue = null; persist()
            val value = buildJsonObject {
                put("v", 1); put("at", at)
                put("pairing", pair?.let { buildJsonObject {
                    put("v", 1); put("endpoint", it.endpoint); put("id", it.id); put("secretKey", it.secretKey)
                } } ?: JsonNull)
            }
            val content = signer.nip44Encrypt(signer.pubkey, value.toString())
            if (!live()) return false
            val event = signer.sign(30078, at, TAGS, content)
            if (!live()) return false
            require(valid(event) && event.createdAt == at && event.content == content)
            pending = event; pendingValue = value; persist()
            return send()
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live()) status("Spry account sync needs your signer and relays. Tap Sync Spry to retry."); return false }
    }
    suspend fun retry(): Boolean = gate.withLock { if (pending == null && removalAt != null) change(null, removalAt) else send() }
    private suspend fun send(): Boolean {
        val event = pending ?: latest ?: return true
        if (!live()) return false
        return try {
            beforePublish()
            if (!live()) return false
            check(transport.publishConfirmed(event))
            if (!live()) return false
            if (pending?.id == event.id) { apply(event); if (pending?.id == event.id) return false }
            status("Spry settings accepted by your account relays, encrypted to your Nostr key. Your other signed-in devices can load them.")
            true
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { if (live()) status("Spry account sync is waiting for your relays. Tap Sync Spry to retry."); false }
    }
    suspend fun close() {
        closed = true
        collector?.cancelAndJoin(); collector = null
        gate.withLock { if (acquired) { storage.release(); acquired = false } }
    }
}
