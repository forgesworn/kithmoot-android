package dev.forgesworn.kithmoot.epoch

import android.os.Build
import androidx.test.core.app.ApplicationProvider
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.crypto.*
import dev.forgesworn.kithmoot.protocol.*
import dev.forgesworn.kithmoot.relay.*
import dev.forgesworn.kithmoot.session.*
import dev.forgesworn.kithmoot.storage.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList

/** Actual selected controller, sessions and Keystore/AtomicFile vaults.
 * Only the Nearby byte boundary is injected; no physical BLE or Internet. */
class NativeMemberCommandAndroidTest {
    private class Link(private val meshScope: String, private val scope: CoroutineScope) : RoomMeshLink {
        @Volatile var receive: ((ByteArray, String) -> Unit)? = null
        var peer: Link? = null
        val events = CopyOnWriteArrayList<NostrEvent>()
        private val queries = CopyOnWriteArrayList<JsonObject>()
        override fun subscribe(receive: (ByteArray, String) -> Unit): AutoCloseable {
            this.receive = receive
            return AutoCloseable { this.receive = null }
        }
        override fun offer(bytes: ByteArray, to: String?) {
            RoomMeshWire.decode(bytes)?.let { (kind, payload) ->
                payload["event"]?.let { events += NostrEvent.fromJson(it) }
                if (kind == RoomMeshWire.QUERY) payload.getValue("filters").jsonArray.forEach { queries += it.jsonObject }
            }
            val copy = bytes.clone()
            scope.launch { delay(1); peer?.receive?.invoke(copy, "lab-member") }
        }
        fun inject(event: NostrEvent) = receive?.invoke(RoomMeshWire.encode(RoomMeshWire.EVENT,
            buildJsonObject { put("scope", meshScope); put("event", event.toJson()) }), "lab-member")
        override suspend fun resetQueued() = Unit
        override fun reachable() = true
        override fun close() { receive = null }
        fun subscribedTo(kind: Int) = queries.any { query -> query["kinds"]?.jsonArray?.any { it.jsonPrimitive.int == kind } == true }
    }

