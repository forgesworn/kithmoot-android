package dev.forgesworn.kithmoot.discovery

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.storage.ContactBook
import kotlinx.serialization.json.*

interface BoxReader {
    fun subscribe(filter: Filter, receive: (NostrEvent) -> Unit, ready: () -> Unit): () -> Unit
    fun close()
    fun trustedHistory(): Boolean = true
}

/** Explicit per-box consent; persisted signed watermarks never grant offline trust.
 * All callbacks validate the currently held contact revision before writing. */
class BoxDiscovery(
    private val contacts: ContactBook,
    private val reader: (unavailable: () -> Unit) -> BoxReader,
    private val changed: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
) {
    private class Watch(val contact: String, val box: String, val revision: String, val reader: BoxReader) {
        val stops = mutableListOf<() -> Unit>()
        var seed: NostrEvent? = null
        var latest: NostrEvent? = null
        var candidate: NostrEvent? = null
        var claimReady = false
        var statusReady = false
        var proof: VerifiedBoxStatus? = null
        var message = "Checking the box and its keeper’s claim…"
    }
    private val watches = mutableMapOf<Pair<String, String>, Watch>()
    private val stoppedChecks = mutableSetOf<Pair<String, String>>()
    private var closed = false
    private fun str(s: JsonObject?, key: String) = (s?.get(key) as? JsonPrimitive)?.contentOrNull
    private fun number(s: JsonObject?, key: String) = (s?.get(key) as? JsonPrimitive)?.longOrNull
    private fun enabled(s: JsonObject?) = (s?.get("enabled") as? JsonPrimitive)?.booleanOrNull == true
    private fun event(s: JsonObject?, key: String): NostrEvent? = try { s?.get(key)?.let { NostrEvent.fromJson(it) } } catch (_: Exception) { null }
    private fun signed(e: NostrEvent, kind: Int, author: String) = e.kind == kind && e.pubkey == author && e.createdAt in 0..LinkCards.MAX_SAFE && e.toCompactJson().toByteArray(Charsets.UTF_8).size <= 32768 && Events.verify(e)
    private fun current(w: Watch): Pair<ContactBook.Contact, ContactBook.Box>? {
        if (closed || watches[w.contact to w.box] !== w) return null
        val c = contacts.get(w.contact) ?: return null
        val b = c.boxes.firstOrNull { it.p == w.box } ?: return null
        if (c.expires <= now() || !enabled(b.discovery) || ContactBook.discoveryRevision(c, b) != w.revision || str(b.discovery, "revision") != w.revision) return null
        return c to b
    }
    private fun save(w: Watch, values: Map<String, JsonElement>): Boolean {
        val (_, b) = current(w) ?: return false
        return contacts.saveDiscovery(w.contact, w.box, w.revision, JsonObject(b.discovery.orEmpty() + values))
    }
    @Synchronized fun enabled(contact: String, box: String): Boolean {
        if (contact to box in stoppedChecks) return false
        val c = contacts.get(contact) ?: return false
        val b = c.boxes.firstOrNull { it.p == box } ?: return false
        return enabled(b.discovery) && str(b.discovery, "revision") == ContactBook.discoveryRevision(c, b)
    }
    @Synchronized fun setEnabled(contact: String, box: String, on: Boolean, expectedRevision: String? = null) {
        check(!closed)
        if (!on) {
            stoppedChecks += contact to box
            watches[contact to box]?.let(::stop)
        }
        val c = contacts.get(contact) ?: error("This contact is no longer held.")
        val b = c.boxes.firstOrNull { it.p == box } ?: error("This contact no longer has that box.")
        require(!on || c.expires > now()) { "Add a current contact card before checking this box." }
        require(!on || enabled(contact, box) || watches.size < 32) { "Check at most 32 boxes at once. Stop checking another box first." }
        val rev = ContactBook.discoveryRevision(c, b)
        require(!on || expectedRevision == null || expectedRevision == rev) { "This card changed. Check its current box before continuing." }
        val retained = if (str(b.discovery, "nodeId") == b.nodeId) b.discovery.orEmpty() else b.discovery.orEmpty().filterKeys { it in setOf("claim", "claimConflictAt") }
        check(contacts.saveDiscovery(contact, box, rev, JsonObject(retained + mapOf("revision" to JsonPrimitive(rev), "nodeId" to JsonPrimitive(b.nodeId), "enabled" to JsonPrimitive(on))))) { "The contact changed before the preference was saved." }
        if (on) stoppedChecks -= contact to box
        reconcile(); changed()
    }
    @Synchronized fun disableAll() {
        stoppedChecks += watches.keys
        for (w in watches.values.toList()) stop(w)
        for (c in contacts.list()) for (b in c.boxes) if (enabled(b.discovery)) {
            stoppedChecks += c.p to b.p
            contacts.saveDiscovery(c.p, b.p, ContactBook.discoveryRevision(c, b), JsonObject(b.discovery.orEmpty() + ("enabled" to JsonPrimitive(false))))
        }
        changed()
    }
    @Synchronized fun message(contact: String, box: String): String = watches[contact to box]?.message ?: if (enabled(contact, box)) "Waiting to check this box." else "Box status is not being checked."
    @Synchronized fun reconcile() {
        if (closed) return
        for (w in watches.values.toList()) if (current(w) == null) stop(w)
        for (c in contacts.list()) for (b in c.boxes) {
            if (watches.size >= 32) return
            if (c.expires > now() && enabled(c.p, b.p) && c.p to b.p !in watches) start(c, b)
        }
    }
    private fun protect(w: Watch, action: () -> Unit) = synchronized(this) {
        try { if (current(w) != null) action() } catch (_: Exception) { w.proof = null; w.message = "Box status could not be verified or saved."; changed() }
    }
    private fun start(c: ContactBook.Contact, b: ContactBook.Box) {
        var activeWatch: Watch? = null
        val transport = try { reader {
            synchronized(this) {
                val w = activeWatch
                if (w != null && watches[w.contact to w.box] === w) {
                    w.proof = null; w.candidate = null; w.claimReady = false; w.statusReady = false
                    w.message = "Read relays unavailable. Waiting for a fresh box check."; changed()
                }
            }
        } } catch (_: Exception) { return }
        val w = Watch(c.p, b.p, ContactBook.discoveryRevision(c, b), transport)
        activeWatch = w
        watches[c.p to b.p] = w
        fun subscribe(filter: Filter, receive: (NostrEvent) -> Unit, ready: () -> Unit) {
            val stop = transport.subscribe(filter, { e -> protect(w) { receive(e) } }, { protect(w, ready) })
            if (watches[c.p to b.p] === w) w.stops += stop else stop()
        }
        subscribe(Filter(kinds = listOf(10640), authors = listOf(b.p), limit = 8), { e ->
            if (w.candidate?.id == e.id) return@subscribe
            if (!signed(e, 10640, b.p) || e.createdAt > now() + 300) return@subscribe
            val held = event(current(w)?.second?.discovery, "status")?.takeIf { signed(it, 10640, b.p) }
            val previous = listOfNotNull(w.candidate, held).maxByOrNull { it.createdAt }
            if (previous != null && e.createdAt < previous.createdAt) return@subscribe
            if (previous != null && e.createdAt == previous.createdAt && e.id != previous.id) {
                save(w, mapOf("statusConflictAt" to JsonPrimitive(e.createdAt))); check(w); return@subscribe
            }
            w.candidate = e; save(w, mapOf("status" to e.toJson())); check(w)
        }, { w.statusReady = true; check(w) })
        subscribe(Filter(ids = listOf(b.claim), kinds = listOf(30640), limit = 1), { e ->
            val read = BoxStatuses.readClaim(e, now()) as? BoxClaimResult.Ok ?: return@subscribe
            if (e.id != b.claim || read.claim.node != b.p || w.seed != null) return@subscribe
            w.seed = e
            val old = event(b.discovery, "claim")
            val previous = old?.let { BoxStatuses.readClaim(it, now()) as? BoxClaimResult.Ok }
            if (previous?.claim?.master == read.claim.master && previous.claim.node == b.p) w.latest = old
            claim(w, e)
            subscribe(Filter(kinds = listOf(30640), authors = listOf(read.claim.master), tags = mapOf("#d" to listOf(b.p)), limit = 16), { claim(w, it) }, { w.claimReady = true; check(w) })
        }, { if (w.seed == null) { w.message = "The claim on this contact card was not found."; changed() } })
    }
    private fun claim(w: Watch, e: NostrEvent) {
        val seed = w.seed ?: return
        if (w.latest?.id == e.id) return
        val read = BoxStatuses.readClaim(e, now()) as? BoxClaimResult.Ok ?: return
        if (read.claim.node != w.box || read.claim.master != seed.pubkey) return
        val previous = w.latest?.let { BoxStatuses.readClaim(it, now()) as? BoxClaimResult.Ok }
        if (read.claim.retired) { w.latest = e; save(w, mapOf("claim" to e.toJson())); check(w); return }
        if (previous != null) {
            if (previous.claim.retired) { check(w); return }
            if (e.createdAt < previous.claim.createdAt) return
            if (e.createdAt == previous.claim.createdAt && e.id != previous.claim.id) {
                save(w, mapOf("claimConflictAt" to JsonPrimitive(e.createdAt))); check(w); return
            }
        }
        w.latest = e; save(w, mapOf("claim" to e.toJson())); check(w)
    }
    private fun check(w: Watch) {
        w.proof = null
        val (_, b) = current(w) ?: return
        val latest = w.latest?.let { BoxStatuses.readClaim(it, now()) as? BoxClaimResult.Ok }
        val claimConflict = number(b.discovery, "claimConflictAt")
        val statusConflict = number(b.discovery, "statusConflictAt")
        w.message = when {
            latest?.claim?.retired == true -> "This box’s keeper retired its claim."
            w.latest != null && w.latest!!.id != b.claim -> "The box’s claim changed. Ask for a current contact card."
            claimConflict != null && (w.latest?.createdAt ?: 0) <= claimConflict -> "Conflicting keeper claims; waiting for a newer statement."
            statusConflict != null && (w.candidate?.createdAt ?: 0) <= statusConflict -> "Conflicting box status; waiting for a newer statement."
            !w.claimReady || !w.statusReady -> "Checking the box and its keeper’s claim…"
            w.candidate == null || w.seed == null -> "No current signed box status was found."
            else -> {
                val serial = maxOf(b.highestSerial, number(b.discovery, "serial") ?: 0)
                val held = event(b.discovery, "status")?.takeIf { signed(it, 10640, b.p) }
                val pin = BoxPin(b.p, b.claim, b.nodeId, serial, if (serial == number(b.discovery, "serial")) str(b.discovery, "card") else b.card, held?.createdAt, held?.id)
                when (val r = BoxStatuses.read(w.candidate!!, w.seed!!, pin, now())) {
                    is BoxStatusResult.Refused -> "Box status is not trusted: ${r.reason}."
                    is BoxStatusResult.Ok -> if (save(w, mapOf("card" to JsonPrimitive(r.status.card), "serial" to JsonPrimitive(r.status.link.serial)))) {
                        w.proof = r.status
                        r.status.dropsUrl?.let { "Verified message endpoint: $it" } ?: if (r.status.drops) "The box has drops on, but advertises no message endpoint." else "The box’s drop tier is off."
                    } else "The contact changed while this box was being checked."
                }
            }
        }
        changed()
    }
    @Synchronized fun circleRelays(): Set<String> = runCatching { watches.values.mapNotNull { w ->
        val proof = w.proof
        if (current(w) != null && w.reader.trustedHistory() && proof?.drops == true && proof.validUntil > now()) proof.dropsUrl?.let { canonicalRelayUrl(it) } else null
    }.toSet() }.getOrElse { emptySet() }
    @Synchronized fun tick() {
        val count = watches.size; reconcile()
        if (count != watches.size) changed()
        for (w in watches.values.toList()) if (w.proof?.validUntil?.let { it <= now() } == true) check(w)
    }
    @Synchronized fun restart() { for (w in watches.values.toList()) stop(w); reconcile(); changed() }
    private fun stop(w: Watch) { watches.remove(w.contact to w.box); w.proof = null; w.stops.forEach { it() }; w.reader.close() }
    @Synchronized fun close() { closed = true; for (w in watches.values.toList()) stop(w) }
}
