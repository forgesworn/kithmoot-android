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
import java.io.File
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
        var queryFailure: Exception? = null
        var beforeGuard: suspend () -> Unit = {}
        var generation = 0L
        val guarded = mutableListOf<NostrEvent>()
        override fun publicationGeneration() = generation
        override suspend fun publishConfirmedGuarded(event: NostrEvent, generation: Long,
            stillAllowed: () -> Boolean, timeoutMs: Long): Boolean {
            beforeGuard()
            if (generation != this.generation || !stillAllowed()) return false
            guarded.add(event)
            val confirmed = publishConfirmed(event, timeoutMs)
            return confirmed && generation == this.generation && stillAllowed()
        }
        override fun publish(event: NostrEvent) = error("Project publication requires an acknowledgement")
        override fun subscribe(filters: List<Filter>): Flow<NostrEvent> { this.filters.add(filters); return incoming }
        override suspend fun queryStored(filters: List<Filter>, timeoutMs: Long): List<NostrEvent> {
            queryFailure?.let { throw it }
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
    private fun image(): LogoImage {
        val file = listOf("../protocol/src/test/resources/project-logo-web-v1.json", "protocol/src/test/resources/project-logo-web-v1.json")
            .map(::File).first { it.isFile }
        val event = Json.parseToJsonElement(file.readText()).jsonObject.getValue("cases").jsonArray.first().jsonObject
            .getValue("event").jsonObject
        return requireNotNull(LogoImages.read(Json.parseToJsonElement(event.getValue("content").jsonPrimitive.content).jsonObject["image"]))
    }
    private suspend fun logo(project: SharedProject, request: String, version: Int = 1, image: LogoImage? = image()): NostrEvent {
        val context = ProjectLogoContext(project.reference, project.definition!!)
        return ProjectLogos.sign(owner.pubkey, context, buildJsonObject {
            put("v", 1); put("op", "logo"); put("project", project.reference.project); put("authority", project.authority!!)
            put("version", version); put("request", request); put("image", image?.toJson() ?: JsonNull)
        }, now, owner::sign)
    }

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

    @Test fun aChangeDuringATorOnlyRoomWaitsOutTheHoldWithoutCountingAsAFailedSend() = runTest {
        val hold = AccountWriteHold(backgroundScope) { 60_000 }; hold.torOnlyRoomOpened()
        val store = Store(); val net = Network()
        val log = SharedProjects(owner, net, store, backgroundScope, { now }, hold::awaitReleased); log.open()
        log.create(definition(), "create-during-tor-only-room"); runCurrent()
        assertTrue(net.sent.isEmpty()); assertEquals(2, log.state.value.pendingSends)
        // Longer than the 15 s publish timeout: the wait must not count as a failed send.
        hold.torOnlyRoomClosed(); advanceTimeBy(59_999); runCurrent(); assertTrue(net.sent.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(2, net.sent.size); assertEquals(0, log.state.value.pendingSends); assertNull(log.state.value.error)
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

    @Test fun authenticationFailureExplainsRecoveryAndPreservesExistingProjects() = runTest {
        val net = Network(); val store = Store(); val log = open(store, net); log.open()
        log.create(definition(), "create-before-auth-refusal"); runCurrent()
        net.queryFailure = RelayHistoryException("wss://relay.damus.io", true)
        log.refresh()
        assertFalse(log.state.value.ready)
        assertFalse(log.state.value.syncing)
        assertEquals(1, log.state.value.projects.size)
        assertContains(log.state.value.error!!, "wss://relay.damus.io requires authentication")
        assertContains(log.state.value.error!!, "Retry project sync")
        net.queryFailure = null; log.refresh()
        assertTrue(log.state.value.ready); assertNull(log.state.value.error)
        assertEquals(1, log.state.value.projects.size)
        log.close()
    }

    @Test fun signerRefusalIsReportedAsAnAuthorisationProblemAndCanBeRetried() = runTest {
        val net = Network(); val sender = open(Store(), net); sender.open()
        sender.create(definition(), "create-before-signer-refusal"); runCurrent(); sender.close()
        var refused = true
        val signer = object : ParticipantSigner by owner {
            override suspend fun nip44Decrypt(peer: String, payload: String): String {
                if (refused) throw SignerException("Cambium declined to sign.")
                return owner.nip44Decrypt(peer, payload)
            }
        }
        val store = Store(); val phone = open(store, net, signer); phone.open()
        assertFalse(phone.state.value.ready)
        assertContains(phone.state.value.error!!, "decryption permissions")
        assertNull(store.bytes)
        refused = false; phone.refresh()
        assertTrue(phone.state.value.ready); assertNull(phone.state.value.error)
        assertEquals("Private Kithmoot", phone.state.value.projects.single().name)
        phone.close()
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

    @Test fun logoPersistencePrecedesGuardedPublicationAndRestartReusesExactEnvelopesAndReceipt() = runTest {
        val store = Store(); val net = Network(); var log = open(store, net); log.open()
        log.create(definition(), "logo-durable-project-01"); runCurrent()
        val original = log.state.value.projects.single(); val chosen = image(); net.fail = true
        net.beforeSend = {
            val cache = Json.parseToJsonElement(store.load()!!).jsonObject
            assertEquals(1, cache.getValue("logoEvents").jsonArray.size)
            assertEquals(2, cache.getValue("logoPending").jsonArray.size)
        }
        val request = "logo-durable-change-01"
        val head = log.updateLogo(original.reference, original.heads, original.logoHeads, chosen, request); runCurrent()
        val first = net.guarded.toList(); assertEquals(2, first.size); assertEquals(2, log.state.value.pendingSends)
        assertEquals(chosen.toJson(), log.state.value.projects.single().logo!!.toJson())
        assertFalse(store.bytes!!.toString(Charsets.UTF_8).contains(chosen.data))
        log.close(); net.beforeSend = {}; log = open(store, net); log.open(); runCurrent()
        assertEquals(first, net.guarded); assertEquals(2, log.state.value.pendingSends)
        net.fail = false; log.retry(); runCurrent()
        assertEquals(first, net.guarded.takeLast(2)); assertEquals(0, log.state.value.pendingSends)
        assertEquals(head, log.updateLogo(original.reference, original.heads, original.logoHeads, chosen, request))
        assertFailsWith<IllegalStateException> { log.updateLogo(original.reference, original.heads, original.logoHeads, null, request) }
        assertFailsWith<IllegalStateException> { log.update(original.reference, original.heads, definition(), request) }
        log.close()
    }

    @Test fun legacyJournalLoadsWithoutLosingDirectoryAndNextLogoWriteAddsCompanions() = runTest {
        val store = Store(); val net = Network(); var log = open(store, net); log.open()
        log.create(definition(), "logo-migration-project"); runCurrent(); log.close()
        val existing = Json.parseToJsonElement(store.load()!!).jsonObject
        store.acquire(); store.save(JsonObject(existing.filterKeys { !it.startsWith("logo") }).toString()); store.release()
        log = open(store, net); log.open(); val project = log.state.value.projects.single()
        assertTrue(log.state.value.ready); assertNull(project.logo); assertEquals("Private Kithmoot", project.name)
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-migration-change"); runCurrent()
        val cache = Json.parseToJsonElement(store.load()!!).jsonObject
        assertEquals(existing["events"], cache["events"]); assertEquals(1, cache.getValue("logoEvents").jsonArray.size)
        log.close()
    }

    @Test fun companionBeforeDirectoryWaitsEncryptedAcrossRestartAndCannotCreateMembership() = runTest {
        val net = Network(); val ownerLog = open(Store(), net); ownerLog.open()
        ownerLog.create(definition(), "logo-arrival-project-01"); runCurrent()
        val project = ownerLog.state.value.projects.single(); val companion = logo(project, "logo-arrival-change-01")
        val outer = ProjectLogos.wrap(companion, member.pubkey, ProjectLogoContext(project.reference, project.definition!!), now)
        val directoryEvents = net.history.toList(); net.history.clear(); net.history.add(outer)
        var decrypts = 0
        val signer = object : ParticipantSigner by member {
            override suspend fun nip44Decrypt(peer: String, payload: String): String { decrypts++; return member.nip44Decrypt(peer, payload) }
        }
        val store = Store(); var log = open(store, net, signer); log.open()
        assertTrue(log.state.value.projects.isEmpty()); assertEquals(0, decrypts)
        assertEquals(1, Json.parseToJsonElement(store.load()!!).jsonObject.getValue("logoWaiting").jsonArray.size)
        log.close(); log = open(store, net, signer); log.open(); assertEquals(0, decrypts)
        net.history.addAll(directoryEvents); log.refresh()
        val received = log.state.value.projects.single(); assertFalse(received.joined)
        assertEquals(image().toJson(), received.logo!!.toJson()); assertEquals(listOf(companion.id), received.logoHeads)
        assertEquals(0, Json.parseToJsonElement(store.load()!!).jsonObject.getValue("logoWaiting").jsonArray.size)
        log.close(); ownerLog.close()
    }

    @Test fun membershipRefreshIsAtomicAndRemovedThenReaddedMemberReceivesOnlyFreshAuthority() = runTest {
        val net = Network(); val ownerLog = open(Store(), net); ownerLog.open()
        ownerLog.create(definition(), "logo-membership-project"); runCurrent()
        var project = ownerLog.state.value.projects.single()
        ownerLog.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-membership-change"); runCurrent()
        val phone = open(Store(), net, member); phone.open(); val initial = phone.state.value.projects.single()
        project = ownerLog.state.value.projects.single()
        ownerLog.update(project.reference, project.heads, definition("Renamed"), "logo-membership-rename"); runCurrent()
        assertEquals(initial.logoHeads, phone.state.value.projects.single().logoHeads)
        project = ownerLog.state.value.projects.single()
        ownerLog.update(project.reference, project.heads, definition(members = listOf(owner)), "logo-membership-remove"); runCurrent()
        val removed = phone.state.value.projects.single(); assertTrue(removed.withdrawn); assertNull(removed.logo)
        val afterRemoval = ownerLog.state.value.projects.single(); assertEquals(1, afterRemoval.logoVersion)
        assertNotEquals(initial.logoHeads, afterRemoval.logoHeads)
        ownerLog.update(afterRemoval.reference, afterRemoval.heads, definition(), "logo-membership-readd"); runCurrent()
        val readded = phone.state.value.projects.single()
        assertFalse(readded.joined); assertNotEquals(initial.authority, readded.authority)
        assertNotEquals(initial.logoHeads, readded.logoHeads); assertEquals(image().toJson(), readded.logo!!.toJson())
        assertEquals(ownerLog.state.value.projects.single().logoHeads, readded.logoHeads)
        phone.close(); ownerLog.close()
    }

    @Test fun failedLogoOrMembershipRefreshSavePreservesLastDurableStateAndDoesNotPublish() = runTest {
        val store = Store(); val net = Network(); var log = open(store, net); log.open()
        log.create(definition(), "logo-save-failure-project"); runCurrent()
        var project = log.state.value.projects.single()
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-before-save-failure"); runCurrent()
        project = log.state.value.projects.single(); val bytes = store.bytes!!.copyOf(); val sent = net.sent.toList(); store.fail = true
        assertFailsWith<IOException> { log.update(project.reference, project.heads, definition(members = listOf(owner)), "logo-refresh-save-failure") }
        runCurrent(); assertContentEquals(bytes, store.bytes); assertEquals(sent, net.sent)
        assertEquals(project.authority, log.state.value.projects.single().authority); assertFalse(log.state.value.ready)
        log.close(); store.fail = false; log = open(store, net); log.open()
        project = log.state.value.projects.single(); val durable = store.bytes!!.copyOf()
        store.fail = true; var accepted = false
        assertFailsWith<IOException> { log.updateLogo(project.reference, project.heads, project.logoHeads, null, "logo-removal-save-failure", onAccepted = { accepted = true }) }
        assertFalse(accepted)
        runCurrent(); assertContentEquals(durable, store.bytes); assertEquals(sent, net.sent)
        assertNotNull(log.state.value.projects.single().logo); log.close()
    }

    @Test fun sameVersionForkWithholdsImageAndHigherRemovalResolvesWithReviewedHeads() = runTest {
        val net = Network(); val log = open(Store(), net); log.open()
        log.create(definition(), "logo-fork-project-01"); runCurrent(); val project = log.state.value.projects.single()
        val context = ProjectLogoContext(project.reference, project.definition!!)
        val a = logo(project, "logo-fork-first-change"); val b = logo(project, "logo-fork-other-change", image = null)
        net.incoming.emit(ProjectLogos.wrap(a, owner.pubkey, context, now)); runCurrent()
        net.incoming.emit(ProjectLogos.wrap(b, owner.pubkey, context, now)); runCurrent()
        val fork = log.state.value.projects.single(); assertTrue(fork.logoConflicted); assertNull(fork.logo)
        assertEquals(listOf(a.id, b.id).sorted(), fork.logoHeads)
        assertFailsWith<IllegalStateException> { log.updateLogo(fork.reference, fork.heads, listOf(a.id), null, "logo-fork-stale-removal") }
        log.updateLogo(fork.reference, fork.heads, fork.logoHeads, null, "logo-fork-fresh-removal"); runCurrent()
        val removed = log.state.value.projects.single(); assertFalse(removed.logoConflicted); assertNull(removed.logo)
        assertEquals(2, removed.logoVersion); log.close()
    }

    @Test fun accountCloseWhileGuardWaitsPreventsAnyOfferWithoutTakingAccountMutex() = runTest {
        val net = Network(); val log = open(Store(), net); log.open()
        log.create(definition(), "logo-close-guard-project"); runCurrent(); val project = log.state.value.projects.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        net.beforeGuard = { entered.complete(Unit); release.await() }
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-close-guard-change"); entered.await()
        log.close(); release.complete(Unit); runCurrent(); assertTrue(net.guarded.isEmpty())
        assertFalse(log.state.value.ready)
    }

    @Test fun removalDuringGuardWaitPrunesOldAuthorityBeforeDispatchAndRefreshesCurrentLogo() = runTest {
        val net = Network(); val log = open(Store(), net); log.open()
        log.create(definition(), "logo-dispatch-project-01"); runCurrent(); val initial = log.state.value.projects.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        net.beforeGuard = { entered.complete(Unit); release.await() }
        val oldHead = log.updateLogo(initial.reference, initial.heads, emptyList(), image(), "logo-dispatch-change-01"); entered.await()
        val current = log.state.value.projects.single()
        log.update(current.reference, current.heads, definition(members = listOf(owner)), "logo-dispatch-remove-01")
        net.beforeGuard = {}; release.complete(Unit); runCurrent(); log.retry(); runCurrent()
        val after = log.state.value.projects.single(); assertNotEquals(listOf(oldHead), after.logoHeads)
        assertEquals(1, net.guarded.size)
        val inner = ProjectLogos.unwrap(net.guarded.single(), owner.pubkey, listOf(ProjectLogoContext(after.reference, after.definition!!)), now, owner::nip44Decrypt)
        assertEquals(after.logoHeads.single(), inner?.id); log.close()
    }

    @Test fun generationChangeBeforeDispatchKeepsExactLogoEnvelopesForRetry() = runTest {
        val store = Store(); val net = Network(); val log = open(store, net); log.open()
        log.create(definition(), "logo-generation-project"); runCurrent(); val project = log.state.value.projects.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        net.beforeGuard = { entered.complete(Unit); release.await() }
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-generation-change"); entered.await()
        val before = Json.parseToJsonElement(store.load()!!).jsonObject.getValue("logoPending")
        net.generation++; net.beforeGuard = {}; release.complete(Unit); runCurrent()
        assertTrue(net.guarded.isEmpty()); assertEquals(2, log.state.value.pendingSends)
        assertEquals(before, Json.parseToJsonElement(store.load()!!).jsonObject.getValue("logoPending"))
        log.retry(); runCurrent(); assertEquals(2, net.guarded.size); assertEquals(0, log.state.value.pendingSends)
        log.close()
    }

    @Test fun forkArrivingWhileGuardWaitsWithholdsAllPendingLogoOffers() = runTest {
        val net = Network(); val log = open(Store(), net); log.open()
        log.create(definition(), "logo-fork-guard-project"); runCurrent(); val project = log.state.value.projects.single()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        net.beforeGuard = { entered.complete(Unit); release.await() }
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-fork-guard-first"); entered.await()
        val competing = logo(project, "logo-fork-guard-other", image = null)
        net.incoming.emit(ProjectLogos.wrap(competing, owner.pubkey, ProjectLogoContext(project.reference, project.definition!!), now))
        runCurrent(); assertTrue(log.state.value.projects.single().logoConflicted)
        assertEquals(0, log.state.value.pendingSends)
        release.complete(Unit); runCurrent(); assertTrue(net.guarded.isEmpty())
        assertNull(log.state.value.projects.single().logo); assertNull(log.state.value.error); log.close()
    }

    @Test fun lostGuardAtConfirmationRetainsReceiptAndExactEnvelopeForIdempotentRetry() = runTest {
        val store = Store(); val net = Network(); val log = open(store, net); log.open()
        log.create(definition(), "logo-confirm-project-01"); runCurrent(); val project = log.state.value.projects.single()
        var switched = false
        net.beforeSend = { if (!switched) { switched = true; net.generation++ } }
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-confirm-change-01"); runCurrent()
        assertEquals(2, log.state.value.pendingSends)
        val first = net.guarded.single(); val persisted = Json.parseToJsonElement(store.load()!!).jsonObject
        assertEquals(1, persisted.getValue("logoRequests").jsonObject.size)
        net.beforeSend = {}; log.retry(); runCurrent()
        assertTrue(net.guarded.drop(1).contains(first)); assertEquals(0, log.state.value.pendingSends)
        assertEquals(persisted["logoRequests"], Json.parseToJsonElement(store.load()!!).jsonObject["logoRequests"])
        log.close()
    }

    @Test fun logoSignerCancellationAndSignoutCannotReplaceDurableArtworkOrOfferAnEnvelope() = runTest {
        val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Unit>(); var wait = false
        val signer = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                if (wait) { entered.complete(Unit); answer.await() }
                return owner.sign(kind, createdAt, tags, content)
            }
        }
        val store = Store(); val net = Network(); val log = open(store, net, signer); log.open()
        log.create(definition(), "logo-signer-project-01"); runCurrent(); val project = log.state.value.projects.single()
        val bytes = store.bytes!!.copyOf(); wait = true
        val change = async { runCatching { log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-signer-change-01") } }
        entered.await(); val close = launch { log.close() }; runCurrent(); answer.complete(Unit)
        assertTrue(change.await().isFailure); close.join(); runCurrent()
        assertContentEquals(bytes, store.bytes); assertTrue(net.guarded.isEmpty()); assertFalse(store.held)
    }

    @Test fun cancellingPhotoPreviewWhileSignerWaitsPreventsCommitWithoutClosingTheAccount() = runTest {
        val entered = CompletableDeferred<Unit>(); val answer = CompletableDeferred<Unit>(); var wait = false; var allowed = true
        val signer = object : ParticipantSigner by owner {
            override suspend fun sign(kind: Int, createdAt: Long, tags: List<List<String>>, content: String): NostrEvent {
                if (wait) { entered.complete(Unit); answer.await() }
                return owner.sign(kind, createdAt, tags, content)
            }
        }
        val store = Store(); val net = Network(); val log = open(store, net, signer); log.open()
        log.create(definition(), "logo-preview-project-01"); runCurrent(); val project = log.state.value.projects.single()
        val bytes = store.bytes!!.copyOf(); wait = true
        val change = async { runCatching { log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-preview-change-01") { allowed } } }
        entered.await(); allowed = false; answer.complete(Unit)
        assertTrue(change.await().isFailure); runCurrent()
        assertContentEquals(bytes, store.bytes); assertTrue(net.guarded.isEmpty()); assertTrue(log.state.value.ready)
        assertNull(log.state.value.projects.single().logo); log.close()
    }

    @Test fun corruptCompanionSignatureRejectsCacheWithoutPromptingSignerOrReplacingBytes() = runTest {
        val store = Store(); val net = Network(); var log = open(store, net); log.open()
        log.create(definition(), "logo-corruption-project"); runCurrent(); val project = log.state.value.projects.single()
        log.updateLogo(project.reference, project.heads, emptyList(), image(), "logo-corruption-change"); runCurrent(); log.close()
        val cache = Json.parseToJsonElement(store.load()!!).jsonObject
        val event = cache.getValue("logoEvents").jsonArray.single().jsonObject
        val corrupted = JsonObject(cache + ("logoEvents" to JsonArray(listOf(JsonObject(event + ("content" to JsonPrimitive("changed")))))))
        store.acquire(); store.save(corrupted.toString()); store.release(); val bytes = store.bytes!!.copyOf()
        var decrypts = 0
        val signer = object : ParticipantSigner by owner {
            override suspend fun nip44Decrypt(peer: String, payload: String): String { decrypts++; return owner.nip44Decrypt(peer, payload) }
        }
        log = open(store, net, signer); assertFailsWith<IllegalArgumentException> { log.open() }
        assertFalse(log.state.value.ready); assertEquals(0, decrypts); assertContentEquals(bytes, store.bytes); log.close()
    }

    @Test fun projectMembershipCannotBorrowAnotherProjectsLogoAndMembersCannotEditOwnersLogo() = runTest {
        val net = Network(); val log = open(Store(), net); log.open()
        log.create(definition(), "logo-scope-first-project"); runCurrent()
        val first = log.state.value.projects.single()
        log.updateLogo(first.reference, first.heads, emptyList(), image(), "logo-scope-first-change"); runCurrent()
        log.create(definition("Other project", listOf(owner, stranger)), "logo-scope-other-project"); runCurrent()
        val phone = open(Store(), net, member); phone.open(); val received = phone.state.value.projects.single()
        assertEquals(first.reference, received.reference); assertNotNull(received.logo); assertFalse(received.joined)
        assertFailsWith<IllegalStateException> { phone.updateLogo(received.reference, received.heads, received.logoHeads, null, "logo-scope-member-edit") }
        assertEquals(2, net.guarded.size)
        phone.close(); log.close()
    }
    @Test fun acceptedLogoRemovalStaysCurrentAcrossDelayedEditorReturn() = runTest {
        val log = open(Store(), Network()); log.open()
        log.create(definition(), "logo-editor-transition-project"); runCurrent()
        var project = log.state.value.projects.single()
        log.updateLogo(project.reference, project.heads, project.logoHeads, image(), "logo-editor-initial-image"); runCurrent()
        project = log.state.value.projects.single()
        val expected = ProjectLogoEditHeads(project.logoHeads)
        var alive = true; var closed = false
        val poll = launch { while (alive) { delay(250); if (!expected.matches(log.state.value.projects.single().logoHeads)) alive = false } }
        val removal = launch {
            val head = log.updateLogo(project.reference, project.heads, project.logoHeads, null, "logo-editor-remove-image", onAccepted = expected::accepted)
            // The view-model caller resumes on another dispatcher after publication.
            delay(500)
            expected.completed(head)
            if (alive && expected.matches(log.state.value.projects.single().logoHeads)) closed = true
        }
        runCurrent(); advanceTimeBy(501); runCurrent(); removal.join(); poll.cancelAndJoin()
        assertNull(log.state.value.projects.single().logo)
        assertEquals(2, log.state.value.projects.single().logoVersion)
        assertTrue(alive, "The editor invalidated its own accepted head before save returned")
        assertTrue(closed)
        assertFalse(expected.matches(project.logoHeads))
        assertFalse(expected.matches(listOf("unrelated-head")))
        log.close()
    }

    @Test fun ownAcceptedHeadNeverKeepsAnAccountOrProjectChangeCurrent() = runTest {
        for (changeProject in listOf(false, true)) {
            val log = open(Store(), Network()); log.open()
            log.create(definition(), "logo-editor-scope-project"); runCurrent()
            val project = log.state.value.projects.single()
            val expected = ProjectLogoEditHeads(project.logoHeads)
            val capturedAccount = Any(); var account = capturedAccount
            fun current() = account === capturedAccount && log.state.value.ready &&
                log.state.value.projects.single().heads == project.heads &&
                expected.matches(log.state.value.projects.single().logoHeads)
            var alive = true; var closed = false
            val poll = launch { while (alive) { delay(250); if (!current()) alive = false } }
            val save = launch {
                val head = log.updateLogo(project.reference, project.heads, project.logoHeads, null,
                    "logo-editor-scope-removal", onAccepted = expected::accepted) { alive && current() }
                delay(750)
                expected.completed(head)
                if (alive && current()) closed = true
            }
            runCurrent(); advanceTimeBy(100); runCurrent()
            if (changeProject) log.update(project.reference, project.heads, definition("Changed project"), "logo-editor-other-project-head")
            else account = Any()
            advanceTimeBy(201); runCurrent()
            assertFalse(alive)
            // Switching back must not revive a preview invalidated during save.
            account = capturedAccount
            advanceTimeBy(500); runCurrent(); save.join(); poll.cancelAndJoin()
            assertFalse(closed)
            log.close()
        }
    }

}