    private class Rig {
        init { require(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Use the guarded disposable emulator runner" } }
        val app = ApplicationProvider.getApplicationContext<KithMootApplication>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val at = System.currentTimeMillis() / 1000
        val creation = NativeKeeperCreation.fresh(at)
        val base = creation.roomSecret()
        val room = deriveRoom(base)
        private val keys = (1..5).map { seed -> ByteArray(32) { (seed + it).toByte() } }
        val owner = PrimaryIdentity.create(room.roomId, at + 3600, at, keys[0], keys[1])
        val member = PrimaryIdentity.create(room.roomId, at + 3600, at, keys[2], keys[3])
        val offlineKey = keys[4]
        lateinit var offlineCredential: NostrEvent
        val binding = NativeKeeperBinding(room.roomId, creation.authority, owner.participant, owner.devicePubkey, RoomRoute.NEARBY, emptyList())
        val queueBinding = RoomRekeyBinding(room.roomId, binding.authority, owner.devicePubkey, binding.meshScope, emptyList(), RoomRoute.NEARBY)
        val authority = NativeKeeperVault(app, binding)
        val queueVault = RoomRekeyVault(app, queueBinding)
        private val receiverAlias = "kithmoot.lab.native-member-receiver." + room.roomId
        private val peerAlias = "kithmoot.lab.native-member-peer." + room.roomId
        private val receiverStore = EncryptedRoomStorage(app, receiverAlias)
        private val peerStore = EncryptedRoomStorage(app, peerAlias)
        val receiver = EpochVault(receiverStore)
        private val peerReceiver = EpochVault(peerStore)
        lateinit var source: NativeKeeperJournal
        lateinit var ledger: RoomRekeyLedger
        val link = Link(requireNotNull(binding.meshScope), scope)
        private val peerLink = Link(requireNotNull(binding.meshScope), scope)
        val mesh = RoomMeshTransport(requireNotNull(binding.meshScope), link)
        private val peerMesh = RoomMeshTransport(requireNotNull(binding.meshScope), peerLink)
        lateinit var live: RoomSession
        lateinit var peer: RoomSession
        @Volatile private var selected = true
        private var controller: NativeKeeperController? = null

        private fun session(identity: RoomIdentity, transport: RoomTransport, vault: EpochVault) =
            RoomSession(room, identity, transport, scope, authority = binding.authority,
                epochGate = { event, notice ->
                    if (notice.closed || notice.secret == null) vault.terminal(room.roomId, notice.epoch - 1, notice, event.id, System.currentTimeMillis() / 1000)
                    else requireNotNull(vault.follow(room.roomId, notice, event.id, System.currentTimeMillis() / 1000))
                    EpochGateResult.COMMITTED
                }, timing = SessionTiming(announceJitterMs = 0))

        suspend fun start() {
            offlineCredential = member.enrol(Schnorr.publicKeyHex(offlineKey), room.roomId, at + 3600, at)
            receiver.initialise(room.roomId, binding.authority, base, at)
            peerReceiver.initialise(room.roomId, binding.authority, base, at)
            source = authority.create(creation, owner.credential)
            ledger = queueVault.open(initialise = true)
            source.recordCourierCreated(ledger)
            live = session(owner, mesh, receiver); peer = session(member, peerMesh, peerReceiver)
            link.peer = peerLink; peerLink.peer = link
            live.join()
            controller = NativeKeeperController.start(source, receiver, live, ledger,
                NativeKeeperEndpoints(queueBinding, mesh, null), scope, { selected })
            await("native controller ready") { controller!!.state.value == NativeKeeperController.State.Ready(0, KeeperPhase.ACTIVE) }
            // Ready describes authority recovery, not callbackFlow registration.
            // Requests are volatile: observe the real subscription query before
            // injecting one rather than losing it in the startup race.
            await("native epoch-request subscription") { link.subscribedTo(KIND_EPOCH_REQUEST) }
            for ((key, credential) in listOf(member.deviceSecretKey to member.credential, offlineKey to offlineCredential)) {
                var ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, System.currentTimeMillis() / 1000)
                link.inject(ask)
                if (credential == member.credential) {
                    await("unknown primary participant") { member.participant in controller!!.unknownParticipants.value }
                    await("unapproved primary refusal") { grants(key, ask.id).any { it == EpochGrant.Refused("unknown") } }
                    controller!!.approve(member.participant)
                    // Approval does not turn the original refused answer into
                    // a secret-bearing grant. Ask again with a fresh request.
                    val refusedRequest = ask.id
                    ask = encodeEpochRequest(room.roomId, binding.authority, room.roomKey, key, credential, System.currentTimeMillis() / 1000)
                    assertNotEquals(refusedRequest, ask.id)
                    link.inject(ask)
                }
                await("approved ${if (credential == member.credential) "primary" else "offline"} device grant") {
                    grants(key, ask.id).any { it is EpochGrant.Current && it.epoch == 0 && member.participant in it.members.orEmpty() }
                }
            }
            // An idempotent command is a worker barrier: observing the byte
            // offer alone does not prove its source accounting has finished.
            controller!!.approve(member.participant)
        }

        private fun grants(key: ByteArray, request: String) = link.events.mapNotNull {
            decodeEpochGrant(it, room.roomId, binding.authority, key, request, System.currentTimeMillis() / 1000)
        }

        suspend fun joinPeer() {
            peer.join()
            await("member chat subscription") { peerLink.subscribedTo(KIND_CHAT) }
            await("member rekey subscription") { peerLink.subscribedTo(KIND_ROOM_REKEY) }
        }

        suspend fun command(removed: List<String> = emptyList(), destruct: Boolean = false) =
            controller!!.rekeyMembers(removed, destruct = destruct)
        suspend fun retire() = controller!!.retire()
        fun hosting() = controller!!.hosting.value
        suspend fun retryRetirement(expected: NativeHostingState, id: String) = controller!!.retryObservedRetirement(expected, id)
        suspend fun reopenKeeper(legacyFour: Boolean = false) {
            controller!!.stop()
            if (legacyFour) {
                val alias = "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray()).toHex()
                val actual = EncryptedRoomStorage(app, alias, NativeKeeperJournal.MAX_FILE_BYTES)
                val bytes = requireNotNull(actual.read())
                try {
                    val root = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
                    val legacy = JsonObject(root - "retirements" - "legacyRetirementSlots" + ("v" to JsonPrimitive(4))).toString().toByteArray()
                    try { actual.write(legacy) } finally { legacy.fill(0) }
                } finally { bytes.fill(0) }
            }
            source = authority.open(); ledger = queueVault.open()
            controller = NativeKeeperController.start(source, receiver, live, ledger,
                NativeKeeperEndpoints(queueBinding, mesh, null), scope, { selected })
            await("cold retired controller ready") {
                controller!!.state.value == NativeKeeperController.State.Ready(0, KeeperPhase.RETIRED)
            }
        }

        fun peerDurable() = requireNotNull(peerReceiver.get(room.roomId))

        fun ciphertext(sourceFile: Boolean): ByteArray {
            val alias = if (sourceFile) "kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray()).toHex()
                else "kithmoot.keeper-rekeys." + Digests.sha256(queueBinding.owner.toByteArray()).toHex()
            // A live owner's committed descriptor, never AtomicFile.openRead.
            return File(app.noBackupFilesDir, "$alias.vault").inputStream().use { stream ->
                check(stream.channel.size() <= NativeKeeperJournal.MAX_FILE_BYTES + 64)
                stream.readBytes()
            }
        }

        suspend fun close() {
            selected = false
            try { controller?.stop() ?: run {
                if (::source.isInitialized) source.close()
                if (::ledger.isInitialized) ledger.close()
            } }
            finally {
                try { if (::live.isInitialized) live.leave(); if (::peer.isInitialized) peer.leave() }
                finally {
                    mesh.close(); peerMesh.close(); scope.cancel()
                    authority.forget(); queueVault.forget(); receiverStore.reset(); peerStore.reset()
                    val aliases = listOf("kithmoot.keeper-authority." + Digests.sha256(binding.owner.toByteArray()).toHex(),
                        "kithmoot.keeper-rekeys." + Digests.sha256(queueBinding.owner.toByteArray()).toHex(), receiverAlias, peerAlias)
                    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                    assertTrue(aliases.all { !store.containsAlias(it) && !File(app.noBackupFilesDir, "$it.vault").exists() &&
                        !File(app.noBackupFilesDir, "$it.vault.new").exists() && !File(app.noBackupFilesDir, "$it.vault.bak").exists() })
                    creation.close(); base.fill(0); room.roomKey.fill(0); keys.forEach { it.fill(0) }
                }
            }
        }
    }

