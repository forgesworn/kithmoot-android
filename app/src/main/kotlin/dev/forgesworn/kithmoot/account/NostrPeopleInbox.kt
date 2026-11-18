package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.NostrEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

data class NostrPeopleThread(val peer: String, val messages: List<NostrDirectMessage>, val unread: Int)
data class NostrPeopleView(val enabled: Boolean = false, val status: String = "", val threads: List<NostrPeopleThread> = emptyList(),
    val sending: Boolean = false, val pending: Boolean = false) {
    val unread: Int get() = threads.sumOf { it.unread }
}

internal class NostrPeopleInbox(
    private val actor: ParticipantSigner,
    private val vault: ProjectStorage,
    private val current: () -> Boolean,
    private val blocked: (String) -> Boolean,
    private val now: () -> Long,
    private val keepNodeInvitation: suspend (JsonObject) -> Boolean = { false },
) {
    private data class Journal(val version: Int = 1, val account: String, val messages: List<NostrDirectMessage> = emptyList(),
        val read: Set<String> = emptySet(), val pending: Pending? = null)

    private data class Pending(val message: NostrDirectMessage, val copies: List<JsonObject>)

    private val lock = Mutex()
    private val sends = Mutex()
    private val seen = linkedSetOf<String>()
    private var journal = Journal(account = actor.pubkey)
    private var ready = false
    private var signerNeedsAttention = false
    private var status = "Connecting to your Nostr inbox…"
    private val mutable = MutableStateFlow(NostrPeopleView(enabled = true, status = status))
    val state = mutable.asStateFlow()

    private fun live() = check(current() && ready) { "The account or inbox changed." }

    private fun emit() {
        if (!current()) return
        val at = now()
        val visible = journal.messages.filter { (it.expiresAt ?: Long.MAX_VALUE) > at && !blocked(it.peer) }
        val threads = visible.groupBy { it.peer }.map { (peer, messages) ->
            NostrPeopleThread(peer, messages, messages.count { it.author != actor.pubkey && it.id !in journal.read })
        }.sortedWith(compareByDescending<NostrPeopleThread> { it.unread > 0 }
            .thenByDescending { thread -> if (thread.unread > 0) 0 else thread.messages.count { it.author == actor.pubkey } }
            .thenByDescending { it.messages.last().sentAt }
            .thenBy { it.peer })
        mutable.value = NostrPeopleView(true, status, threads, sends.isLocked, journal.pending != null)
    }

    suspend fun open() {
        vault.acquire()
        val restored = vault.load()?.let(::decode)
        require(restored == null || restored.version == 1 && restored.account == actor.pubkey && restored.messages.size <= MAX_MESSAGES)
        if (restored != null) journal = restored
        ready = true
        emit()
    }

    suspend fun close() {
        lock.withLock { ready = false; journal = Journal(account = actor.pubkey); seen.clear(); mutable.value = NostrPeopleView() }
        vault.release()
    }

    suspend fun connectionStatus(value: String) = lock.withLock {
        if (current() && (value.isNotEmpty() || status.startsWith("Connecting"))) { status = value; emit() }
    }

    suspend fun repaint() = lock.withLock { emit() }

    suspend fun receive(event: NostrEvent, foreground: () -> Boolean) {
        fun reading() = current() && foreground()
        if (!reading() || signerNeedsAttention || event.id in seen) return
        var invitationSaved = true
        val opened = try {
            NostrDirectMessages.open(event, actor.pubkey, now(), ::reading, blocked, keepNodeInvitation = { rumor ->
                invitationSaved = false
                invitationSaved = keepNodeInvitation(rumor)
            }, decrypt = { peer, ciphertext -> actor.openInboxCiphertext(peer, ciphertext, ::reading) })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: SignerException) {
            signerNeedsAttention = true
            connectionStatus("Allow KithMoot to decrypt Nostr messages in your signer, then refresh.")
            return
        } catch (_: Exception) {
            // A signed wrap can still contain unusable ciphertext.
            null
        }
        if (!reading() || !invitationSaved) return
        if (opened != null) lock.withLock {
            if (!reading()) return
            if (journal.messages.none { it.id == opened.id }) save(withMessage(journal, opened))
        }
        seen.add(event.id)
        if (seen.size > 2_048) seen.remove(seen.first())
    }

    suspend fun markRead(ids: Set<String>) = lock.withLock {
        live()
        val next = journal.read + journal.messages.filter { it.id in ids }.map { it.id }
        if (next != journal.read) save(journal.copy(read = next))
    }

    suspend fun send(
        peer: String, text: String, parent: String?,
        resolve: suspend (List<String>) -> Map<String, List<String>>,
        publish: suspend (NostrEvent, List<String>, () -> Boolean) -> Unit,
    ) {
        try {
            sends.withLock {
                lock.withLock { live(); status = ""; emit() }
                val pending = lock.withLock { journal.pending }
                if (pending != null && text.isNotEmpty()) {
                    lock.withLock { status = "Retry the waiting Nostr message first."; emit() }
                    return@withLock
                }
                if (pending == null && text.isEmpty()) return@withLock
                val target = pending?.message?.peer ?: peer
                fun allowed() = current() && !blocked(target)
                check(allowed()) { "The account or contact changed." }
                val routes = resolve(listOf(actor.pubkey, target))
                check(routes[actor.pubkey].orEmpty().isNotEmpty() && routes[target].orEmpty().isNotEmpty()) {
                    "Both accounts need published Nostr inbox relays."
                }
                val original = pending ?: NostrDirectMessages.prepare(actor, peer, text, parent, now(), ::allowed)
                    .let { (message, copies) -> Pending(message, copies.map(NostrEvent::toJson)) }
                    .also { row -> lock.withLock { check(allowed()); save(journal.copy(pending = row)) } }
                for (copy in original.copies) {
                    check(allowed()) { "The account or contact changed." }
                    val event = NostrEvent.fromJson(copy)
                    val recipient = event.tags.single().get(1)
                    check(Events.verify(event) && recipient in setOf(actor.pubkey, target))
                    publish(event, routes.getValue(recipient), ::allowed)
                    lock.withLock {
                        live()
                        val rest = journal.pending!!.copies.filter { it != copy }
                        if (rest.isEmpty()) save(withMessage(journal.copy(pending = null), original.message))
                        else save(journal.copy(pending = original.copy(copies = rest)))
                    }
                }
                lock.withLock { if (current()) { status = "Sent to the inbox relays."; emit() } }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            lock.withLock {
                if (current()) {
                    status = if (journal.pending != null) "Some copies are waiting to send. Retry on the inbox relays."
                    else error.message?.takeIf { it in setOf("Both accounts need published Nostr inbox relays.", "The account or contact changed.") }
                        ?: "The Nostr message could not be prepared."
                    emit()
                }
            }
        } finally { lock.withLock { emit() } }
    }

    private fun withMessage(from: Journal, message: NostrDirectMessage): Journal {
        val messages = (from.messages.filter { it.id != message.id && (it.expiresAt ?: Long.MAX_VALUE) > now() } + message)
            .sortedWith(compareBy<NostrDirectMessage> { it.sentAt }.thenBy { it.id }).takeLast(MAX_MESSAGES)
        return from.copy(messages = messages, read = from.read.intersect(messages.mapTo(HashSet()) { it.id }))
    }

    private suspend fun save(next: Journal) {
        live()
        val rows = next.messages.toMutableList()
        var stored = next
        var value = encode(stored)
        while (value.toByteArray().size > 2 * 1024 * 1024 && rows.isNotEmpty()) {
            val evicted = rows.removeAt(0)
            stored = stored.copy(messages = rows.toList(), read = stored.read - evicted.id)
            value = encode(stored)
        }
        vault.save(value)
        live(); journal = stored; emit()
    }

    private fun encode(journal: Journal): String = buildJsonObject {
        put("v", journal.version); put("account", journal.account)
        put("messages", JsonArray(journal.messages.map(::messageJson)))
        put("read", JsonArray(journal.read.map(::JsonPrimitive)))
        journal.pending?.let { row -> put("pending", buildJsonObject {
            put("message", messageJson(row.message)); put("copies", JsonArray(row.copies))
        }) }
    }.toString()

    private fun decode(value: String): Journal {
        val json = Json.parseToJsonElement(value).jsonObject
        val pending = json["pending"]?.jsonObject?.let { row ->
            Pending(messageFrom(row.getValue("message").jsonObject), row.getValue("copies").jsonArray.map { it.jsonObject })
        }
        return Journal(json.getValue("v").jsonPrimitive.int, json.getValue("account").jsonPrimitive.content,
            json.getValue("messages").jsonArray.map { messageFrom(it.jsonObject) },
            json.getValue("read").jsonArray.map { it.jsonPrimitive.content }.toSet(), pending)
    }

    private fun messageJson(message: NostrDirectMessage): JsonObject = buildJsonObject {
        put("id", message.id); put("author", message.author); put("peer", message.peer)
        put("text", message.text); put("sent", message.sentAt)
        message.expiresAt?.let { put("expires", it) }
    }

    private fun messageFrom(json: JsonObject): NostrDirectMessage = NostrDirectMessage(
        json.getValue("id").jsonPrimitive.content, json.getValue("author").jsonPrimitive.content,
        json.getValue("peer").jsonPrimitive.content, json.getValue("text").jsonPrimitive.content,
        json.getValue("sent").jsonPrimitive.long, json["expires"]?.jsonPrimitive?.long)

    companion object { const val MAX_MESSAGES = 128 }
}
