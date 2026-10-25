package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.media.SpryPairing
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SpryAccountSyncTest {
    private val signer = LocalSigner(ByteArray(32) { 1 })
    private val now = 1789000000L
    private val pair get() = SpryPairing("https://relay.example:3744", "a".repeat(43), "b".repeat(64), signer.pubkey)
    private class Store : ProjectStorage {
        var raw: String? = null; var held = false
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun load() = raw
        override suspend fun save(value: String) { check(held); raw = value }
        override suspend fun release() { held = false }
    }
    private class Network : RoomTransport {
        val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val sent = mutableListOf<NostrEvent>(); val history = mutableListOf<NostrEvent>()
        var ack = true
        override fun publish(event: NostrEvent) = error("Account sync requires confirmed publication")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events
        override suspend fun queryAvailable(filters: List<Filter>, timeoutMs: Long) = history.toList()
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            if (ack) { sent.add(event); history.removeAll { it.id == event.id }; history.add(event); events.emit(event) }
            return ack
        }
    }
    private fun TestScope.sync(store: Store, net: Network, actor: ParticipantSigner = signer,
        adopted: MutableList<SpryPairing?> = mutableListOf(), current: () -> Boolean = { true },
        beforePublish: suspend () -> Unit = {}) = SpryAccountSync(actor, net, store, backgroundScope,
            current, { adopted.add(it) }, {}, beforePublish, { now + 60 })

    @Test fun readsBrowserRecordAndExportsAndroidPairingAndRemoval(): Unit = runTest {
        val fixture = javaClass.getResourceAsStream("/spry-account-web.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val net = Network(); net.history.add(NostrEvent.fromJson(fixture.getValue("event")))
        val adopted = mutableListOf<SpryPairing?>(); val log = sync(Store(), net, adopted = adopted)
        log.open(); runCurrent()
        assertEquals(pair, adopted.single())
        net.sent.clear()
        assertTrue(log.save(pair)); assertTrue(log.save(null)); runCurrent()
        val written = net.sent.distinctBy { it.id }
        assertEquals(2, written.size)
        val output = java.io.File("build/interop/spry-account-android.json"); output.parentFile!!.mkdirs()
        output.writeText(buildJsonObject { put("paired", written[0].toJson()); put("removed", written[1].toJson()) }.toString())
        log.close()
    }
    @Test fun selfEncryptionDoesNotShareWithAnotherAccount(): Unit = runTest {
        val a = sync(Store(), Network()); a.open()
        val net = Network(); val writer = sync(Store(), net); writer.open(); assertTrue(writer.save(pair))
        val adopted = mutableListOf<SpryPairing?>()
        val stranger = sync(Store(), Network(), LocalSigner(ByteArray(32) { 2 }), adopted)
        stranger.open(); stranger.receive(net.sent.last())
        assertTrue(adopted.isEmpty()); assertFalse(net.sent.last().content.contains(pair.secretKey))
        a.close(); writer.close(); stranger.close()
    }
    @Test fun removalCannotBeResurrectedByAnOlderRelayCopy(): Unit = runTest {
        val net = Network(); val writer = sync(Store(), net); writer.open(); writer.save(pair); val old = net.sent.last()
        writer.save(null); val removed = net.sent.last()
        val adopted = mutableListOf<SpryPairing?>(); val reader = sync(Store(), Network(), adopted = adopted); reader.open()
        reader.receive(old); reader.receive(removed); reader.receive(old)
        assertEquals(listOf(pair, null), adopted)
        writer.close(); reader.close()
    }
    @Test fun refusedPublishKeepsEncryptedOutboxForRestart(): Unit = runTest {
        val store = Store(); val net = Network(); net.ack = false
        val first = sync(store, net); first.open(); assertFalse(first.save(pair))
        assertFalse(store.raw!!.contains(pair.secretKey)); first.close()
        net.ack = true
        val restarted = sync(store, net); restarted.open()
        assertTrue(net.sent.isNotEmpty()); assertFalse(store.raw!!.contains("\"pending\""))
        restarted.close()
    }
    @Test fun changedAccountBeforeDispatchDoesNotPublish(): Unit = runTest {
        var current = true
        val net = Network(); val log = sync(Store(), net, current = { current }, beforePublish = { current = false })
        log.open(); assertFalse(log.save(pair)); assertTrue(net.sent.isEmpty()); log.close()
    }
    @Test fun removalIntentSurvivesSignerRefusalAndRestart(): Unit = runTest {
        var refuse = false
        val actor = object : ParticipantSigner by signer {
            override suspend fun nip44Encrypt(peer: String, plaintext: String): String {
                check(!refuse); return signer.nip44Encrypt(peer, plaintext)
            }
        }
        val store = Store(); val first = sync(store, Network(), actor)
        first.open(); assertTrue(first.save(pair))
        refuse = true; assertFalse(first.save(null)); first.close()
        val adopted = mutableListOf<SpryPairing?>(); val net = Network()
        val restarted = sync(store, net, adopted = adopted); restarted.open()
        assertEquals(listOf<SpryPairing?>(null), adopted)
        assertFalse(store.raw!!.contains("removalAt")); assertEquals(1, net.sent.size)
        restarted.close()
    }
    @Test fun forgedRecordDoesNotReachTheSignerOrPairingStore(): Unit = runTest {
        val fixture = javaClass.getResourceAsStream("/spry-account-web.json")!!.bufferedReader().use { Json.parseToJsonElement(it.readText()).jsonObject }
        val event = NostrEvent.fromJson(fixture.getValue("event"))
        val adopted = mutableListOf<SpryPairing?>(); val log = sync(Store(), Network(), adopted = adopted); log.open()
        log.receive(event.copy(sig = "0".repeat(128))); log.receive(event.copy(content = "x".repeat(16001)))
        assertTrue(adopted.isEmpty()); log.close()
    }
}