    @Test fun sourceAudienceRotatesBothMemberDevicesAndRefusalsPreserveActualEncryptedStoresAndChat() = runBlocking {
        val r = Rig()
        try {
            r.start()
            val source = r.ciphertext(true); val queue = r.ciphertext(false)
            try {
                for (action in listOf<suspend () -> Unit>({ r.command(listOf(r.owner.participant)) },
                    { r.command(listOf("ab".repeat(32))) }, { r.command(destruct = true) })) {
                    var refused = false
                    try { action() } catch (error: IllegalArgumentException) { refused = true }
                    assertTrue("Invalid member command must refuse", refused)
                }
                assertTrue(source.contentEquals(r.ciphertext(true))); assertTrue(queue.contentEquals(r.ciphertext(false)))
            } finally { source.fill(0); queue.fill(0) }
            r.joinPeer(); r.live.sendChat("Host after native command refusal"); r.peer.sendChat("Member after native command refusal")
            await("paired chat after refusal") { r.live.chat.value.size == 2 && r.peer.chat.value.size == 2 }
            r.command()
            await("paired successor epoch") { r.live.epochState.value is RoomEpochState.Active && r.peer.epochState.value is RoomEpochState.Active &&
                (r.peer.epochState.value as RoomEpochState.Active).epoch == 1 }
            val original = r.ledger.status().entries.single().event
            assertTrue(Events.verify(original)); assertEquals(original.id, r.source.snapshot().epochCause)
            assertEquals(original.id, r.receiver.get(r.room.roomId)!!.activationCause)
            val previous = deriveEpoch(RoomEpoch(0, r.base))
            try {
                val body = Json.parseToJsonElement(Nip44.decrypt(original.content, previous.key)).jsonObject
                assertEquals(setOf(r.owner.devicePubkey, r.member.devicePubkey, Schnorr.publicKeyHex(r.offlineKey)), body.getValue("keys").jsonObject.keys)
                val notice = requireNotNull(decodeRekeyEvent(original, r.room.roomId, r.binding.authority, previous, r.offlineKey))
                try { assertTrue(requireNotNull(notice.secret).contentEquals(r.receiver.get(r.room.roomId)!!.currentSecret)) }
                finally { notice.secret?.fill(0) }
            } finally { previous.key.fill(0) }
            r.live.sendChat("Host after source-derived successor"); r.peer.sendChat("Member after source-derived successor")
            await("paired chat after successor") { r.live.chat.value.size == 4 && r.peer.chat.value.size == 4 }
        } finally { r.close() }
    }

