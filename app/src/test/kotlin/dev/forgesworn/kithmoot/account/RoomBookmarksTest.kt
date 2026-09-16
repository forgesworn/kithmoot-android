package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RoomBookmarksTest {
    private val signer = LocalSigner(ByteArray(32) { 1 })
    private val now = 1_789_000_000_000L
    private val roomId = "a".repeat(64)
    private val link = encodeInvitationUrl("https://kithmoot.example/j/", RoomInvitation(ByteArray(32) { 2 }, signer.pubkey, true), listOf("wss://relay.example"))
    private val room = AccountRoom(roomId, link, "Private conversation", now / 1000)
    private class Store : ProjectStorage {
        var raw: String? = null; var fail = false; var held = false
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun load() = raw
        override suspend fun save(value: String) { check(held && !fail); raw = value }
        override suspend fun release() { held = false }
    }
    private class Network : RoomTransport {
        val events = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val history = mutableListOf<NostrEvent>(); val sent = mutableListOf<NostrEvent>()
        var ack = true; var failQuery = false; var beforeSend: () -> Unit = {}
        override fun publish(event: NostrEvent) = error("Bookmarks require confirmed publication")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> = events
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            check(!failQuery); return history.toList()
        }
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            beforeSend(); sent.add(event)
            if (ack) { history.add(event); events.emit(event) }
            return ack
        }
    }
    private fun TestScope.log(store: Store, net: Network, actor: ParticipantSigner = signer) =
        RoomBookmarks(actor, net, store, backgroundScope, { now })

    @Test fun readsActualBrowserWriterAndExportsAndroidChangesForBrowserVerification() = runTest {
        val fixture = javaClass.getResourceAsStream("/room-bookmarks-web.json")!!.bufferedReader().use {
            Json.parseToJsonElement(it.readText()).jsonObject
        }
        val net = Network(); net.history.add(NostrEvent.fromJson(fixture.getValue("event")))
        val log = log(Store(), net); log.open()
        assertEquals("Browser private chat", log.state.value.rooms.single().name)
        val current = log.state.value.rooms.single()
        log.save(current.copy(name = "Android private chat")); log.remove(current.roomId); runCurrent()
        val output = java.io.File("build/interop/room-bookmarks-android.json"); output.parentFile!!.mkdirs()
        output.writeText(buildJsonObject {
            put("roomId", current.roomId); put("saved", net.sent[0].toJson()); put("removed", net.sent[1].toJson())
        }.toString())
        log.close()
    }

    @Test fun restoresConversationsOnFreshDeviceAndPublishesOnlyEncryptedBookmarks() = runTest {
        val net = Network(); val first = log(Store(), net); first.open(); first.save(room); runCurrent()
        val event = net.sent.single()
        assertEquals(30078, event.kind); assertFalse(event.content.contains(room.label))
        assertFalse(event.tags.flatten().contains(roomId)); assertTrue(Events.verify(event))
        val plain = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, event.content)).jsonObject
        assertEquals(setOf("roomId", "at", "room"), plain.keys)
        assertEquals(setOf("roomId", "link", "name", "openedAt", "readAt"), plain.getValue("room").jsonObject.keys)
        val second = log(Store(), net); second.open(); assertEquals(listOf(room), second.state.value.rooms)
        val foreign = log(Store(), net, LocalSigner(ByteArray(32) { 3 })); foreign.open()
        assertTrue(foreign.state.value.rooms.isEmpty())
        first.close(); second.close(); foreign.close()
    }

    @Test fun cachesBeforeSendingAndRetriesExactEventAfterRestart() = runTest {
        val net = Network(); net.ack = false; val store = Store(); var log = log(store, net); log.open()
        net.beforeSend = { assertNotNull(store.raw) }
        log.save(room); val first = net.sent.single(); assertEquals(1, log.state.value.pending)
        log.close(); log = log(store, net); log.open()
        assertEquals(1, net.sent.size); assertEquals(listOf(room), log.state.value.rooms)
        net.ack = true; log.retry(); runCurrent()
        assertEquals(first, net.sent.last()); assertEquals(0, log.state.value.pending); log.close()
    }

    @Test fun staleHistoryCannotResurrectRemovedRoomAndSameSecondEditsAdvance() = runTest {
        val net = Network(); val log = log(Store(), net); log.open(); log.save(room)
        log.remove(roomId); runCurrent(); assertTrue(log.state.value.rooms.isEmpty())
        assertTrue(net.sent[1].createdAt > net.sent[0].createdAt)
        assertEquals(net.sent[0].tags, net.sent[1].tags)
        net.history.reverse()
        val second = log(Store(), net); second.open(); assertTrue(second.state.value.rooms.isEmpty())
        log.close(); second.close()
    }

    @Test fun failedCacheCannotPublishOrReplaceSavedData() = runTest {
        val net = Network(); val store = Store(); val log = log(store, net); log.open(); log.save(room)
        val saved = store.raw; store.fail = true
        assertFails { log.remove(roomId) }
        assertEquals(saved, store.raw); assertEquals(1, net.sent.size)
        assertEquals(listOf(room), log.state.value.rooms); assertFalse(log.state.value.ready); log.close()
    }

    @Test fun failedLookupRetainsCachedRoomsAndCanBeRetried() = runTest {
        val net = Network(); val store = Store(); var log = log(store, net); log.open(); log.save(room); log.close()
        net.failQuery = true; log = log(store, net); log.open()
        assertEquals(listOf(room), log.state.value.rooms); assertFalse(log.state.value.ready); assertNotNull(log.state.value.error)
        net.failQuery = false; log.refresh(); assertTrue(log.state.value.ready); assertNull(log.state.value.error); log.close()
    }

    @Test fun rejectsPairingPayloadsAndWrongLegacyRoomIds() = runTest {
        val net = Network(); val log = log(Store(), net); log.open()
        val payload = Json.parseToJsonElement(String(java.util.Base64.getUrlDecoder().decode(link.substringAfter('#')))).jsonObject
        for (key in listOf("c", "k")) {
            val paired = JsonObject(payload + (key to JsonPrimitive("pairing secret")))
            val url = "https://kithmoot.example/j/#" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(paired.toString().toByteArray())
            assertFails { log.save(room.copy(link = url)) }
        }
        val legacy = encodeJoinUrl("https://kithmoot.example/j/", ByteArray(32) { 8 }, emptyList())
        assertFails { log.save(room.copy(link = legacy)) }; assertTrue(net.sent.isEmpty()); log.close()
    }

    @Test fun cancelledSigningCannotPublishAfterClose() = runTest {
        val block = CompletableDeferred<Unit>()
        val waiting = object : ParticipantSigner by signer {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                block.await(); return signer.sign(kind, createdAt, tags, content)
            }
        }
        val net = Network(); val log = log(Store(), net, waiting); log.open()
        val job = launch { assertFails { log.save(room) } }; runCurrent()
        val closing = launch { log.close() }; runCurrent(); block.complete(Unit); job.join(); closing.join()
        assertTrue(net.sent.isEmpty())
    }
}
