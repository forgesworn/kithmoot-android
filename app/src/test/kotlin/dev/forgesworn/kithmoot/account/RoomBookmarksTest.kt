package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.crypto.Schnorr
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

    @Test fun putsBackRecordsTheRelaysLostAsTheirExactEvents() = runTest {
        val store = Store(); val net = Network()
        val first = log(store, net); first.open()
        first.save(room); first.save(room.copy(roomId = "b".repeat(64), name = "Kept")); runCurrent(); first.close()
        val (lost, kept) = net.sent.toList()
        // The relay still has one room's record and has dropped the other's.
        net.history.clear(); net.history.add(kept); net.sent.clear()
        val second = log(store, net); second.open()
        assertEquals(1, second.state.value.pending)
        second.retry(); runCurrent()
        assertEquals(listOf(lost.id, kept.id), net.sent.map { it.id })
        assertEquals(0, second.state.value.pending)
        second.close()
    }

    @Test fun sendsRecordsStillOnARelayAgainQuietly() = runTest {
        val store = Store(); val net = Network()
        val first = log(store, net); first.open(); first.save(room); runCurrent(); first.close()
        val event = net.sent.single(); net.sent.clear()
        val second = log(store, net); second.open(); second.retry(); runCurrent()
        assertEquals(listOf(event.id), net.sent.map { it.id })
        second.retry(); runCurrent()
        assertEquals(1, net.sent.size)
        second.close()
    }

    @Test fun repairsNothingAfterALookupThatFoundNothing() = runTest {
        val store = Store(); val net = Network()
        val first = log(store, net); first.open(); first.save(room); runCurrent(); first.close()
        net.history.clear(); net.sent.clear()
        val second = log(store, net); second.open(); second.retry(); runCurrent()
        assertTrue(net.sent.isEmpty())
        second.close()
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

    @Test fun aSaveDuringATorOnlyRoomIsKeptOnThePhoneAndSentOnlyAfterTheHold() = runTest {
        val hold = AccountWriteHold(backgroundScope) { 60_000 }; hold.torOnlyRoomOpened()
        val net = Network(); val store = Store()
        val log = RoomBookmarks(signer, net, store, backgroundScope, { now }, hold::awaitReleased); log.open()
        backgroundScope.launch { log.save(room) }; runCurrent()
        assertTrue(net.sent.isEmpty()); assertNotNull(store.raw); assertEquals(listOf(room), log.state.value.rooms)
        hold.torOnlyRoomClosed(); advanceTimeBy(59_999); runCurrent(); assertTrue(net.sent.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(1, net.sent.size); assertNull(log.state.value.error)
        log.close()
    }

    private val groupSecret = ByteArray(32) { 7 }
    private val groupRoom get() = AccountRoom(deriveRoom(groupSecret).roomId, link, "Private chat", now / 1000, groupSecret.joinToString("") { "%02x".format(it) })

    @Test fun carriesAGroupsSecretBesideTheRoomAndRestoresItOnAnotherDevice() = runTest {
        val net = Network(); val first = log(Store(), net); first.open(); first.save(groupRoom); runCurrent()
        val plain = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, net.sent.single().content)).jsonObject
        assertEquals(setOf("roomId", "at", "room", "admission"), plain.keys)
        assertEquals(setOf("roomId", "link", "name", "openedAt", "readAt"), plain.getValue("room").jsonObject.keys)
        assertEquals(setOf("secret"), plain.getValue("admission").jsonObject.keys)
        assertFalse(net.sent.single().content.contains(groupRoom.admission!!))
        val second = log(Store(), net); second.open()
        assertEquals(groupRoom.admission, second.state.value.rooms.single().admission)
        first.close(); second.close()
    }

    @Test fun ignoresASecretThatIsNotTheRoomsOwnButStillListsTheRoom() = runTest {
        val wrong = groupRoom.copy(admission = "b".repeat(64))
        val net = Network(); val first = log(Store(), net); first.open(); first.save(wrong); runCurrent()
        val second = log(Store(), net); second.open()
        val listed = second.state.value.rooms.single()
        assertEquals("Private chat", listed.name); assertNull(listed.admission)
        first.close(); second.close()
    }

    @Test fun aSaveFromADeviceWithoutTheSecretKeepsTheOneTheRecordCarries() = runTest {
        val net = Network(); val first = log(Store(), net); first.open(); first.save(groupRoom); runCurrent()
        val second = log(Store(), net); second.open()
        second.save(second.state.value.rooms.single().copy(name = "Renamed", admission = null)); runCurrent()
        val plain = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, net.sent.last().content)).jsonObject
        assertEquals("Renamed", plain.getValue("room").jsonObject.getValue("name").jsonPrimitive.content)
        assertEquals(groupRoom.admission, plain.getValue("admission").jsonObject.getValue("secret").jsonPrimitive.content)
        first.close(); second.close()
    }

    /** A record as the web writes it with an owner key, from another of the account's devices. */
    private suspend fun webRecordWithOwner(owner: JsonObject, at: Long = now): NostrEvent {
        val value = buildJsonObject {
            put("roomId", groupRoom.roomId); put("at", at)
            put("room", buildJsonObject { put("roomId", groupRoom.roomId); put("link", link); put("name", "Private chat"); put("openedAt", now / 1000); put("readAt", 0) })
            put("owner", owner)
        }.toString()
        val content = signer.nip44Encrypt(signer.pubkey, value)
        val tags = listOf(listOf("d", "kithmoot.rooms.v1.00000000-0000-4000-8000-000000000001"), listOf("l", "kithmoot.rooms.v1"))
        return checkedSignedEvent(signer.sign(RoomBookmarks.KIND, at / 1000, tags, content), signer.pubkey, RoomBookmarks.KIND, at / 1000, tags, content)
    }
    private val ownerKey = "c".repeat(63) + "d"
    private val member = "e".repeat(64)
    private val gone = "f".repeat(64)

    @Test fun keepsTheOwnerKeyAnotherDeviceSharedWhenThePhoneSavesTheRoom() = runTest {
        val owner = buildJsonObject { put("key", ownerKey); put("members", JsonArray(listOf(JsonPrimitive(member), JsonPrimitive(gone)))); put("removed", JsonArray(listOf(JsonPrimitive(gone)))) }
        val net = Network(); net.history.add(webRecordWithOwner(owner))
        val log = log(Store(), net); log.open()
        val listed = log.state.value.rooms.single()
        assertEquals(listOf(member), listed.owner?.members); assertEquals(listOf(gone), listed.owner?.removed)
        assertFalse(listed.owner.toString().contains(ownerKey))
        log.save(listed.copy(name = "Renamed", owner = null)); runCurrent()
        val plain = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, net.sent.last().content)).jsonObject
        assertEquals("Renamed", plain.getValue("room").jsonObject.getValue("name").jsonPrimitive.content)
        val kept = plain.getValue("owner").jsonObject
        assertEquals(ownerKey, kept.getValue("key").jsonPrimitive.content)
        assertEquals(listOf(member), kept.getValue("members").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf(gone), kept.getValue("removed").jsonArray.map { it.jsonPrimitive.content })
        log.close()
    }

    @Test fun dropsAMalformedOwnerKeyButStillListsTheRoom() = runTest {
        val net = Network(); net.history.add(webRecordWithOwner(buildJsonObject { put("key", "not a key") }))
        val log = log(Store(), net); log.open()
        val listed = log.state.value.rooms.single()
        assertEquals("Private chat", listed.name); assertNull(listed.owner)
        log.close()
    }

    @Test fun savesAgainWhenARoomGainsASecretItWasBookmarkedWithout() = runTest {
        val net = Network(); val log = log(Store(), net); log.open()
        log.save(groupRoom.copy(admission = null)); runCurrent(); log.save(groupRoom); runCurrent()
        assertEquals(2, net.sent.size)
        assertEquals(groupRoom.admission, log.state.value.rooms.single().admission); log.close()
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

    /** A room made directly on a node, as the web's account record carries it. */
    private val nodeAuthority = ByteArray(32) { 5 }
    private val nodeService = Schnorr.publicKeyHex(ByteArray(32) { 6 })
    private val nodeSecret = ByteArray(32) { 7 }
    private val nodeRoomId = deriveRoom(nodeSecret).roomId
    private fun nodeLink(room: String = nodeRoomId, endsAt: Long? = null, short: Boolean = false): String {
        val signed = NodeRooms.sign(nodeAuthority, room, "https://node.example", nodeService, now / 1000, endsAt).toJson()
        return if (short) NodeRooms.encodeShortLink("https://kithmoot.example/j/", signed, now / 1000)
        else NodeRooms.encodeLink("https://kithmoot.example/j/", signed, now / 1000)
    }
    private suspend fun webNodeRecord(nodeLink: String, extra: JsonObjectBuilder.() -> Unit = {}): NostrEvent {
        val value = buildJsonObject {
            put("roomId", nodeRoomId); put("at", now)
            put("room", buildJsonObject { put("roomId", nodeRoomId); put("link", nodeLink); put("name", "Node room"); put("openedAt", now / 1000); put("readAt", 0) })
            extra()
        }.toString()
        val content = signer.nip44Encrypt(signer.pubkey, value)
        val tags = listOf(listOf("d", "kithmoot.rooms.v1.00000000-0000-4000-8000-000000000002"), listOf("l", "kithmoot.rooms.v1"))
        return checkedSignedEvent(signer.sign(RoomBookmarks.KIND, now / 1000, tags, content), signer.pubkey, RoomBookmarks.KIND, now / 1000, tags, content)
    }

    @Test fun keepsARoomMadeOnANodeWithTheOwnerKeyItCarries() = runTest {
        val ownerOfNode = nodeAuthority.joinToString("") { "%02x".format(it) }
        val net = Network(); net.history.add(webNodeRecord(nodeLink()) { put("owner", buildJsonObject { put("key", ownerOfNode); put("members", JsonArray(listOf(JsonPrimitive(member)))) }) })
        val log = log(Store(), net); log.open()
        val listed = log.state.value.rooms.single()
        assertEquals(nodeRoomId, listed.roomId); assertEquals("Node room", listed.name)
        assertEquals(listOf(member), listed.owner?.members)
        log.close()
    }

    @Test fun keepsANodeRoomListedAfterItsEndAsTheWebDoes() = runTest {
        // Checked against the time the room was opened, not now: an ended room stays in the list.
        val ended = nodeLink(endsAt = now / 1000 + 3600)
        val net = Network(); net.history.add(webNodeRecord(ended))
        val log = RoomBookmarks(signer, net, Store(), backgroundScope, { now + 7_200_000 }); log.open()
        assertEquals(nodeRoomId, log.state.value.rooms.single().roomId); log.close()
    }

    @Test fun keepsANodeRoomWhoseRecordCarriesAShortLink() = runTest {
        // This app stores long links, but a record written elsewhere may carry the version 7 form.
        val net = Network(); net.history.add(webNodeRecord(nodeLink(short = true)))
        val log = log(Store(), net); log.open()
        assertEquals(nodeRoomId, log.state.value.rooms.single().roomId); log.close()
        RoomBookmarks.validateLink(nodeLink(short = true), nodeRoomId, now / 1000)
        assertFails { RoomBookmarks.validateLink(nodeLink(room = "d".repeat(64), short = true), nodeRoomId, now / 1000) }
    }

    @Test fun treatsAnEndedShortNodeRoomLinkAsItsLongForm() = runTest {
        // A self-destructing room's bookmark is usually an ended one.
        val endsAt = now / 1000 + 3600
        val short = nodeLink(endsAt = endsAt, short = true); val long = nodeLink(endsAt = endsAt)
        val net = Network(); net.history.add(webNodeRecord(short))
        val log = RoomBookmarks(signer, net, Store(), backgroundScope, { now + 7_200_000 }); log.open()
        assertEquals(nodeRoomId, log.state.value.rooms.single().roomId); log.close()
        for (link in listOf(short, long)) {
            RoomBookmarks.validateLink(link, nodeRoomId, now / 1000)
            assertFails { RoomBookmarks.validateLink(link, nodeRoomId, endsAt + 1) }
        }
    }

    @Test fun readsANodeRoomRecordThatCarriesTheRoomSecret() = runTest {
        val secret = nodeSecret.joinToString("") { "%02x".format(it) }
        val net = Network(); net.history.add(webNodeRecord(nodeLink()) { put("admission", buildJsonObject { put("secret", secret) }) })
        val log = log(Store(), net); log.open()
        assertEquals(secret, log.state.value.rooms.single().admission); log.close()
    }

    @Test fun savesANodeRoomButRefusesALinkNamingAnotherRoom() = runTest {
        val net = Network(); val log = log(Store(), net); log.open()
        val node = AccountRoom(nodeRoomId, nodeLink(), "Node room", now / 1000)
        log.save(node); runCurrent()
        assertEquals(1, net.sent.size)
        assertFails { log.save(node.copy(link = nodeLink(room = "d".repeat(64)))) }
        assertEquals(1, net.sent.size); log.close()
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

    @Test fun carriesAnEndSelfDestructAndStartAsTheWebWritesThemAndNoSaveTakesThemBack() = runTest {
        val dated = groupRoom.copy(endsAt = now / 1000 + 86_400, destruct = true, startsAt = now / 1000 - 60)
        val net = Network(); val first = log(Store(), net); first.open(); first.save(dated); runCurrent()
        val plain = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, net.sent.single().content)).jsonObject
        val room = plain.getValue("room").jsonObject
        assertEquals(setOf("roomId", "link", "name", "openedAt", "readAt", "endsAt", "destruct", "startsAt"), room.keys)
        assertEquals(JsonPrimitive(true), room.getValue("destruct"))
        val second = log(Store(), net); second.open()
        val listed = second.state.value.rooms.single()
        assertEquals(dated.endsAt, listed.endsAt); assertTrue(listed.destruct); assertEquals(dated.startsAt, listed.startsAt)
        // A save from a device that knows none of it keeps all three.
        second.save(listed.copy(name = "Renamed", endsAt = null, destruct = false, startsAt = null)); runCurrent()
        val kept = Json.parseToJsonElement(signer.nip44Decrypt(signer.pubkey, net.sent.last().content)).jsonObject.getValue("room").jsonObject
        assertEquals(JsonPrimitive(true), kept.getValue("destruct"))
        assertEquals(JsonPrimitive(dated.endsAt!!), kept.getValue("endsAt"))
        // Learning self-destruct later is a change worth sending.
        val plainLog = log(Store(), Network()); plainLog.open(); plainLog.save(groupRoom); runCurrent()
        val before = plainLog.state.value.rooms.single(); assertFalse(before.destruct)
        plainLog.save(before.copy(destruct = true)); runCurrent()
        assertTrue(plainLog.state.value.rooms.single().destruct)
        first.close(); second.close(); plainLog.close()
    }

    private fun TestScope.told(store: Store, net: Network, into: MutableList<String>) =
        RoomBookmarks(signer, net, store, backgroundScope, { now }, onTombstone = { into += it })

    @Test fun tellsTheAppOfATombstoneOnlyOnceTheLookupHasSettledAndOnlyWhenItIsTheLatestWord() = runTest {
        val net = Network(); val first = log(Store(), net); first.open()
        first.save(groupRoom); runCurrent(); first.remove(groupRoom.roomId); runCurrent()
        val told = mutableListOf<String>()
        val second = told(Store(), net, told); second.open(); runCurrent()
        assertEquals(listOf(groupRoom.roomId), told)
        // Saved again after its tombstone: the tombstone is history, not news.
        first.save(groupRoom); runCurrent()
        val third = mutableListOf<String>()
        val later = told(Store(), net, third); later.open(); runCurrent()
        assertEquals(emptyList(), third)
        first.close(); second.close(); later.close()
    }

    @Test fun tellsTheAppOfATombstoneThatArrivesWhileItIsListening() = runTest {
        val net = Network(); val first = log(Store(), net); first.open(); first.save(groupRoom); runCurrent()
        val told = mutableListOf<String>()
        val second = told(Store(), net, told); second.open(); runCurrent()
        assertEquals(emptyList(), told)
        first.remove(groupRoom.roomId); runCurrent()
        assertEquals(listOf(groupRoom.roomId), told)
        first.close(); second.close()
    }
}
