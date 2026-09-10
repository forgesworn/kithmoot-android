package dev.forgesworn.kithmoot.discovery

import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.Filter
import dev.forgesworn.kithmoot.storage.ContactBook
import dev.forgesworn.kithmoot.storage.MemoryStorage
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

internal class BoxFixture {
    private val root = Json.parseToJsonElement(File(requireNotNull(System.getProperty("kithmoot.boxVectors"))).readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject
    val now = root.getValue("now").jsonPrimitive.long
    val claim = NostrEvent.fromJson(root.getValue("claim"))
    val status = NostrEvent.fromJson(root.getValue("status"))
    val master = claim.pubkey
    val box = status.pubkey
    val disk = MemoryStorage()
    val contacts = ContactBook(disk)
    var clock = now
    val readers = mutableListOf<Reader>()
    val outages = mutableListOf<() -> Unit>()
    val card: String
    init {
        val cardBytes = Base64.getDecoder().decode(status.tagValue("card"))
        val content = ContactCardBuilder.content(Schnorr.publicKeyHex(ByteArray(32) { 5 }), Schnorr.publicKeyHex(ByteArray(32) { 6 }), emptyList(), boxes = listOf(CardBox(box, claim.id, Base64.getUrlEncoder().withoutPadding().encodeToString(cardBytes), null)), name = "Rowan")
        card = ContactCardBuilder.link("https://kithmoot.test/j/", Events.sign(ByteArray(32) { 2 }, ContactCards.KIND, now - 60, ContactCardBuilder.tags(now + 86400), content))
        check(contacts.add(card, now) is ContactBook.Added.Ok)
    }
    fun discovery() = BoxDiscovery(contacts, { outage -> outages += outage; Reader().also { readers += it } }, {}, { clock })
    fun status(value: String, at: Long = now + 1, endpoint: String? = null): NostrEvent = Events.sign(ByteArray(32) { 1 }, status.kind, at, status.tags.map { if (it[0] == "drops") listOfNotNull("drops", value, endpoint) else it }, "")
    fun retired(at: Long = now + 1): NostrEvent = Events.sign(ByteArray(32) { 2 }, claim.kind, at, claim.tags.filter { !(it[0] == "p" && it[3] == "stash") }.map { if (it[0] == "status") listOf("status", "retired") else it }, "")
    fun verify(r: Reader) { r.send(claim); r.send(status); r.eose() }
}
internal class Reader : BoxReader {
    data class Sub(val filter: Filter, val receive: (NostrEvent) -> Unit, val ready: () -> Unit, var stopped: Boolean = false)
    val subs = mutableListOf<Sub>()
    var closed = false
    override fun subscribe(filter: Filter, receive: (NostrEvent) -> Unit, ready: () -> Unit): () -> Unit {
        val s = Sub(filter, receive, ready); subs += s; return { s.stopped = true }
    }
    fun send(e: NostrEvent) { for (s in subs.toList()) if (!s.stopped && s.filter.let { f -> (f.ids == null || e.id in f.ids) && (f.authors == null || e.pubkey in f.authors) && (f.kinds == null || e.kind in f.kinds) && f.tags.all { (k,v) -> e.tags.any { it.size >= 2 && it[0] == k.removePrefix("#") && it[1] in v } } }) s.receive(e) }
    fun eose() { subs.toList().filterNot { it.stopped }.forEach { it.ready() } }
    override fun close() { closed = true; subs.forEach { it.stopped = true } }
}

class BoxDiscoveryTest {
    @Test fun consentAndCompleteHistoryAreRequired() {
        val f = BoxFixture(); val d = f.discovery(); d.reconcile(); assertTrue(f.readers.isEmpty())
        d.setEnabled(f.master, f.box, true); val r = f.readers.last()
        r.send(f.claim); r.send(f.status); assertTrue(d.circleRelays().isEmpty())
        r.eose(); assertEquals(setOf("wss://owned.example/drops"), d.circleRelays())
        assertFalse(d.circleRelays().contains("wss://transport.example")); d.close()
    }
    @Test fun retirementIsTerminalAcrossRestartAndOutOfOrderHistory() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); val r = f.readers.last(); f.verify(r)
        r.send(Events.sign(ByteArray(32) { 2 }, f.claim.kind, f.now + 20, f.claim.tags, ""))
        r.send(f.retired(f.now + 10)); assertTrue(d.circleRelays().isEmpty()); assertTrue(d.message(f.master, f.box).contains("retired")); d.close()
        val again = f.discovery(); again.reconcile(); f.verify(f.readers.last()); assertTrue(again.circleRelays().isEmpty()); assertTrue(again.message(f.master, f.box).contains("retired")); again.close()
    }
    @Test fun offMalformedNewerAndConflictingStatusesCannotReplayAnOldGrant() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); val r = f.readers.last(); f.verify(r)
        r.send(f.status("off")); assertTrue(d.circleRelays().isEmpty())
        r.send(f.status("on", f.now + 2, "wss://owned.example/drops")); assertEquals(1, d.circleRelays().size)
        r.send(f.status("on", f.now + 3, "ws://wrong.example")); assertTrue(d.circleRelays().isEmpty()); d.close()
        val again = f.discovery(); again.reconcile(); f.verify(f.readers.last()); assertTrue(again.circleRelays().isEmpty())
        f.readers.last().send(f.status("on", f.now + 4, "wss://owned.example/drops")); assertEquals(1, again.circleRelays().size)
        f.readers.last().send(f.status("off", f.now + 4)); assertTrue(again.circleRelays().isEmpty()); again.close()
        val third = f.discovery(); third.reconcile(); f.verify(f.readers.last()); assertTrue(third.circleRelays().isEmpty()); third.close()
    }
    @Test fun expiryIsCheckedWhenTheLaneIsUsedEvenWithoutTimerTicks() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); f.verify(f.readers.last())
        f.clock += BoxStatuses.MAX_AGE_SECONDS; assertTrue(d.circleRelays().isEmpty()); d.close()
    }
    @Test fun disconnectAndRestartNeedFreshStatusHistory() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); val r = f.readers.last(); f.verify(r)
        f.outages.last()(); assertTrue(d.circleRelays().isEmpty()); r.eose(); assertTrue(d.circleRelays().isEmpty())
        r.send(f.status); r.eose(); assertEquals(1, d.circleRelays().size); d.close()
        val again = f.discovery(); again.reconcile(); assertTrue(again.circleRelays().isEmpty()); f.verify(f.readers.last()); assertEquals(1, again.circleRelays().size); again.close()
    }
    @Test fun forgettingAndReplacingCardsRejectLateResultsAndOldConsent() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); val r = f.readers.last(); r.send(f.claim)
        val old = f.contacts.get(f.master)!!; val revision = ContactBook.discoveryRevision(old, old.boxes.single())
        f.contacts.add(f.card, f.now + 1); r.send(f.status); r.eose(); assertTrue(d.circleRelays().isEmpty()); d.reconcile(); assertTrue(r.closed)
        assertFalse(d.enabled(f.master, f.box))
        assertThrows(IllegalArgumentException::class.java) { d.setEnabled(f.master, f.box, true, revision) }
        d.setEnabled(f.master, f.box, true); val next = f.readers.last(); f.verify(next)
        f.contacts.forget(f.master); next.subs.forEach { it.receive(f.status) }; d.reconcile()
        assertNull(f.contacts.get(f.master)); assertTrue(next.closed); assertTrue(d.circleRelays().isEmpty()); d.close()
    }
    @Test fun failedPersistenceCannotGrantTrustAndStoppingRemovesIt() {
        val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true); val r = f.readers.last(); r.send(f.claim)
        f.disk.failWrites = true; r.send(f.status); r.eose(); assertTrue(d.circleRelays().isEmpty())
        f.disk.failWrites = false; r.send(f.status); r.eose(); assertEquals(1, d.circleRelays().size)
        d.disableAll(); assertTrue(r.closed); assertFalse(d.enabled(f.master, f.box)); assertTrue(d.circleRelays().isEmpty()); d.close()
    }
    @Test fun stopClosesReadsEvenWhenSavingThePreferenceFails() {
        for (all in listOf(false, true)) {
            val f = BoxFixture(); val d = f.discovery(); d.setEnabled(f.master, f.box, true)
            val r = f.readers.last(); f.verify(r); assertEquals(1, d.circleRelays().size)
            f.disk.failWrites = true
            assertThrows(dev.forgesworn.kithmoot.storage.RoomStorageException::class.java) {
                if (all) d.disableAll() else d.setEnabled(f.master, f.box, false)
            }
            assertTrue(r.closed); assertTrue(d.circleRelays().isEmpty()); assertFalse(d.enabled(f.master, f.box))
            f.disk.failWrites = false; d.tick(); d.restart()
            assertEquals(1, f.readers.size)
            d.setEnabled(f.master, f.box, true); assertEquals(2, f.readers.size); d.close()
        }
    }
}
