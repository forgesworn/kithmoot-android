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
/** [status] is the inbox connection; [sendStatus] is the outcome of the latest send, kept apart so
 *  a reconnect after the signer screen cannot overwrite it. [sends] and [sent] change on each send
 *  attempt and each success, so a view opened earlier can tell a fresh result from an old one. */
data class NostrPeopleView(val enabled: Boolean = false, val status: String = "", val threads: List<NostrPeopleThread> = emptyList(),
    val sending: Boolean = false, val pending: Boolean = false, val sendStatus: String = "", val sends: Int = 0, val sent: Int = 0) {
    val unread: Int get() = threads.sumOf { it.unread }
}

internal class NostrPeopleInbox(
    private val actor: ParticipantSigner,
    private val vault: ProjectStorage,
    private val current: () -> Boolean,
    private val blocked: (String) -> Boolean,
    private val now: () -> Long,
    private val keepNodeInvitation: suspend (JsonObject) -> Boolean = { false },
    /** Diagnostics only: outcomes and error types, never message text or keys. */
    private val log: (String) -> Unit = {},
) {
    private data class Journal(val version: Int = 1, val account: String, val messages: List<NostrDirectMessage> = emptyList(),
        val read: Set<String> = emptySet(), val pending: Pending? = null)

    private data class Pending(val message: NostrDirectMessage, val copies: List<JsonObject>)
    private class MissingInbox(message: String) : IllegalStateException(message)

    private val lock = Mutex()
    private val sends = Mutex()
    private val seen = linkedSetOf<String>()
    private var journal = Journal(account = actor.pubkey)
    private var ready = false
    private var signerNeedsAttention = false
    private var status = "Connecting to your Nostr inbox…"
    private var sendStatus = ""
    private var sendCount = 0
    private var sentCount = 0
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
        mutable.value = NostrPeopleView(true, status, threads, sends.isLocked, journal.pending != null, sendStatus, sendCount, sentCount)
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
        /** Publishes one copy and returns the relays that accepted it; throws if none did. */
        publish: suspend (NostrEvent, List<String>, () -> Boolean) -> List<String>,
    ) {
        try {
            sends.withLock {
                lock.withLock { live(); sendStatus = ""; sendCount = nextSerial(); emit() }
                val pending = lock.withLock { journal.pending }
                if (pending != null && text.isNotEmpty()) {
                    lock.withLock { sendStatus = "Retry the waiting Nostr message first."; emit() }
                    return@withLock
                }
                if (pending == null && text.isEmpty()) return@withLock
                val target = pending?.message?.peer ?: peer
                fun allowed() = current() && !blocked(target)
                check(allowed()) { "The account or contact changed." }
                val routes = resolve(listOf(actor.pubkey, target))
                nostrInboxRelayProblem(routes[actor.pubkey].orEmpty(), routes[target].orEmpty())?.let {
                    throw MissingInbox(it)
                }
                var delivered: List<String>? = null
                val original = pending ?: NostrDirectMessages.prepare(actor, peer, text, parent, now(), ::allowed)
                    .let { (message, copies) -> Pending(message, copies.map(NostrEvent::toJson)) }
                    .also { row -> lock.withLock { check(allowed()); save(journal.copy(pending = row)) } }
                for (copy in original.copies) {
                    check(allowed()) { "The account or contact changed." }
                    val event = NostrEvent.fromJson(copy)
                    val recipient = event.tags.single().get(1)
                    check(Events.verify(event) && recipient in setOf(actor.pubkey, target))
                    val accepted = publish(event, routes.getValue(recipient), ::allowed)
                    if (recipient == target) delivered = accepted
                    lock.withLock {
                        live()
                        val rest = journal.pending!!.copies.filter { it != copy }
                        if (rest.isEmpty()) save(withMessage(journal.copy(pending = null), original.message))
                        else save(journal.copy(pending = original.copy(copies = rest)))
                    }
                }
                lock.withLock {
                    if (current()) {
                        // A retried copy for this account alone leaves [delivered] unknown.
                        val missing = delivered?.let { routes.getValue(target) - it.toSet() }.orEmpty()
                        sendStatus = if (missing.isEmpty()) SENT
                            else "$SENT ${missing.joinToString { relayHost(it) }} did not accept it."
                        sentCount = nextSerial()
                        log("send accepted; missing=${missing.size}")
                        emit()
                    }
                }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            log("send failed: ${error::class.simpleName}")
            lock.withLock {
                if (current()) {
                    val waiting = journal.pending
                    sendStatus = if (error is MissingInbox) {
                        if (waiting != null) error.message.orEmpty().removeSuffix("Nothing was sent.") +
                            "Previously accepted copies may remain. Your waiting message is kept for retry."
                        else error.message.orEmpty()
                    } else if (waiting != null) {
                        val theirs = waiting.copies.any { copy -> NostrEvent.fromJson(copy).tags.single().getOrNull(1) == waiting.message.peer }
                        if (theirs) "None of their inbox relays accepted the message yet. Retry sends the same message."
                        else "Sent, but your own copy is waiting. Retry to keep it in your inbox."
                    } else error.message?.takeIf { it == "The account or contact changed." }
                        ?: "The Nostr message could not be prepared. Check your signer and try again."
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

    companion object {
        const val MAX_MESSAGES = 128
        const val SENT = "Sent to their inbox relays."

        internal fun relayHost(url: String): String = url.substringAfter("://").trimEnd('/')

        private val serials = java.util.concurrent.atomic.AtomicInteger()
        /** Never repeats in this process, even across inboxes, so a sheet can tell a new result from an old one. */
        internal fun nextSerial(): Int = serials.incrementAndGet()
    }
}