    @Test fun completedRetirementReopensTheActualEncryptedSourceAndConservesOriginalAttempts() = runBlocking {
        val r = Rig()
        try {
            r.start(); r.joinPeer(); r.retire()
            val original = r.link.events.filter { it.kind == KIND_INVITATION_RETIREMENT }.distinctBy { it.id }.single()
            assertEquals(KeeperPhase.RETIRED, r.source.snapshot().phase)
            assertEquals(listOf(original.id), r.hosting().retirementOriginals)
            assertEquals(0, r.hosting().missingRetirementSlots)
            assertTrue(r.source.snapshot().pending.isEmpty())
            val initial = r.hosting()
            r.reopenKeeper()
            assertNotEquals(initial.ownerGeneration, r.hosting().ownerGeneration)
            assertEquals(listOf(original.id), r.hosting().retirementOriginals)
            val before = r.ciphertext(true); val courier = r.ciphertext(false)
            try {
                var refused = false
                try { r.retryRetirement(initial, original.id) } catch (_: IllegalArgumentException) { refused = true }
                assertTrue("Previous owner's observation must refuse", refused)
                assertTrue(before.contentEquals(r.ciphertext(true)))
                assertTrue(courier.contentEquals(r.ciphertext(false)))
            } finally { before.fill(0); courier.fill(0) }
            val expected = r.hosting()
            r.retryRetirement(expected, original.id)
            assertEquals(listOf(original), r.link.events.filter { it.kind == KIND_INVITATION_RETIREMENT }.distinctBy { it.id })
            assertEquals(2, r.link.events.count { it.id == original.id })
            assertEquals(0, r.source.snapshot().epoch)
            assertEquals(3, r.source.snapshot().deviceCount)
            r.live.sendChat("Host after retirement reopen"); r.peer.sendChat("Member after retirement reopen")
            await("paired chat after retired source reopen") { r.live.chat.value.size == 2 && r.peer.chat.value.size == 2 }
            println("NATIVE_RETIREMENT_MEASUREMENT completed-source-reopen original-count=1 reserved-attempts=2 devices=3 paired-chat-rows=2 physical-radio=false")
        } finally { r.close() }
    }

