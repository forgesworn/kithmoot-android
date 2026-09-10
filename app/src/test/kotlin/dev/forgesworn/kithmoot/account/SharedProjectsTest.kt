package dev.forgesworn.kithmoot.account

import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.storage.RoomCipher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Test
import java.io.IOException
import javax.crypto.KeyGenerator
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProjectsTest {
    private val owner = LocalSigner(ByteArray(32) { 1 })
    private val member = LocalSigner(ByteArray(32) { 2 })
    private val stranger = LocalSigner(ByteArray(32) { 3 })
    private val now = 1_789_000_000L
    private class Store : ProjectStorage {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        private val cipher = RoomCipher { key }
        var bytes: ByteArray? = null; var fail = false; var held = false; var writes = 0
        override suspend fun acquire() { check(!held); held = true }
        override suspend fun load(): String? = bytes?.let { cipher.decrypt(it).toString(Charsets.UTF_8) }
        override suspend fun save(value: String) { check(held); if (fail) throw IOException("synthetic storage failure"); bytes = cipher.encrypt(value.toByteArray()); writes++ }
        override suspend fun release() { held = false }
    }
    private class Network : RoomTransport {
        val incoming = MutableSharedFlow<NostrEvent>(extraBufferCapacity = 64)
        val history = mutableListOf<NostrEvent>(); val sent = mutableListOf<NostrEvent>(); val filters = mutableListOf<List<Filter>>()
        var fail = false; var failQuery = false; var beforeSend: suspend () -> Unit = {}
        override fun publish(event: NostrEvent) = error("Project publication requires an acknowledgement")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> { this.filters.add(filters); return incoming }
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            if (failQuery) throw IOException("synthetic missing EOSE"); return history.toList()
        }
        override suspend fun publishConfirmed(event: NostrEvent, timeoutMs: Long): Boolean {
            beforeSend(); sent.add(event)
            if (fail) throw IOException("synthetic missing relay acknowledgement")
            history.add(event); incoming.emit(event); return true
        }
    }
    private fun definition(name: String = "Private Kithmoot", members: List<ParticipantSigner> = listOf(owner, member)) = buildJsonObject {
        put("name", name); put("archived", false); put("authorityRevision", 1); put("rooms", JsonArray(emptyList()))
        put("members", JsonArray(members.map { buildJsonObject { put("pubkey", it.pubkey); put("kind", "person"); put("epoch", 1) } }))
    }
    private fun TestScope.open(store: Store, net: Network, signer: ParticipantSigner = owner) = SharedProjects(signer, net, store, backgroundScope, { now })

    @Test fun persistencePrecedesPublicationAndRestartRetriesExactlyTheSameEncryptedEnvelopes() = runTest {
        val store = Store(); val net = Network(); net.fail = true
        var log = open(store, net); log.open()
        net.beforeSend = { assertNotNull(store.bytes); assertEquals(2, Json.parseToJsonElement(store.load()!!).jsonObject.getValue("pending").jsonArray.size) }
        val request = "create-private-project-01"
        val head = log.create(definition(), request); runCurrent()
        val first = net.sent.toList(); assertEquals(2, first.size); assertEquals(2, log.state.value.pendingSends)
        assertFalse(store.bytes!!.toString(Charsets.UTF_8).contains("Private Kithmoot"))
        log.close(); assertFalse(store.held)
        net.beforeSend = {}; log = open(store, net); log.open(); runCurrent()
        assertEquals(first, net.sent); assertEquals(2, log.state.value.pendingSends)
        net.fail = false; log.retry(); runCurrent()
        assertEquals(first, net.sent.takeLast(2)); assertEquals(0, log.state.value.pendingSends)
        assertEquals(head, log.create(definition(), request)); runCurrent(); assertEquals(4, net.sent.size)
        assertFailsWith<IllegalStateException> { log.create(definition("Different intent"), request) }
        log.close()
    }

    @Test fun failedStorageCannotPublishOrEraseTheLastDurableState() = runTest {
        val store = Store(); val net = Network(); val log = open(store, net); log.open()
        store.fail = true
        assertFailsWith<IOException> { log.create(definition(), "create-storage-failure") }; runCurrent()
        assertTrue(net.sent.isEmpty()); assertFalse(log.state.value.ready); assertNull(store.bytes)
        assertFailsWith<IllegalStateException> { log.create(definition(), "create-after-failure") }
        log.close()
    }

    @Test fun ownerAndFreshPhoneShareProjectsAndMetadataRenamesPreserveAuthority() = runTest {
        val net = Network(); val desktop = open(Store(), net); desktop.open()
        desktop.create(definition(), "create-shared-project-01"); runCurrent()
        val phone = open(Store(), net); phone.open()
        val initial = phone.state.value.projects.single(); assertEquals(desktop.state.value.projects.single(), initial)
        phone.update(initial.reference, initial.heads, definition("Renamed project"), "rename-shared-project-01"); runCurrent()
        assertEquals("Renamed project", desktop.state.value.projects.single().name)
        assertEquals(initial.authority, phone.state.value.projects.single().authority)
        assertFailsWith<IllegalStateException> { desktop.update(initial.reference, initial.heads, definition(), "stale-owner-update-01") }
        phone.close(); desktop.close()
    }

    @Test fun memberExplicitlyJoinsAndWithdrawalPlusReadditionRequiresANewJoin() = runTest {
        val net = Network(); val desktop = open(Store(), net); desktop.open()
        desktop.create(definition(), "create-join-project-01"); runCurrent()
        var store = Store(); var phone = open(store, net, member); phone.open()
        val original = phone.state.value.projects.single(); assertFalse(original.joined)
        phone.follow(original.reference, true, original.heads, "join-shared-project-01"); runCurrent()
        assertTrue(phone.state.value.projects.single().joined)
        phone.close(); phone = open(store, net, member); phone.open(); assertTrue(phone.state.value.projects.single().joined)
        val selected = desktop.state.value.projects.single()
        desktop.update(selected.reference, selected.heads, definition(members = listOf(owner)), "remove-project-member-01"); runCurrent()
        assertTrue(phone.state.value.projects.single().withdrawn); assertFalse(phone.state.value.projects.single().joined)
        val removed = desktop.state.value.projects.single()
        desktop.update(removed.reference, removed.heads, definition(), "restore-project-member-01"); runCurrent()
        val readded = phone.state.value.projects.single(); assertFalse(readded.joined); assertNotEquals(original.authority, readded.authority)
        assertFailsWith<IllegalStateException> { phone.follow(original.reference, true, original.heads, "join-stale-invitation-01") }
        phone.follow(readded.reference, true, readded.heads, "join-new-invitation-01"); runCurrent(); assertTrue(phone.state.value.projects.single().joined)
        phone.close(); desktop.close()
    }

    @Test fun overlappingAndDisjointProjectsStayScopedAndNoRoomIsJoinedByTheDirectory() = runTest {
        val net = Network(); val desktop = open(Store(), net); desktop.open()
        desktop.create(definition("Kithmoot"), "create-kithmoot-scope-01")
        desktop.create(definition("Bothy", listOf(owner, stranger)), "create-bothy-scope-01"); runCurrent(); desktop.retry(); runCurrent()
        val phone = open(Store(), net, member); phone.open()
        assertEquals(listOf("Kithmoot"), phone.state.value.projects.map { it.name })
        assertEquals(setOf(1059), net.sent.map { it.kind }.toSet())
        assertTrue(net.filters.all { f -> f.size == 1 && f.single().kinds == listOf(1059) && f.single().tags.keys == setOf("#p", "#l") })
        phone.close(); desktop.close()
    }

    @Test fun cacheCorruptionWrongAccountAndDuplicateWriterFailClosed() = runTest {
        val net = Network(); val store = Store(); val first = open(store, net); first.open()
        first.create(definition(), "create-cached-project-01"); runCurrent()
        val duplicate = open(store, net)
        assertFailsWith<IllegalStateException> { duplicate.open() }; duplicate.close(); assertTrue(store.held)
        first.close()
        val wrong = open(store, net, stranger); assertFailsWith<IllegalStateException> { wrong.open() }; wrong.close()
        store.bytes!![store.bytes!!.lastIndex] = (store.bytes!!.last().toInt() xor 1).toByte()
        val corrupt = open(store, net); assertFails { corrupt.open() }; assertFalse(corrupt.state.value.ready); corrupt.close()
    }

    @Test fun incompleteRelayHistoryKeepsChangesDisabledAndCanBeRetriedWithoutErasingData() = runTest {
        val net = Network(); val store = Store(); val log = open(store, net); net.failQuery = true; log.open()
        assertFalse(log.state.value.ready)
        assertFailsWith<IllegalStateException> { log.create(definition(), "create-before-history") }
        assertTrue(net.sent.isEmpty()); net.failQuery = false; log.refresh(); assertTrue(log.state.value.ready)
        log.create(definition(), "create-after-history-01"); runCurrent(); assertEquals(1, log.state.value.projects.size); log.close()
    }

    @Test fun closingWhileAnExternalSignerIsWaitingPreventsSavingAndPublication() = runTest {
        val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Unit>()
        val signer = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                entered.complete(Unit); answer.await(); return owner.sign(kind, createdAt, tags, content)
            }
        }
        val store = Store(); val net = Network(); val log = open(store, net, signer); log.open()
        val operation = async { runCatching { log.create(definition(), "create-during-signout") } }
        entered.await(); val close = launch { log.close() }; runCurrent(); answer.complete(Unit)
        assertTrue(operation.await().isFailure); close.join(); runCurrent()
        assertTrue(net.sent.isEmpty()); assertNull(store.bytes); assertFalse(store.held)
    }

    @Test fun anEditDuringRelayAcknowledgementIsSentAfterTheInFlightBatch() = runTest {
        val store = Store(); val net = Network(); val log = open(store, net); log.open()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        net.beforeSend = { started.complete(Unit); release.await() }
        log.create(definition(), "create-inflight-project"); started.await()
        val before = log.state.value.projects.single()
        log.update(before.reference, before.heads, definition("Changed while sending"), "update-inflight-project")
        runCurrent(); release.complete(Unit); runCurrent()
        assertEquals(0, log.state.value.pendingSends)
        assertEquals("Changed while sending", log.state.value.projects.single().name)
        assertEquals(4, net.sent.size)
        log.close()
    }

    @Test fun aRemoteSignerTimeoutCannotLeaveAStoppedSubscriptionMarkedReady() = runTest {
        var timeout = true
        val signer = object : ParticipantSigner by owner {
            override suspend fun nip44Decrypt(peer: String, payload: String): String {
                if (timeout) withTimeout(1) { delay(10_000) }
                return owner.nip44Decrypt(peer, payload)
            }
        }
        val net = Network(); val log = open(Store(), net, signer); log.open()
        val inner = Projects.sign(owner.pubkey, buildJsonObject {
            put("v", 1); put("op", "snapshot"); put("project", Projects.id(owner.pubkey, "signer-timeout-project"))
            put("revision", 1); put("parents", JsonArray(emptyList())); put("request", "signer-timeout-project"); put("definition", definition())
        }, now, owner::sign)
        val outer = Projects.wrap(inner, owner.pubkey, now); net.history.add(outer); net.incoming.emit(outer)
        runCurrent(); advanceTimeBy(2); runCurrent()
        assertFalse(log.state.value.ready); assertNotNull(log.state.value.error)
        timeout = false; log.refresh(); assertTrue(log.state.value.ready)
        assertEquals("Private Kithmoot", log.state.value.projects.single().name)
        log.close()
    }
}
