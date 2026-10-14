package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.NostrEvent
import dev.forgesworn.kithmoot.relay.Filter
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.net.URI

data class ContactInboxView(val ready: Boolean = false, val invitations: List<ContactInvitation> = emptyList(),
    val pending: Int = 0, val error: String? = null)

/** Exclusive encrypted account journal. Save exact envelopes before any
 * delivery; clear each destination only after its relay acknowledges. */
class ContactInbox(private val actor: ParticipantSigner, private val storage: ProjectStorage,
    private val scope: CoroutineScope, private val incoming: ContactsRelay,
    private val relay: (List<String>) -> ContactsRelay, private val current: () -> Boolean,
    private val allowed: (String) -> Boolean, private val blocked: (String) -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    private val beforeNetwork: suspend () -> Unit = {}) {
    private data class Pending(val outer: NostrEvent, val recipient: String, val peer: String, val relays: List<String>, val invitation: String)
    private val gate = Mutex()
    private val sends = Mutex()
    private var rumors = emptyList<JsonObject>()
    private var pending = emptyList<Pending>()
    private var dismissed = emptySet<String>()
    private val seen = linkedSetOf<String>()
    private var collector: Job? = null
    private var acquired = false
    @Volatile private var closed = false
    @Volatile private var ready = false
    @Volatile private var eligible = emptySet<String>()
    private var error: String? = null
    private val mutable = MutableStateFlow(ContactInboxView())
    val state = mutable.asStateFlow()
    private fun live() = check(!closed && current()) { "The signed-in account changed" }
    private fun emit() {
        eligible = if (ready && !closed && current()) pending.map { it.outer.id }.toSet() else emptySet()
        mutable.value = ContactInboxView(ready && !closed && current(), rumors.mapNotNull { ContactInvitations.read(it, actor.pubkey, now()) }
            .filter { !blocked(if (it.sender == actor.pubkey) it.recipient else it.sender) }, pending.size, error)
    }
    private suspend fun save(next: List<JsonObject> = rumors, out: List<Pending> = pending, tombstones: Set<String> = dismissed) {
        live(); check(next.size <= 256 && out.size <= 128 && tombstones.size <= 2048) { "Your invitation inbox is full" }
        val text = buildJsonObject {
            put("v", 1); put("account", actor.pubkey); put("invitations", JsonArray(next))
            put("dismissed", JsonArray(tombstones.map(::JsonPrimitive)))
            put("pending", JsonArray(out.map { p -> buildJsonObject {
                put("outer", p.outer.toJson()); put("recipient", p.recipient); put("peer", p.peer)
                put("relays", JsonArray(p.relays.map(::JsonPrimitive))); put("invitation", p.invitation)
            } }))
        }.toString()
        check(text.toByteArray().size <= 2 * 1024 * 1024)
        try { storage.save(text) } catch (e: Exception) {
            if (e is CancellationException) throw e
            ready = false; error = "Your invitations could not be saved. Existing data has been kept."; emit(); throw e
        }
        live(); rumors = next; pending = out; dismissed = tombstones; emit()
    }
    suspend fun open() = gate.withLock {
        live(); check(!acquired)
        try {
            storage.acquire(); acquired = true
            storage.load()?.let { text ->
                check(text.toByteArray().size <= 2 * 1024 * 1024)
                val row = Json.parseToJsonElement(text).jsonObject
                check(row["v"] == JsonPrimitive(1) && row["account"] == JsonPrimitive(actor.pubkey))
                rumors = row.getValue("invitations").jsonArray.map { it.jsonObject.also { r -> check(ContactInvitations.read(r, actor.pubkey, now()) != null) } }
                dismissed = row.getValue("dismissed").jsonArray.map { it.jsonPrimitive.content.also { id -> check(id.matches(Regex("[0-9a-f]{64}"))) } }.toSet()
                pending = row.getValue("pending").jsonArray.map { item ->
                    val p = item.jsonObject
                    val recipient = p.getValue("recipient").jsonPrimitive.content
                    val peer = p.getValue("peer").jsonPrimitive.content
                    val id = p.getValue("invitation").jsonPrimitive.content
                    val outer = NostrEvent.fromJson(p.getValue("outer"))
                    val invitation = rumors.mapNotNull { ContactInvitations.read(it, actor.pubkey, now()) }.single { it.id == id }
                    check(invitation.sender == actor.pubkey && invitation.recipient == peer && recipient in listOf(actor.pubkey, peer))
                    check(ContactInvitations.isWrap(outer, recipient, now()))
                    Pending(outer, recipient, peer, destinations(p.getValue("relays").jsonArray.map { it.jsonPrimitive.content }), id)
                }
                check(rumors.size <= 256 && pending.size <= 128 && dismissed.size <= 2048 && rumors.none { it.getValue("id").jsonPrimitive.content in dismissed })
            } ?: save()
            live(); ready = true; emit()
            collector = scope.launch {
                beforeNetwork()
                incoming.transport.subscribe(listOf(Filter(kinds = listOf(1059), tags = mapOf("#p" to listOf(actor.pubkey)), since = maxOf(0, now() - 32 * 86400), limit = 256)))
                    .buffer(64, BufferOverflow.DROP_OLDEST).collect { event ->
                        gate.withLock {
                            if (!closed && current() && ready && event.id !in seen && ContactInvitations.isWrap(event, actor.pubkey, now())) {
                                try {
                                    val r = ContactInvitations.unwrap(event, actor, now())
                                    live()
                                    val invite = r?.let { ContactInvitations.read(it, actor.pubkey, now()) }
                                    if (invite != null && !blocked(if (invite.sender == actor.pubkey) invite.recipient else invite.sender) && invite.id !in dismissed &&
                                        rumors.none { it["id"] == JsonPrimitive(invite.id) }) save(rumors + r)
                                    seen.add(event.id); while (seen.size > 2048) seen.remove(seen.first())
                                } catch (e: CancellationException) { throw e }
                                catch (_: Exception) { error = "A private invitation could not be saved. Reopen Contacts to retry."; emit() }
                            }
                        }
                    }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { ready = false; error = "Your private inbox could not open. Check your signer, relays and storage."; emit() }
    }
    suspend fun enqueue(peer: String, room: String, link: String, peerRelays: List<String>, selfRelays: List<String>): ContactInvitation = gate.withLock {
        live(); check(ready && allowed(peer)) { "This contact is no longer available to message" }
        val at = now()
        val r = ContactInvitations.rumor(actor.pubkey, peer, room, link, at)
        val permission = { !closed && current() && ready && allowed(peer) }
        val other = ContactInvitations.wrap(r, actor, peer, at, permission)
        val own = ContactInvitations.wrap(r, actor, actor.pubkey, at, permission)
        check(permission())
        val invite = ContactInvitations.read(r, actor.pubkey, at)!!
        save(rumors + r, pending + listOf(Pending(other, peer, peer, destinations(peerRelays), invite.id), Pending(own, actor.pubkey, peer, destinations(selfRelays), invite.id)))
        invite
    }
    suspend fun retry() = sends.withLock {
        val selected = gate.withLock { live(); check(ready); pending.toList() }
        try {
            for (p in selected) {
                beforeNetwork()
                val permission = { !closed && current() && ready && allowed(p.peer) && p.outer.id in eligible }
                if (!permission()) continue
                val connection = relay(p.relays)
                try {
                    check(connection.transport.publishConfirmedGuarded(p.outer, connection.transport.publicationGeneration(), permission)) { "Relay did not accept this invitation" }
                    gate.withLock { live(); save(out = pending.filterNot { it.outer.id == p.outer.id }) }
                } finally { connection.close() }
            }
            gate.withLock { error = null; emit() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { gate.withLock { if (!closed && current()) { error = "The invitation is saved. Retry delivery when your signer and relays are available."; emit() } } }
    }
    suspend fun dismiss(id: String) = gate.withLock {
        live(); check(ready && pending.none { it.invitation == id }) { "Retry the pending invitation before dismissing it" }
        save(rumors.filterNot { it["id"] == JsonPrimitive(id) }, tombstones = (dismissed + id).takeLastSet(2048))
    }
    suspend fun forgetRoom(room: String) = gate.withLock {
        live(); if (!ready) return@withLock
        val removed = rumors.mapNotNull { ContactInvitations.read(it, actor.pubkey, now()) }.filter { it.room == room }.map { it.id }.toSet()
        if (removed.isNotEmpty()) save(rumors.filterNot { it["id"]?.jsonPrimitive?.content in removed }, pending.filterNot { it.invitation in removed }, (dismissed + removed).takeLastSet(2048))
    }
    fun repaint() { emit() }
    suspend fun close() {
        closed = true; ready = false; eligible = emptySet(); collector?.cancelAndJoin(); incoming.close()
        gate.withLock { if (acquired) storage.release(); acquired = false; rumors = emptyList(); pending = emptyList(); dismissed = emptySet(); emit() }
    }
    private fun Set<String>.takeLastSet(size: Int) = toList().takeLast(size).toSet()
    private fun destinations(relays: List<String>): List<String> {
        check(relays.isNotEmpty() && relays.size <= 12)
        return relays.map { URI(it).also { uri -> check(uri.scheme in setOf("ws", "wss") && uri.host != null && uri.userInfo == null && uri.fragment == null) }.toString() }.distinct()
    }
}