    @Test fun missingLegacyRetirementEvidenceKeepsApprovedChatAndRefusesInventedRetry() = runBlocking {
        val r = Rig()
        try {
            r.start(); r.joinPeer(); r.retire()
            val original = r.link.events.filter { it.kind == KIND_INVITATION_RETIREMENT }.distinctBy { it.id }.single()
            r.reopenKeeper(legacyFour = true)
            assertEquals(1, r.hosting().missingRetirementSlots)
            assertTrue(r.hosting().retirementOriginals.isEmpty())
            assertEquals(KeeperPhase.RETIRED, r.source.snapshot().phase)
            assertEquals(3, r.source.snapshot().deviceCount)
            val before = r.ciphertext(true); val courier = r.ciphertext(false)
            try {
                var refused = false
                try { r.retryRetirement(r.hosting(), original.id) } catch (_: IllegalArgumentException) { refused = true }
                assertTrue("A missing legacy original cannot be recreated", refused)
                assertTrue(before.contentEquals(r.ciphertext(true)))
                assertTrue(courier.contentEquals(r.ciphertext(false)))
            } finally { before.fill(0); courier.fill(0) }
            r.live.sendChat("Host with missing old notice"); r.peer.sendChat("Approved member with missing old notice")
            await("paired chat with missing old notice") { r.live.chat.value.size == 2 && r.peer.chat.value.size == 2 }
            assertEquals(1, r.link.events.count { it.id == original.id })
            println("NATIVE_RETIREMENT_MEASUREMENT legacy-completed-reopen missing-slots=1 invented-originals=0 devices=3 paired-chat-rows=2 physical-radio=false")
        } finally { r.close() }
    }

    @Test fun removalRetiresBothDeviceSealsAndTheActualRemovedSessionCannotPublish() = runBlocking {
        val r = Rig()
        try {
            r.start(); r.joinPeer(); r.command(listOf(r.member.participant))
            await("removed member epoch") { r.peer.epochState.value == RoomEpochState.Removed(1) }
            assertEquals(listOf(r.owner.participant), r.source.snapshot().members)
            assertEquals(listOf(r.member.participant), r.source.snapshot().removed)
            val original = r.ledger.status().entries.single().event
            val terminal = r.peerDurable()
            try {
                assertEquals(EpochPhase.REMOVED, terminal.phase)
                assertEquals(0, terminal.currentEpoch)
                assertEquals(original.id, terminal.terminalCause)
                assertEquals(listOf(r.member.participant), terminal.removed)
                assertNull(terminal.pending)
            } finally { terminal.currentSecret.fill(0) }
            val previous = deriveEpoch(RoomEpoch(0, r.base))
            try {
                val body = Json.parseToJsonElement(Nip44.decrypt(original.content, previous.key)).jsonObject
                assertEquals(setOf(r.owner.devicePubkey), body.getValue("keys").jsonObject.keys)
                for (key in listOf(r.member.deviceSecretKey, r.offlineKey))
                    assertNull(requireNotNull(decodeRekeyEvent(original, r.room.roomId, r.binding.authority, previous, key)).secret)
            } finally { previous.key.fill(0) }
            var refused = false
            try { r.peer.sendChat("Removed member must not publish") } catch (error: IllegalStateException) { refused = true }
            assertTrue(refused)
            r.live.sendChat("Remaining native owner after removal")
            await("remaining owner chat") { r.live.chat.value.any { it.body == "Remaining native owner after removal" } }
        } finally { r.close() }
    }

    companion object {
        private suspend fun await(stage: String, test: () -> Boolean) {
            try { withTimeout(30_000) { while (!test()) delay(10) } }
            catch (timeout: TimeoutCancellationException) { throw AssertionError("Native member command stage timed out: $stage", timeout) }
        }
    }
}
