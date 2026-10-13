package dev.forgesworn.kithmoot.g5

import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.account.LocalSigner
import dev.forgesworn.kithmoot.cadence.CadenceOwnership
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.protocol.BothyPairing
import dev.forgesworn.kithmoot.protocol.BoxCadence
import dev.forgesworn.kithmoot.protocol.CadenceLeaseOptions
import dev.forgesworn.kithmoot.protocol.CadenceScope
import dev.forgesworn.kithmoot.protocol.Events
import dev.forgesworn.kithmoot.protocol.DeadDrop
import dev.forgesworn.kithmoot.protocol.RoomDrops
import dev.forgesworn.kithmoot.protocol.createDeviceCredential
import dev.forgesworn.kithmoot.relay.LinkConsent
import dev.forgesworn.kithmoot.relay.LinkConsentState
import dev.forgesworn.kithmoot.relay.LinkJsonRequest
import dev.forgesworn.kithmoot.session.PrimaryIdentity
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real generated JNI binding -> Link session -> Bothy's fixture-only ordinary cadence router. */
class G7CadenceStatusTest {
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val control get() = requireNotNull(arguments.getString("fixture_control")).removeSuffix("/")
    private val action get() = requireNotNull(arguments.getString("g7_action"))
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as KithMootApplication

    private fun syntheticRecordingStorageOrigin(): String {
        val origin = arguments.getString("recordingStorageOrigin") ?: "https://127.0.0.1:39847"
        val uri = URI(origin)
        require(uri.scheme == "https" && uri.host == "127.0.0.1" && uri.port in 1..65535 &&
            origin == "https://127.0.0.1:${uri.port}") {
            "Synthetic recording qualification requires one explicit loopback HTTPS origin"
        }
        return origin
    }

    @Test fun runsRequestedCadenceProof() {
        when (action) {
            "probe" -> pairAndRunSuccessorCutover()
            "restart-check" -> reconnectAndVerifySuccessorCutover()
            "recording-queue" -> pairAndQueueRecording()
            "recording-lost-withdrawal" -> withdrawRecordingWithLostReply()
            "recording-withdrawal-replay" -> replayRecordingWithdrawal()
            "recording-room-stage" -> stageRecordingRoomModelLease()
            "recording-room-authority-stage" -> stageRecordingRoomModelLease(withAuthority = true)
            "recording-room-prepare" -> prepareRecordingRoomModelDraft()
            "recording-room-prepare-retained-export" -> prepareRecordingRoomModelDraft(resumeExport = true)
            "recording-room-prepared-verify" -> verifyPreparedRecordingRoomModelDraft()
            "recording-room-send" -> sendPreparedRecordingRoomModelDraft()
            "recording-room-send-leave" -> sendPreparedRecordingRoomModelDraft(leaveAfterQueue = true)
            "recording-room-send-rekey" -> sendPreparedRecordingRoomModelDraft(rekeyAfterQueue = true)
            "recording-room-rekey-inspect" -> inspectOriginalRecordingRekey()
            "recording-room-leave-lost-reply" -> sendPreparedRecordingRoomModelDraft(leaveAfterQueue = true,
                loseWithdrawalReply = true)
            "recording-room-cancellation-restart" -> retryOriginalRecordingCancellationAfterRestart()
            "recording-room-withdrawn-restart" -> verifyWithdrawnRecordingRoomModelRestart()
            "recording-room-send-controls" -> sendPreparedRecordingRoomModelDraft(send = false)
            "recording-room-send-observe" -> observeOriginalRecordingRoomModelSend()
            "recording-room-first-publication" -> sendPreparedRecordingRoomModelDraft(afterVerifiedNoop = true)
            "recording-room-confirmed-restart" -> verifyConfirmedRecordingRoomModelRestart()
            "recording-room-stored-drop-inspect" -> inspectOriginalRecordingStoredDrops()
            "recording-worker-stage" -> stageRecordingWorkerLease()
            "recording-worker-lead-in" -> verifyRecordingWorkerLeadIn()
            "recording-worker-delivery" -> observeRecordingWorkerDelivery()
            "recording-worker-retained-delivery" -> observeRecordingWorkerDelivery(retained = true)
            "recording-worker-expired-refusal" -> verifyExpiredRecordingWorkerRefusal()
            "recording-worker-original-status" -> verifyOriginalRecordingWorkerStatus()
            "recording-worker-guarded-transport-refusal" -> verifyOriginalGuardedTransportRefusal()
            "recording-worker-fresh-delivery" -> observeRecordingWorkerDelivery(freshSample = true)
            else -> throw AssertionError("unknown G7 cadence action")
        }
    }

    /** Inspect the retained failed attempt without opening a room or making
     * any publication, cadence request, cancellation retry or pairing. */
    private fun inspectOriginalRecordingRekey() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        try {
            val saved = requireNotNull(app.savedRooms.get(room.roomId))
            assertEquals(DEVICE, saved.devicePubkey)
            assertEquals(LocalSigner(PERSONA_SECRET.hexToBytes()).pubkey, saved.participant)
            val original = app.recordingNodeHandoffs.pending(saved.participant).single()
            val lease = app.cadenceLeases.all(room.roomId, DEVICE).single {
                it.plan.requestBody == original.binding.leaseBody
            }
            val draft = requireNotNull(app.recordingShareDrafts.selected(original.binding.draftId, room.roomId))
            val epoch = requireNotNull(app.roomEpochs.get(room.roomId))
            val receipt = lease.receipt
            println("REKEY_INSPECTION=" + buildJsonObject {
                put("nativeEpoch", epoch.currentEpoch); put("epochPhase", epoch.phase.name)
                put("custodyState", original.state.name); put("originalEvent", original.binding.event.id)
                put("leaseId", lease.plan.leaseId); put("leaseGeneration", lease.plan.generation)
                put("leaseOwnership", lease.ownership.name); put("receiptCode", receipt?.code)
                put("receiptState", receipt?.state); put("queueCount", receipt?.queueCount)
                put("originalEventFailed", receipt?.failedItemIds?.contains(original.binding.event.id) == true)
                put("originalEventSent", receipt?.sentItemIds?.contains(original.binding.event.id) == true)
                put("originalWithdrawalReserved", original.withdrawal != null)
                put("sourceRetained", app.recordings.selectedExport(draft.sourceName).isFile)
                put("ciphertextRetained", draft.sealed.file.isFile)
                put("uploadedRetained", draft.uploaded != null)
                put("originalPreparedEventUnchanged", draft.preparedSend?.pending?.event == original.binding.event)
                put("sendInvoked", false); put("uploadInvoked", false); put("retryInvoked", false)
            })
        } finally { room.roomKey.fill(0) }
    }

    private fun pairAndRunSuccessorCutover() {
        val now = epochSeconds()
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val changedCard = pairing.card.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFutureRefused(app.linkEngine.pair(changedCard, pairing.pairingSecret.copyOf(), pairing.expiresAt))
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val ready = get("ready")
        val nodeId = ready.getValue("link_node_id").jsonPrimitive.content
        val identity = identity(now)
        val scope = scope(identity, nodeId)
        val body = BoxCadence.statusBody(scope, "44".repeat(16), now)
        val signed = BoxCadence.sign(nodeId, "POST", "/cadence/v1/status", body, identity.deviceSecretKey, now)
        val request = LinkJsonRequest(route.routeId, signed.method, signed.path, signed.authorization, signed.body)

        assertFutureRefused(app.linkEngine.request(request.copy(routeId = "route-absent")))
        assertFutureRefused(app.linkEngine.request(request.copy(method = "GET")))
        assertFutureRefused(app.linkEngine.request(request.copy(path = "/events")))
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, app.linkEngine.request(
            request.copy(body = request.body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }),
        ).get(120, TimeUnit.SECONDS).status)
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, app.linkEngine.request(
            request.copy(authorization = changedSignature(request.authorization)),
        ).get(120, TimeUnit.SECONDS).status)
        assertFalse(Class.forName("dev.forgesworn.link.ffi.LinkHttpRequest").methods.any { it.name.contains("host", ignoreCase = true) })

        val consent = consent(identity, nodeId, route.routeId, ready.getValue("claimed_by").jsonPrimitive.content)
        app.linkConsents.put(consent)

        val late = app.cadenceClient.status(identity.participant, scope, REQUEST_ID, identity, now)
        app.linkConsents.remove(consent.accountPubkey, consent.roomId, consent.bothyNodeId)
        val refusal = assertThrows(ExecutionException::class.java) { late.get(120, TimeUnit.SECONDS) }
        assertEquals("cadence consent changed while the request was in flight", refusal.cause?.message)

        app.linkConsents.put(consent)
        val result = app.cadenceClient.status(identity.participant, scope, REQUEST_ID, identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        val status = result.answer
        assertTrue(status.ready)
        assertTrue(status.missing.isEmpty())

        val start = status.earliestStartEpoch
        val options = leaseOptions(scope, REQUEST_ID, LEASE_ID, status.currentEpoch, start, start + 6, now, 1)
        val staged = app.cadenceClient.stage(identity.participant, options, identity, epochSeconds(), app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        assertEquals(CadenceOwnership.BOX_OWNED, staged.ownership)
        assertEquals("staged", staged.receipt?.code)
        assertEquals("staged", staged.receipt?.state)
        assertEquals((0 until 8).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, start))

        val queuedEvent = Events.sign(
            identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", ROOM)),
            "fixture quiet ciphertext", ByteArray(32) { 7 },
        )
        val queued = app.cadenceClient.queue(
            identity.participant, scope, identity, staged, QUEUE_REQUEST_ID, queuedEvent,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("queued", queued.receipt?.code)
        assertEquals(1, queued.receipt?.queueCount)

        // A separate counter slot retains the original pending-item cutover
        // proof without cancelling the first lease's real delivery.
        val pendingOptions = leaseOptions(scope, PENDING_REQUEST_ID, PENDING_LEASE_ID,
            status.currentEpoch, start, start + 6, now, 1, 1)
        val pending = app.cadenceClient.stage(identity.participant, pendingOptions, identity,
            epochSeconds(), app.cadenceLeases).get(120, TimeUnit.SECONDS).lease
        val pendingEvent = Events.sign(
            identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", ROOM)),
            "pending fixture quiet ciphertext", ByteArray(32) { 8 },
        )
        val pendingQueued = app.cadenceClient.queue(identity.participant, scope, identity,
            pending, PENDING_QUEUE_REQUEST_ID, pendingEvent, epochSeconds(), app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        val pendingRekeyed = app.cadenceClient.rekey(identity.participant, scope, identity,
            pendingQueued, PENDING_REKEY_REQUEST_ID, 3, epochSeconds(), app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        assertEquals("rekeyed", pendingRekeyed.receipt?.code)
        assertEquals("cover", pendingRekeyed.receipt?.state)
        assertEquals(0, pendingRekeyed.receipt?.queueCount)
        assertEquals(listOf(pendingEvent.id), pendingRekeyed.receipt?.failedItemIds)
        assertEquals((0 until 16).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, start))

        put("g7-cadence-probe", buildJsonObject {
            put("authenticated", true); put("ready", status.ready); put("missing", status.missing.joinToString(","))
            put("linkPath", result.path.status); put("consentWithdrawalRefusedLateResult", true); put("countersExcluded", true)
            put("leaseStaged", true); put("messageQueued", true)
            put("pendingItemRekeyed", true); put("pendingItemFailed", true)
            put("leaseStartEpoch", start); put("leaseEndEpoch", options.endEpoch)
            put("changedNodeCardRefused", true); put("unknownRouteRefused", true); put("changedMethodRefused", true)
            put("changedPathRefused", true); put("changedPayloadRefused", true); put("changedDeviceSignatureRefused", true)
            put("hostPinnedByBridge", true)
        })
    }

    private fun reconnectAndVerifySuccessorCutover() {
        val stored = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == LEASE_ID }
        assertEquals(CadenceOwnership.BOX_OWNED, stored.ownership)
        assertEquals("queued", stored.receipt?.code)
        assertEquals((0 until 16).toList(), app.cadenceLeases.reservedCounters(ROOM, DEVICE, stored.plan.startEpoch))
        val pendingStored = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == PENDING_LEASE_ID }
        assertEquals("rekeyed", pendingStored.receipt?.code)
        assertEquals("cover", pendingStored.receipt?.state)
        assertEquals(1, pendingStored.receipt?.failedItemIds?.size)
        val consent = app.linkConsents.all().single { it.roomId == ROOM }
        assertTrue(app.linkEngine.routeIds().contains(consent.routeId))
        val now = epochSeconds()
        val identity = identity(now)
        val nodeId = consent.canonicalUrl.removePrefix("ws://").removeSuffix("/events")
        val status = app.cadenceClient.status(identity.participant, scope(identity, nodeId), "66".repeat(16), identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        assertTrue(status.answer.ready)
        val retained = app.cadenceClient.leaseStatus(
            identity.participant, scope(identity, nodeId), identity, stored, STATUS_REQUEST_ID,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("status", retained.receipt?.code)
        assertEquals(1, retained.receipt?.sentItemIds?.size)
        assertTrue(retained.receipt?.failedItemIds.isNullOrEmpty())
        val pendingRetained = app.cadenceClient.leaseStatus(
            identity.participant, scope(identity, nodeId), identity, pendingStored, PENDING_STATUS_REQUEST_ID,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals(pendingStored.receipt?.failedItemIds, pendingRetained.receipt?.failedItemIds)

        val rekeyed = app.cadenceClient.rekey(
            identity.participant, scope(identity, nodeId), identity, retained, REKEY_REQUEST_ID, 3,
            epochSeconds(), app.cadenceLeases,
        ).get(120, TimeUnit.SECONDS).lease
        assertEquals("rekeyed", rekeyed.receipt?.code)
        assertEquals("cover", rekeyed.receipt?.state)
        assertEquals(1, rekeyed.receipt?.sentItemIds?.size)
        assertFutureRefused(app.cadenceClient.queue(
            identity.participant, scope(identity, nodeId), identity, rekeyed, OLD_QUEUE_REQUEST_ID,
            Events.sign(identity.deviceSecretKey, 1460, epochSeconds(), listOf(listOf("d", ROOM)), "late", ByteArray(32) { 8 }),
            epochSeconds(), app.cadenceLeases,
        ))

        val lowerScope = scope(identity, nodeId, LOWER_TRAFFIC_ROOM, 2)
        val lowerStart = maxOf(status.answer.earliestStartEpoch, stored.plan.endEpoch)
        assertFutureRefused(app.cadenceClient.stage(
            identity.participant,
            leaseOptions(
                lowerScope, LOWER_REQUEST_ID, LOWER_LEASE_ID, status.answer.currentEpoch,
                lowerStart, lowerStart + 1, now, 2,
            ),
            identity, epochSeconds(), app.cadenceLeases,
        ))
        val lower = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == LOWER_LEASE_ID }
        assertEquals(CadenceOwnership.CLIENT_EXCLUDED, lower.ownership)
        put("g7-cadence-restart", buildJsonObject {
            put("routeRestored", true); put("messageDeliveredAfterRestart", true); put("countersStillExcluded", true)
            put("authenticatedStatusAfterRestart", true); put("rekeyedAfterDelivery", true)
            // The lease state the box reported to the phone; the lab asserts it.
            put("leaseStateAfterRestart", retained.receipt?.state)
            put("pendingFailureRetained", true)
            put("oldGenerationQueueRefused", true); put("lowerGenerationRefused", true); put("linkPath", status.path.status)
        })
    }

    /** Actual Add/Upload controls against an explicitly authorised private
     * daemon. Preparation stays separate from the future explicit Send. */
    private fun prepareRecordingRoomModelDraft(resumeExport: Boolean = false) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        val saved = requireNotNull(app.savedRooms.get(room.roomId))
        check(app.savedRooms.list().size == 1 && app.accounts.load() == null)
        check(saved.participant == LocalSigner(PERSONA_SECRET.hexToBytes()).pubkey && saved.devicePubkey == DEVICE)
        val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
        check(epochSeconds() / 3600 < lease.plan.startEpoch) { "Prepare before original activation; never send early" }
        assertEquals(lease.plan.nodeId, get("ready").getValue("link_node_id").jsonPrimitive.content)
        val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
        require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        val fixture = java.io.File(app.noBackupFilesDir, name)
        val marker = java.io.File(fixture, "room-recording-prepared.json")
        check(!marker.exists() && app.recordingShareDrafts.list(room.roomId).isEmpty() &&
            (resumeExport || app.recordings.export.value == null)) {
            "Original recording preparation is retained; never replace or upload it automatically"
        }
        val retained = if (resumeExport) {
            check(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
            val sourceName = requireNotNull(arguments.getString("recordingRetainedSource"))
            val originalSha = requireNotNull(arguments.getString("recordingRetainedSha256"))
            require(originalSha.matches(Regex("[0-9a-f]{64}")))
            val file = app.recordings.selectedExport(sourceName)
            assertEquals(file, app.recordings.export.value)
            assertEquals(room.roomId, app.recordings.details(file).origin?.room)
            val actualSha = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals("Resume only the original unmodified AAC export", originalSha, actualSha)
            file
        } else null
        val keys = java.security.KeyStore.getInstance("PKCS12").apply {
            java.io.File(fixture, "fixture.p12").inputStream().use { load(it, "synthetic-test-only".toCharArray()) }
        }
        val trust = java.security.KeyStore.getInstance("PKCS12").apply {
            load(null); setCertificateEntry("fixture", keys.getCertificate(keys.aliases().nextElement()))
        }
        val managers = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val http = okhttp3.OkHttpClient.Builder().sslSocketFactory(tls.socketFactory,
            managers.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()).build()
        val origin = syntheticRecordingStorageOrigin()
        val ui = dev.forgesworn.kithmoot.storage.RecoveryUi(useSwipeFallback = false, freshNodes = true)
        lateinit var model: dev.forgesworn.kithmoot.ui.RoomViewModel
        val scenario = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { owner ->
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        dev.forgesworn.kithmoot.ui.RoomViewModel(app, chatOnly = true,
                            recordingUploadClient = http, recordingNodeQualification = true) as T
                })["recording-node-product", dev.forgesworn.kithmoot.ui.RoomViewModel::class.java]
                owner.setContent { dev.forgesworn.kithmoot.ui.theme.KithMootTheme {
                    dev.forgesworn.kithmoot.ui.KithMootApp(model)
                } }
                model.reopenRoom(room.roomId)
            }
            ui.await("original quiet recording room reopened", timeoutMs = 120_000) {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.ROOM && !model.start.value.busy
            }
            if (retained == null) {
                val source = app.recordings.begin(dev.forgesworn.kithmoot.media.recording.RecordingFormat.AUDIO,
                    dev.forgesworn.kithmoot.media.recording.RecordingOrigin(room.roomId, "cd".repeat(16), saved.name))
                val encoder = dev.forgesworn.kithmoot.media.recording.AacRecordingFile(source)
                try {
                    repeat(100) { block -> encoder.write(ShortArray(960) { index ->
                        (12_000 * kotlin.math.sin(2 * Math.PI * 660 * (block * 960 + index) / 48_000)).toInt().toShort()
                    }) }
                    encoder.close()
                } catch (error: Throwable) { encoder.discard(); throw error }
                app.recordings.complete(source)
            }
            val completed = requireNotNull(app.recordings.export.value)
            if (retained != null) assertEquals(retained, completed)
            ui.await("native AAC export offered") { ui.hasText("Add to original chat") }
            ui.click("Add to original chat")
            ui.await("original private recording draft") { ui.hasText("Upload recording") }
            ui.click("Upload recording"); ui.replace("Recording storage server", origin); ui.click("Get storage key")
            ui.await("separate storage identity") { ui.hasText("Copy storage key") }
            val choice = requireNotNull(model.room.value.recordingStorageChoice)
            check(choice.publicKey != saved.participant && choice.publicKey != saved.devicePubkey)
            java.io.File(fixture, "storage-public-key.txt").writeText(choice.publicKey)
            ui.await("private daemon explicitly authorised original storage key", timeoutMs = 120_000) {
                java.io.File(fixture, "node-ready").isFile
            }
            ui.click("Allow this encrypted recording to be uploaded to $origin"); ui.click("Upload")
            ui.await("actual encrypted private upload completed", timeoutMs = 120_000) {
                !model.room.value.recordingUploadRunning && app.recordingShareDrafts.list(room.roomId).single().uploaded != null
            }
            assertEquals(null, model.room.value.chatSendError)
            val draft = app.recordingShareDrafts.list(room.roomId).single()
            val attachment = requireNotNull(draft.uploaded)
            val stats = http.newCall(okhttp3.Request.Builder().url("$origin/__fixture/$name").build()).execute().use {
                check(it.code == 200); org.json.JSONObject(requireNotNull(it.body).string())
            }
            assertEquals(1, stats.getInt("puts")); assertEquals(0, stats.getInt("gets"))
            assertEquals(draft.sealed.hash, stats.getString("sha256"))
            assertEquals(draft.sealed.file.length(), stats.getLong("size"))
            assertEquals(draft.sealed.hash, attachment.sha256)
            assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
            assertTrue(model.room.value.chat.none { it.attachments.isNotEmpty() })
            assertEquals(null, draft.preparedSend)
            assertTrue(completed.isFile)
            assertEquals(completed, app.recordings.selectedExport(draft.sourceName))
            marker.writeText(org.json.JSONObject().put("draft", draft.id).put("source", completed.name)
                .put("encryptedSha256", draft.sealed.hash).put("origin", origin).toString())
            put("recording-room-prepare", buildJsonObject {
                put("actualAddAndUploadControlsPassed", true); put("privateDaemonCiphertextPutCount", 1)
                put("storageIdentitySeparate", true); put("originalDraftRetained", true)
                put("recordingQueued", false); put("recordingSendQualified", false); put("clockCompressed", false)
            })
            scenario.onActivity { model.leave() }
            ui.await("original draft retained while product room closes") {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.START && !model.start.value.busy
            }
            assertEquals(draft, app.recordingShareDrafts.selected(draft.id, room.roomId))
            val refreshedLease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            assertEquals(lease.plan, refreshedLease.plan)
            assertEquals(lease.ownership, refreshedLease.ownership)
        } finally {
            scenario.close(); room.roomKey.fill(0)
            http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
        }
    }

    /** Read the original retained result after process death. No Add, Upload,
     * lease staging or Send is invoked by this recovery verification. */
    private fun verifyPreparedRecordingRoomModelDraft() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        try {
            val saved = requireNotNull(app.savedRooms.get(room.roomId))
            check(saved.participant == LocalSigner(PERSONA_SECRET.hexToBytes()).pubkey && saved.devicePubkey == DEVICE)
            val draft = app.recordingShareDrafts.list(room.roomId).single()
            val source = app.recordings.selectedExport(draft.sourceName)
            val details = app.recordings.details(source)
            assertEquals(room.roomId, details.origin?.room)
            assertEquals(dev.forgesworn.kithmoot.media.recording.RecordingFormat.AUDIO, details.format)
            assertTrue(source.isFile && source.length() > 0)
            val uploaded = requireNotNull(draft.uploaded)
            assertEquals(draft.sealed.hash, uploaded.sha256)
            assertEquals(draft.sealed.file.length(), uploaded.size)
            assertEquals(syntheticRecordingStorageOrigin(), draft.storageOrigin)
            assertTrue(app.recordingUploadJournal.readyToSend(room.roomId, requireNotNull(draft.storageOrigin), draft.sealed.hash))
            assertEquals(null, draft.preparedSend)
            assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
            val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            val stage = get("journey/recording-room-stage")
            assertEquals(stage.getValue("leaseId").jsonPrimitive.content, lease.plan.leaseId)
            assertEquals(stage.getValue("generation").jsonPrimitive.content.toLong(), lease.plan.generation)
            assertEquals(stage.getValue("startEpoch").jsonPrimitive.content.toLong(), lease.plan.startEpoch)
            assertEquals(stage.getValue("endEpoch").jsonPrimitive.content.toLong(), lease.plan.endEpoch)
            assertEquals(lease.plan.nodeId, get("ready").getValue("link_node_id").jsonPrimitive.content)
            val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
            require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
            val marker = java.io.File(java.io.File(app.noBackupFilesDir, name), "room-recording-prepared.json")
            if (marker.exists()) {
                val original = org.json.JSONObject(marker.readText())
                assertEquals(draft.id, original.getString("draft"))
                assertEquals(source.name, original.getString("source"))
                assertEquals(draft.sealed.hash, original.getString("encryptedSha256"))
                assertEquals(draft.storageOrigin, original.getString("origin"))
            } else marker.writeText(org.json.JSONObject().put("draft", draft.id).put("source", source.name)
                .put("encryptedSha256", draft.sealed.hash).put("origin", draft.storageOrigin).toString())
            put("recording-room-prepared-verify", buildJsonObject {
                put("originalExportAndUploadedDraftSurvivedProcessRestart", true)
                put("originalUploadReceiptReady", true); put("originalNodeLeaseUnchanged", true)
                put("sourcePathResolvedThroughExportOwner", true); put("uploadRepeated", false)
                put("recordingQueued", false); put("recordingSendQualified", false)
            })
        } finally { room.roomKey.fill(0) }
    }

    /** Offline diagnosis of a read-only snapshot from this fixture's own
     * node DB. These counts are not SDK ingress or delivery proof. */
    private fun inspectOriginalRecordingStoredDrops() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        try {
            val saved = requireNotNull(app.savedRooms.get(room.roomId))
            val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
            require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
            val directory = java.io.File(app.noBackupFilesDir, name)
            val expectedHash = org.json.JSONObject(java.io.File(directory,
                "room-recording-prepared.json").readText()).getString("encryptedSha256")
            val wraps = Json.parseToJsonElement(java.io.File(directory,
                "owned-node-drop-snapshot.json").readText()).jsonArray
            val ikm = DeadDrop.roomIkm(room.roomKey)
            val keys = (lease.plan.startEpoch until minOf(lease.plan.endEpoch, epochSeconds() / 3600 + 1))
                .flatMap { epoch -> (lease.plan.counterLo until lease.plan.counterHi).map { counter ->
                    DeadDrop.deriveDropKey(ikm, epoch, saved.participant, counter)
                } }.associateBy { it.publicKey }
            var matchingKeys = 0; var opened = 0; var decodedChats = 0; var matchingAttachments = 0
            for (value in wraps) {
                val wrap = dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(value)
                val key = keys[RoomDrops.tagOf(wrap)] ?: continue
                matchingKeys++
                val event = RoomDrops.openRoomDrop(wrap, key.privateKey) ?: continue
                opened++
                val chat = dev.forgesworn.kithmoot.session.decodeChatEvent(event, room.roomId,
                    room.roomKey, epochSeconds(), saved.policy) ?: continue
                decodedChats++
                if (chat.attachments.any { it.sha256 == expectedHash }) matchingAttachments++
            }
            put("recording-room-stored-drop-inspect", buildJsonObject {
                put("offlineSnapshotOnly", true); put("storedWraps", wraps.size)
                put("originalLeaseKeyMatches", matchingKeys); put("verifiedInnerEvents", opened)
                put("decodedOriginalRoomChats", decodedChats); put("originalAttachmentMatches", matchingAttachments)
                put("sdkIngressProved", false); put("sendInvoked", false); put("queueInvoked", false)
            })
        } finally { room.roomKey.fill(0) }
    }

    /** Reopen the original successful recording without Add, Upload or Send. */
    private fun verifyConfirmedRecordingRoomModelRestart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        assertTrue(get("journey/recording-room-send").getValue("actualSendControlPassed").jsonPrimitive.boolean)
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        val saved = requireNotNull(app.savedRooms.get(room.roomId))
        val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
        val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
        require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        val prepared = org.json.JSONObject(java.io.File(java.io.File(app.noBackupFilesDir, name),
            "room-recording-prepared.json").readText())
        val source = app.recordings.selectedExport(prepared.getString("source"))
        val hash = prepared.getString("encryptedSha256")
        assertTrue(source.isFile)
        assertEquals(room.roomId, app.recordings.details(source).origin?.room)
        assertTrue(app.recordingShareDrafts.list(room.roomId).isEmpty())
        assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
        lateinit var model: dev.forgesworn.kithmoot.ui.RoomViewModel
        val ui = dev.forgesworn.kithmoot.storage.RecoveryUi(useSwipeFallback = false, freshNodes = true)
        val scenario = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { owner ->
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        dev.forgesworn.kithmoot.ui.RoomViewModel(app, chatOnly = true,
                            recordingNodeQualification = true) as T
                })["recording-node-product", dev.forgesworn.kithmoot.ui.RoomViewModel::class.java]
                owner.setContent { dev.forgesworn.kithmoot.ui.theme.KithMootTheme {
                    dev.forgesworn.kithmoot.ui.KithMootApp(model)
                } }
                model.reopenRoom(room.roomId)
            }
            ui.await("original successful recording room reopened", timeoutMs = 120_000) {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.ROOM && !model.start.value.busy
            }
            ui.await("original local export still offers Save after Send and restart") {
                ui.hasText("Recording ready") && ui.enabled("Save")
            }
            ui.click("Later")
            ui.await("authenticated original recording attachment recovered from node history", timeoutMs = 120_000) {
                model.room.value.chat.any { message -> message.attachments.any { it.sha256 == hash } }
            }
            assertTrue(source.isFile)
            assertTrue(app.recordingShareDrafts.list(room.roomId).isEmpty())
            assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
            assertEquals(lease.plan, app.cadenceLeases.all(room.roomId, DEVICE).single().plan)
            put("recording-room-confirmed-restart", buildJsonObject {
                put("originalMessageRecoveredFromNodeHistory", true)
                put("localSaveOffered", true); put("originalSourceRetained", true)
                put("completedDraftStayedFinished", true); put("originalLeasePlanUnchanged", true)
                put("sendInvoked", false); put("uploadInvoked", false); put("replacementPairing", false)
            })
        } finally { scenario.close(); room.roomKey.fill(0) }
    }

    /** Read durable original custody after process death, without publication.
     * The driver must first observe successful product departure and stop the
     * owned disposable app process. This action does not reopen the room. */
    private fun verifyWithdrawnRecordingRoomModelRestart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        assertTrue(get("journey/recording-room-send-leave")
            .getValue("authenticatedOriginalNodeWithdrawal").jsonPrimitive.boolean)
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        try {
            val saved = requireNotNull(app.savedRooms.get(room.roomId))
            assertEquals(LocalSigner(PERSONA_SECRET.hexToBytes()).pubkey, saved.participant)
            assertEquals(DEVICE, saved.devicePubkey)
            val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            val staged = get("journey/recording-room-stage")
            assertEquals(staged.getValue("leaseId").jsonPrimitive.content, lease.plan.leaseId)
            assertEquals(staged.getValue("generation").jsonPrimitive.content.toLong(), lease.plan.generation)
            assertEquals(staged.getValue("startEpoch").jsonPrimitive.content.toLong(), lease.plan.startEpoch)
            assertEquals(staged.getValue("endEpoch").jsonPrimitive.content.toLong(), lease.plan.endEpoch)
            assertEquals(get("ready").getValue("link_node_id").jsonPrimitive.content, lease.plan.nodeId)
            assertEquals("withdrawn", lease.receipt?.code)
            val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
            require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
            val directory = java.io.File(app.noBackupFilesDir, name)
            val prepared = org.json.JSONObject(java.io.File(directory, "room-recording-prepared.json").readText())
            val outcome = org.json.JSONObject(java.io.File(directory, "room-recording-leave-outcome.json").readText())
            assertEquals("original-node-withdrawn-after-room-departure", outcome.getString("state"))
            val draft = app.recordingShareDrafts.list(room.roomId).single()
            assertEquals(prepared.getString("draft"), draft.id)
            assertEquals(prepared.getString("source"), draft.sourceName)
            assertEquals(prepared.getString("encryptedSha256"), draft.sealed.hash)
            assertEquals(prepared.getString("origin"), draft.storageOrigin)
            assertEquals(draft.sealed.hash, requireNotNull(draft.uploaded).sha256)
            assertTrue(draft.sealed.file.isFile)
            val source = app.recordings.selectedExport(draft.sourceName)
            assertTrue(source.isFile && source.length() > 0)
            assertEquals(room.roomId, app.recordings.details(source).origin?.room)
            val entriesField = app.recordingNodeHandoffs.javaClass.getDeclaredField("entries").apply {
                isAccessible = true
            }
            val handoff = synchronized(app.recordingNodeHandoffs) {
                @Suppress("UNCHECKED_CAST")
                val entries = entriesField.get(app.recordingNodeHandoffs) as
                    List<dev.forgesworn.kithmoot.cadence.RecordingNodeHandoff>
                entries.single()
            }
            assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN, handoff.state)
            assertEquals(draft.id, handoff.binding.draftId)
            assertEquals(saved.participant, handoff.binding.accountPubkey)
            assertEquals(lease.plan.nodeId, handoff.binding.nodeId)
            assertEquals(lease.plan.requestBody, handoff.binding.leaseBody)
            assertEquals(requireNotNull(draft.preparedSend).pending.event, handoff.binding.event)
            val withdrawal = requireNotNull(handoff.withdrawal)
            assertTrue(withdrawal.requestId.isNotBlank() && withdrawal.path.isNotBlank() && withdrawal.body.isNotBlank())
            assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
            put("recording-room-withdrawn-restart", buildJsonObject {
                put("originalWithdrawnJournalRecovered", true)
                put("originalPreparedEventAndWithdrawalRetained", true)
                put("originalSourceAndUploadedDraftRetained", true)
                put("originalLeasePlanAndWithdrawalReceiptRecovered", true)
                put("roomReopened", false); put("sendInvoked", false); put("uploadInvoked", false)
                put("queueInvoked", false); put("replacementPairing", false); put("clockCompressed", false)
            })
        } finally { room.roomKey.fill(0) }
    }

    /** An explicit product cancellation retry after the driver's verified
     * process death. The original node already withdrew; replay only its ACK. */
    private fun retryOriginalRecordingCancellationAfterRestart() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val proof = get("journey/recording-room-leave-lost-reply")
        assertTrue(proof.getValue("realNodeWithdrew").jsonPrimitive.boolean)
        assertTrue(proof.getValue("applicationAckWithheld").jsonPrimitive.boolean)
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        val saved = requireNotNull(app.savedRooms.get(room.roomId))
        val journal = app.recordingNodeHandoffs
        val original = journal.pending(saved.participant).single()
        assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING, original.state)
        assertEquals(proof.getValue("draftId").jsonPrimitive.content, original.binding.draftId)
        assertEquals(proof.getValue("eventId").jsonPrimitive.content, original.binding.event.id)
        val withdrawal = requireNotNull(original.withdrawal)
        assertEquals(proof.getValue("withdrawalRequestId").jsonPrimitive.content, withdrawal.requestId)
        assertEquals(proof.getValue("withdrawalPath").jsonPrimitive.content, withdrawal.path)
        assertEquals(proof.getValue("withdrawalBody").jsonPrimitive.content, withdrawal.body)
        assertFalse(journal.withdrawalStillAllowed(original.binding, withdrawal))
        val draft = app.recordingShareDrafts.selected(original.binding.draftId, room.roomId)
        assertEquals(original.binding.event, requireNotNull(draft.preparedSend).pending.event)
        val lease = app.cadenceLeases.all(room.roomId, DEVICE).single {
            it.plan.requestBody == original.binding.leaseBody
        }
        val source = app.recordings.selectedExport(draft.sourceName)
        assertTrue(source.isFile && draft.sealed.file.isFile)
        val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
        require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        val outcome = java.io.File(java.io.File(app.noBackupFilesDir, name), "room-recording-cancellation-retry-outcome.json")
        check(!outcome.exists()) { "The original cancellation retry is retained; never repeat automatically" }
        val ui = dev.forgesworn.kithmoot.storage.RecoveryUi(useSwipeFallback = false, freshNodes = true)
        lateinit var model: dev.forgesworn.kithmoot.ui.RoomViewModel
        val scenario = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { owner ->
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        dev.forgesworn.kithmoot.ui.RoomViewModel(app, chatOnly = true,
                            recordingNodeQualification = true) as T
                })["recording-cancellation-product", dev.forgesworn.kithmoot.ui.RoomViewModel::class.java]
                owner.setContent { dev.forgesworn.kithmoot.ui.theme.KithMootTheme {
                    dev.forgesworn.kithmoot.ui.KithMootApp(model)
                } }
                model.reopenRoom(room.roomId)
            }
            ui.await("original room reopened without cancellation replay", timeoutMs = 120_000) {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.ROOM && !model.start.value.busy
            }
            if (ui.hasText("Recording ready")) ui.click("Later")
            ui.await("explicit original cancellation retry", timeoutMs = 120_000) { ui.enabled("Retry cancellation") }
            assertEquals(original, journal.pending(saved.participant).single())
            assertFalse(ui.hasText("Retry Send")); assertFalse(ui.hasText("Send recording"))
            outcome.writeText(org.json.JSONObject().put("state", "one-explicit-original-cancellation-retry")
                .put("automaticRetry", false).toString())
            ui.click("Retry cancellation")
            ui.await("original node replay confirms cancellation", timeoutMs = 180_000) {
                journal.states(saved.participant, room.roomId)[draft.id] ==
                    dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN && !model.room.value.mediaBusy
            }
            val entriesField = journal.javaClass.getDeclaredField("entries").apply { isAccessible = true }
            val terminal = synchronized(journal) {
                @Suppress("UNCHECKED_CAST")
                val entries = entriesField.get(journal) as List<dev.forgesworn.kithmoot.cadence.RecordingNodeHandoff>
                entries.single()
            }
            assertEquals(original.binding, terminal.binding); assertEquals(withdrawal, terminal.withdrawal)
            assertEquals(draft, app.recordingShareDrafts.selected(draft.id, room.roomId))
            assertTrue(source.isFile)
            val confirmedLease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            assertEquals(lease.plan, confirmedLease.plan); assertEquals("withdrawn", confirmedLease.receipt?.code)
            assertFalse(ui.hasText("Retry Send")); assertFalse(ui.hasText("Send recording"))
            outcome.writeText(org.json.JSONObject().put("state", "original-cancellation-confirmed-through-product-control")
                .put("automaticRetry", false).toString())
            put("recording-room-cancellation-restart", buildJsonObject {
                put("originalCancellationRecoveredWithoutAutomaticReplay", true)
                put("actualRetryCancellationControlInvoked", true)
                put("authenticatedOriginalNodeReplayConfirmed", true)
                put("originalBindingAndWithdrawalUnchanged", true)
                put("originalSourceAndUploadedDraftRetained", true)
                put("sendInvoked", false); put("uploadInvoked", false); put("queueInvoked", false)
                put("leaseExtended", false); put("clockCompressed", false); put("automaticRetry", false)
            })
        } finally { scenario.close(); room.roomKey.fill(0) }
    }

    /** Read-only recovery of the original attempt, never another Send. */
    private fun observeOriginalRecordingRoomModelSend() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        try {
            val saved = requireNotNull(app.savedRooms.get(room.roomId))
            val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
            val staged = get("journey/recording-room-stage")
            assertEquals(staged.getValue("leaseId").jsonPrimitive.content, lease.plan.leaseId)
            assertEquals(staged.getValue("generation").jsonPrimitive.content.toLong(), lease.plan.generation)
            assertEquals(staged.getValue("startEpoch").jsonPrimitive.content.toLong(), lease.plan.startEpoch)
            assertEquals(staged.getValue("endEpoch").jsonPrimitive.content.toLong(), lease.plan.endEpoch)
            val draft = app.recordingShareDrafts.list(room.roomId).single()
            val source = app.recordings.selectedExport(draft.sourceName)
            val custody = app.recordingNodeHandoffs.pending(saved.participant)
            val observation = buildJsonObject {
                put("uploadedDraftRetained", draft.uploaded != null)
                put("sourceRetained", source.isFile)
                put("signedMessagePrepared", draft.preparedSend != null)
                put("handoffCount", custody.size)
                put("handoffStates", kotlinx.serialization.json.JsonArray(custody.map {
                    kotlinx.serialization.json.JsonPrimitive(it.state.name)
                }))
                put("originalLeasePlanUnchanged", true)
                put("sendInvoked", false); put("queueInvoked", false)
                put("deadlineExtended", false)
                custody.singleOrNull()?.let {
                    put("publicationExpired", epochSeconds() >= it.binding.publishBefore)
                    put("bindingMatchesPreparedMessage", draft.preparedSend?.pending?.event == it.binding.event)
                }
            }
            put("recording-room-send-observe", observation)
        } finally { room.roomKey.fill(0) }
    }

    /** One explicit product Send, using the already uploaded original draft.
     * No queue retry, replacement upload, lease change or injected relay proof. */
    private fun sendPreparedRecordingRoomModelDraft(send: Boolean = true, afterVerifiedNoop: Boolean = false,
        leaveAfterQueue: Boolean = false, loseWithdrawalReply: Boolean = false, rekeyAfterQueue: Boolean = false) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(ByteArray(32) { 61 })
        val saved = requireNotNull(app.savedRooms.get(room.roomId))
        val lease = app.cadenceLeases.all(room.roomId, DEVICE).single()
        val epoch = epochSeconds() / 3600
        check(epoch >= lease.plan.startEpoch && epoch < lease.plan.endEpoch) { "Original lease must be active" }
        val staged = get("journey/recording-room-stage")
        assertEquals(staged.getValue("leaseId").jsonPrimitive.content, lease.plan.leaseId)
        assertEquals(staged.getValue("generation").jsonPrimitive.content.toLong(), lease.plan.generation)
        assertEquals(staged.getValue("startEpoch").jsonPrimitive.content.toLong(), lease.plan.startEpoch)
        assertEquals(staged.getValue("endEpoch").jsonPrimitive.content.toLong(), lease.plan.endEpoch)
        assertEquals(get("ready").getValue("link_node_id").jsonPrimitive.content, lease.plan.nodeId)
        val original = app.recordingShareDrafts.list(room.roomId).single()
        check(original.preparedSend == null && original.uploaded != null)
        check(app.recordingNodeHandoffs.pending(saved.participant).isEmpty()) {
            "An original attempt is retained; never queue it again automatically"
        }
        val source = app.recordings.selectedExport(original.sourceName)
        check(source.isFile)
        val name = requireNotNull(arguments.getString("recordingNetworkFixture"))
        require(name.matches(Regex("recording-ui-network-[0-9a-f]{12}")))
        val fixture = java.io.File(app.noBackupFilesDir, name)
        if (afterVerifiedNoop) {
            val observation = get("journey/recording-room-send-observe")
            assertFalse(observation.getValue("signedMessagePrepared").jsonPrimitive.boolean)
            assertEquals(0, observation.getValue("handoffCount").jsonPrimitive.content.toInt())
            assertTrue(observation.getValue("originalLeasePlanUnchanged").jsonPrimitive.boolean)
            check(java.io.File(fixture, "room-recording-send-outcome.json").exists()) {
                "The earlier UI attempt must remain retained"
            }
        }
        val outcome = java.io.File(fixture, when {
            rekeyAfterQueue -> "room-recording-rekey-outcome.json"
            loseWithdrawalReply -> "room-recording-lost-withdrawal-outcome.json"
            leaveAfterQueue -> "room-recording-leave-outcome.json"
            afterVerifiedNoop -> "room-recording-first-publication-outcome.json"
            else -> "room-recording-send-outcome.json"
        })
        if (send) check(!outcome.exists()) { "The original Send observation is retained" }
        val realWithdrawalReply = CompletableFuture<dev.forgesworn.kithmoot.relay.LinkJsonResponse>()
        val lossClient = if (loseWithdrawalReply) dev.forgesworn.kithmoot.cadence.CadenceClient(
            dev.forgesworn.kithmoot.relay.LinkJsonTransport { request ->
                check(request.method == "POST" && request.path.endsWith("/withdraw"))
                // Complete the real SDK/node exchange; withhold only the
                // application's ACK. No node success is fabricated here.
                app.linkEngine.request(request).whenComplete { response, failure ->
                    if (failure != null) realWithdrawalReply.completeExceptionally(failure)
                    else realWithdrawalReply.complete(response)
                }.thenCompose { CompletableFuture<dev.forgesworn.kithmoot.relay.LinkJsonResponse>() }
            }, app.linkConsents) else null
        lateinit var model: dev.forgesworn.kithmoot.ui.RoomViewModel
        val ui = dev.forgesworn.kithmoot.storage.RecoveryUi(useSwipeFallback = false, freshNodes = true)
        val scenario = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { owner ->
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        dev.forgesworn.kithmoot.ui.RoomViewModel(app, chatOnly = true,
                            recordingNodeQualification = true, recordingWithdrawalClient = lossClient) as T
                })["recording-node-product", dev.forgesworn.kithmoot.ui.RoomViewModel::class.java]
                owner.setContent { dev.forgesworn.kithmoot.ui.theme.KithMootTheme {
                    dev.forgesworn.kithmoot.ui.KithMootApp(model)
                } }
                model.reopenRoom(room.roomId)
            }
            ui.await("original recording room reopened", timeoutMs = 120_000) {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.ROOM && !model.start.value.busy
            }
            // A completed local export is deliberately offered again after
            // restart. Later closes that modal without discarding its source
            // or adding/uploading a second draft.
            if (ui.hasText("Recording ready")) ui.click("Later")
            ui.await("explicit original Send control") { ui.hasText("Send recording") }
            val scheduleBusyAtFirstVisible = model.room.value.cadence?.busy == true
            val sendEnabledAtFirstVisible = ui.enabled("Send recording")
            ui.await("original quiet schedule ready for recording Send", timeoutMs = 120_000) {
                model.room.value.cadence?.busy != true && ui.enabled("Send recording")
            }
            if (!send) {
                assertEquals(original, app.recordingShareDrafts.selected(original.id, room.roomId))
                assertTrue(app.recordingNodeHandoffs.pending(saved.participant).isEmpty())
                assertTrue(source.isFile)
                val refreshedLease = app.cadenceLeases.all(room.roomId, DEVICE).single()
                assertEquals(lease.plan, refreshedLease.plan)
                assertEquals(lease.ownership, refreshedLease.ownership)
                put("recording-room-send-controls", buildJsonObject {
                    put("restoredExportModalDeferred", true); put("originalSendControlVisible", true)
                    put("originalUploadedDraftUnchanged", true); put("originalSourceRetained", true)
                    put("sendInvoked", false); put("replacementUpload", false); put("clockCompressed", false)
                    put("scheduleBusyAtFirstVisible", scheduleBusyAtFirstVisible)
                    put("sendEnabledAtFirstVisible", sendEnabledAtFirstVisible)
                })
                return
            }
            outcome.writeText(org.json.JSONObject().put("state", "one-explicit-original-send")
                .put("draft", original.id).put("automaticRetry", false).toString())
            ui.click("Send recording")
            if (leaveAfterQueue || rekeyAfterQueue) {
                val entriesField = app.recordingNodeHandoffs.javaClass.getDeclaredField("entries").apply {
                    isAccessible = true
                }
                fun originalHandoff(): dev.forgesworn.kithmoot.cadence.RecordingNodeHandoff? {
                    @Suppress("UNCHECKED_CAST")
                    val entries = synchronized(app.recordingNodeHandoffs) {
                        entriesField.get(app.recordingNodeHandoffs) as
                            List<dev.forgesworn.kithmoot.cadence.RecordingNodeHandoff>
                    }
                    return entries.singleOrNull { it.binding.draftId == original.id }
                }
                ui.await("accepted original custody before departure", timeoutMs = 180_000) {
                    originalHandoff()?.state in setOf(
                        dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED,
                        dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CONFIRMED,
                    ) || model.room.value.chatSendError != null
                }
                val queued = requireNotNull(originalHandoff())
                // An already delivered original cannot qualify withdrawal.
                // Retain that attempt; never substitute another Send here.
                assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED, queued.state)
                if (rekeyAfterQueue) {
                    check(saved.nativeAuthority == null && saved.authority != null && saved.policy?.quiet == true)
                    val host = requireNotNull(saved.host(epochSeconds()))
                    val current = dev.forgesworn.kithmoot.protocol.EpochKeys(0, room.roomId, room.roomKey)
                    val next = dev.forgesworn.kithmoot.protocol.RoomEpoch(1, ByteArray(32) { 62 })
                    val notice = try {
                        dev.forgesworn.kithmoot.protocol.encodeRekeyEvent(room.roomId, host.inviterSecretKey,
                            current, next, listOf(DEVICE), emptyList(), epochSeconds(),
                            commit = true, members = listOf(saved.participant), scheduled = true)
                    } finally {
                        host.inviterSecretKey.fill(0); host.invitation.bearer.fill(0)
                        current.key.fill(0); next.secret.fill(0)
                    }
                    outcome.writeText(org.json.JSONObject().put("state", "publishing-one-authority-rekey")
                        .put("originalEvent", queued.binding.event.id).put("rekeyEvent", notice.id).toString())
                    val consent = app.linkConsents.all().single {
                        it.accountPubkey == saved.participant && it.roomId == room.roomId &&
                            it.state == LinkConsentState.ACTIVE
                    }
                    val publisherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                    val publisher = dev.forgesworn.kithmoot.relay.RelayPool(listOf(consent.canonicalUrl),
                        dev.forgesworn.kithmoot.relay.HybridRelaySockets(
                            dev.forgesworn.kithmoot.relay.OkHttpRelaySockets(), app.linkEngine,
                            dev.forgesworn.kithmoot.relay.ActiveLinkRoute { url ->
                                app.linkConsents.activeRoute(saved.participant, room.roomId, url)
                                    ?.takeIf { url == consent.canonicalUrl && it == consent.routeId }
                            }), publisherScope,
                        circle = { setOf(consent.canonicalUrl) },
                        authenticators = dev.forgesworn.kithmoot.relay.RelayAuthenticatorProvider { url ->
                            if (url != consent.canonicalUrl) null else object : dev.forgesworn.kithmoot.relay.RelayAuthenticator {
                                override val pubkey = saved.participant
                                override suspend fun sign(url: String, challenge: String): dev.forgesworn.kithmoot.protocol.NostrEvent {
                                    val key = PERSONA_SECRET.hexToBytes()
                                    try {
                                        check(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(key) == saved.participant)
                                        return Events.sign(key, 22242, epochSeconds(),
                                            listOf(listOf("relay", url), listOf("challenge", challenge)), "")
                                    } finally { key.fill(0) }
                                }
                            }
                        })
                    try {
                        publisher.start()
                        assertTrue("Original node accepts the authenticated authority publication",
                            runBlocking { publisher.publishConfirmed(notice, 120_000) })
                        ui.await("native client authenticates epoch one and withdraws original custody", timeoutMs = 180_000) {
                            app.roomEpochs.get(room.roomId)?.currentEpoch == 1 &&
                                originalHandoff()?.state == dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN
                        }
                        val withdrawn = requireNotNull(originalHandoff())
                        assertEquals(queued.binding, withdrawn.binding)
                        val retained = requireNotNull(app.recordingShareDrafts.selected(original.id, room.roomId))
                        assertEquals(queued.binding.event, requireNotNull(retained.preparedSend).pending.event)
                        assertEquals(original.sealed, retained.sealed); assertEquals(original.uploaded, retained.uploaded)
                        assertTrue(source.isFile)
                        assertEquals(lease.plan, app.cadenceLeases.all(room.roomId, DEVICE)
                            .single { it.plan.leaseId == lease.plan.leaseId }.plan)
                        outcome.writeText(org.json.JSONObject().put("state", "original-withdrawal-after-authenticated-rekey")
                            .put("automaticRetry", false).toString())
                        put("recording-room-send-rekey", buildJsonObject {
                            put("actualSendControlInvoked", true); put("acceptedOriginalCustody", true)
                            put("authorityEventAcceptedByOriginalNode", true); put("nativeClientAuthenticatedEpoch", 1)
                            put("authenticatedOriginalNodeWithdrawal", true); put("originalBindingUnchanged", true)
                            put("originalLeasePlanUnchanged", true); put("originalSourceAndUploadedDraftRetained", true)
                            put("replacementUpload", false); put("automaticRetry", false); put("clockCompressed", false)
                            put("nativeKeeperQuietRoom", false)
                        })
                    } finally { publisher.stop(); publisherScope.cancel() }
                    return
                }
                outcome.writeText(org.json.JSONObject().put("state", "leaving-after-original-queued-custody")
                    .put("automaticRetry", false).toString())
                scenario.onActivity { model.leave() }
                ui.await("product room departure completes", timeoutMs = 120_000) {
                    model.stage.value == dev.forgesworn.kithmoot.ui.Stage.START && !model.start.value.busy
                }
                if (loseWithdrawalReply) {
                    val response = realWithdrawalReply.get(120, TimeUnit.SECONDS)
                    assertEquals("withdrawn", BoxCadence.parseReceipt(response.status, response.body).code)
                    val uncertain = requireNotNull(originalHandoff())
                    assertEquals(queued.binding, uncertain.binding)
                    assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING,
                        uncertain.state)
                    val withdrawal = requireNotNull(uncertain.withdrawal)
                    val retained = requireNotNull(app.recordingShareDrafts.selected(original.id, room.roomId))
                    assertEquals(queued.binding.event, requireNotNull(retained.preparedSend).pending.event)
                    assertEquals(original.sealed, retained.sealed); assertEquals(original.uploaded, retained.uploaded)
                    assertTrue(source.isFile)
                    assertEquals(lease.plan, app.cadenceLeases.all(room.roomId, DEVICE).single().plan)
                    outcome.writeText(org.json.JSONObject().put("state", "real-node-withdrawn-app-ack-withheld")
                        .put("automaticRetry", false).toString())
                    put("recording-room-leave-lost-reply", buildJsonObject {
                        put("actualSendControlInvoked", true); put("acceptedOriginalCustody", true)
                        put("roomDepartureCompleted", true); put("realNodeWithdrew", true)
                        put("applicationAckWithheld", true); put("cancelPendingRetained", true)
                        put("draftId", original.id); put("eventId", queued.binding.event.id)
                        put("withdrawalRequestId", withdrawal.requestId); put("withdrawalPath", withdrawal.path)
                        put("withdrawalBody", withdrawal.body)
                        put("replacementUpload", false); put("automaticRetry", false); put("clockCompressed", false)
                    })
                    return
                }
                ui.await("original node confirms withdrawal after room departure", timeoutMs = 180_000) {
                    originalHandoff()?.state == dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.WITHDRAWN
                }
                val withdrawn = requireNotNull(originalHandoff())
                assertEquals(queued.binding, withdrawn.binding)
                val retained = requireNotNull(app.recordingShareDrafts.selected(original.id, room.roomId))
                assertEquals(queued.binding.event, requireNotNull(retained.preparedSend).pending.event)
                assertEquals(original.sealed, retained.sealed)
                assertEquals(original.uploaded, retained.uploaded)
                assertTrue(source.isFile)
                val confirmedLease = app.cadenceLeases.all(room.roomId, DEVICE).single()
                assertEquals(lease.plan, confirmedLease.plan)
                assertEquals("withdrawn", confirmedLease.receipt?.code)
                outcome.writeText(org.json.JSONObject().put("state", "original-node-withdrawn-after-room-departure")
                    .put("sourceRetained", true).put("draftRetained", true).put("automaticRetry", false).toString())
                put("recording-room-send-leave", buildJsonObject {
                    put("actualSendControlInvoked", true); put("acceptedOriginalCustody", true)
                    put("roomDepartureCompleted", true); put("authenticatedOriginalNodeWithdrawal", true)
                    put("originalSourceAndDraftRetained", true); put("originalLeasePlanUnchanged", true)
                    put("replacementUpload", false); put("automaticRetry", false); put("clockCompressed", false)
                })
                return
            }
            ui.await("original Send finished or retained uncertainty", timeoutMs = 720_000) {
                !model.room.value.mediaBusy && !model.room.value.chatSending &&
                    (app.recordingShareDrafts.list(room.roomId).none { it.id == original.id } ||
                        model.room.value.chatSendError != null)
            }
            val pending = app.recordingShareDrafts.list(room.roomId).singleOrNull { it.id == original.id }
            val custody = app.recordingNodeHandoffs.pending(saved.participant).singleOrNull()
            outcome.writeText(org.json.JSONObject().put("draftRetained", pending != null)
                .put("preparedEvent", pending?.preparedSend?.pending?.event?.id)
                .put("custodyState", custody?.state?.name).put("error", model.room.value.chatSendError)
                .put("sourceRetained", source.isFile).put("automaticRetry", false).toString())
            assertEquals("Actual product Send must finish through authenticated ingress", null, pending)
            assertEquals(null, model.room.value.chatSendError)
            // An empty pending list also describes a send that never queued.
            // Require the original durable handoff's terminal receipt before
            // accepting local rendering or draft removal as product success.
            val entriesField = app.recordingNodeHandoffs.javaClass.getDeclaredField("entries").apply {
                isAccessible = true
            }
            @Suppress("UNCHECKED_CAST")
            val entries = entriesField.get(app.recordingNodeHandoffs) as
                List<dev.forgesworn.kithmoot.cadence.RecordingNodeHandoff>
            val confirmed = entries.single { it.binding.draftId == original.id }
            assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CONFIRMED, confirmed.state)
            assertEquals(lease.plan.nodeId, confirmed.binding.nodeId)
            assertTrue(model.room.value.chat.any { it.attachments.any { attachment ->
                attachment.sha256 == original.sealed.hash
            } })
            assertTrue(source.isFile)
            // Accepted queue custody may update its receipt; the original
            // plan, node, generation and interval must remain unchanged.
            assertEquals(lease.plan, app.cadenceLeases.all(room.roomId, DEVICE).single().plan)
            put("recording-room-send", buildJsonObject {
                put("actualSendControlPassed", true); put("originalSourceRetained", true)
                put("replacementUpload", false); put("automaticRetry", false); put("clockCompressed", false)
            })
        } finally { scenario.close(); room.roomKey.fill(0) }
    }

    /** Product-model authority setup for the separate derived-room fixture.
     * This stages real cadence through RoomViewModel; it does not claim Send. */
    private fun stageRecordingRoomModelLease(withAuthority: Boolean = false) {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        check(app.savedRooms.list().isEmpty() && app.accounts.load() == null) {
            "Product-room qualification refuses retained rooms or accounts"
        }
        val now = epochSeconds()
        val secret = ByteArray(32) { 61 }
        val room = dev.forgesworn.kithmoot.protocol.deriveRoom(secret)
        val ready = get("ready")
        assertEquals("emulated", ready.getValue("evidence").jsonPrimitive.content)
        assertEquals(room.roomId, ready.getValue("fixture_room").jsonPrimitive.content)
        assertFalse(ready.getValue("lab_slot_override_built").jsonPrimitive.boolean)
        assertTrue(ready.getValue("worker").jsonObject.getValue("running").jsonPrimitive.boolean)
        val grantEvents = ready.getValue("fixture_grant_plan").jsonArray.map {
            dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(it)
        }
        assertEquals(2, grantEvents.size)
        val plan = dev.forgesworn.kithmoot.relay.CircleGrantPlan(grantEvents[0], grantEvents[1])
        val who = PrimaryIdentity.create(room.roomId, now + 43_200, now,
            PERSONA_SECRET.hexToBytes(), DEVICE_SECRET.hexToBytes())
        assertEquals(room.roomId, plan.active.tagValue("d"))
        assertEquals(who.participant, plan.active.tagValue("p"))
        assertEquals(who.devicePubkey, plan.active.tagValue("device"))
        assertEquals(GRANT_ID, plan.active.tagValue("grant"))
        assertEquals(ready.getValue("claimed_by").jsonPrimitive.content, plan.active.pubkey)
        if (withAuthority) {
            assertEquals("Authority publication requires this fixture's actual node owner",
                who.participant, ready.getValue("claimed_by").jsonPrimitive.content)
            assertTrue(ready.getValue("fixture_owner_persona").jsonPrimitive.boolean)
        }
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val node = ready.getValue("link_node_id").jsonPrimitive.content
        val canonical = "ws://$node/events"
        val relays = fixtureRelays()
        app.linkConsents.put(LinkConsent(who.participant, room.roomId, plan.active.pubkey,
            route.routeId, canonical, relays, LinkConsentState.ACTIVE, listOf(plan)))
        val policy = dev.forgesworn.kithmoot.protocol.RoomPolicy(
            dev.forgesworn.kithmoot.protocol.KindredTier.OPEN, members = listOf(who.participant), quiet = true)
        val host = if (withAuthority) dev.forgesworn.kithmoot.protocol.createRoomInvitation(persistent = true) else null
        val url = if (host == null)
            dev.forgesworn.kithmoot.protocol.encodeJoinUrl("https://kithmoot.app/j/", secret, listOf(canonical), policy)
            else dev.forgesworn.kithmoot.protocol.encodeInvitationUrl("https://kithmoot.app/j/", host.invitation,
                listOf(canonical), policy)
        val saved = dev.forgesworn.kithmoot.storage.SavedRoom.create(secret, who,
            url, listOf(canonical), "Synthetic node recording", now, host, host?.invitation?.canonicalInviter)
        try { app.savedRooms.saveNew(saved) } finally {
            host?.inviterSecretKey?.fill(0); host?.invitation?.bearer?.fill(0)
        }
        val ui = dev.forgesworn.kithmoot.storage.RecoveryUi(useSwipeFallback = false, freshNodes = true)
        lateinit var model: dev.forgesworn.kithmoot.ui.RoomViewModel
        val scenario = androidx.test.core.app.ActivityScenario.launch(androidx.activity.ComponentActivity::class.java)
        try {
            scenario.onActivity { owner ->
                model = androidx.lifecycle.ViewModelProvider(owner, object : androidx.lifecycle.ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST") override fun <T : androidx.lifecycle.ViewModel> create(type: Class<T>): T =
                        dev.forgesworn.kithmoot.ui.RoomViewModel(app, chatOnly = true, recordingNodeQualification = true) as T
                })["recording-node-product", dev.forgesworn.kithmoot.ui.RoomViewModel::class.java]
                owner.setContent { dev.forgesworn.kithmoot.ui.theme.KithMootTheme {
                    dev.forgesworn.kithmoot.ui.KithMootApp(model)
                } }
                model.refreshSavedRooms()
                model.reopenRoom(room.roomId)
            }
            ui.await("actual model opens its derived quiet room", timeoutMs = 120_000) {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.ROOM && !model.start.value.busy
            }
            assertEquals(room.roomId, model.room.value.roomId)
            assertEquals(who.participant, model.room.value.selfParticipant)
            assertEquals(who.devicePubkey, model.room.value.selfDevice)
            assertTrue(model.room.value.quiet)
            assertTrue(model.room.value.cadence?.eligible == true)
            scenario.onActivity { model.startCadence() }
            ui.await("actual model stages its original node lease", timeoutMs = 120_000) {
                !model.room.value.cadence!!.busy &&
                    app.cadenceLeases.all(room.roomId, who.devicePubkey).any { it.ownership == CadenceOwnership.BOX_OWNED }
            }
            val lease = app.cadenceLeases.all(room.roomId, who.devicePubkey).single()
            assertEquals(node, lease.plan.nodeId)
            assertEquals(room.roomId, lease.plan.room)
            assertEquals(room.roomId, lease.plan.trafficRoom)
            assertEquals("staged", lease.receipt?.code)
            assertTrue(app.recordingNodeHandoffs.pending(who.participant).isEmpty())
            put("recording-room-stage", buildJsonObject {
                put("leaseId", lease.plan.leaseId); put("generation", lease.plan.generation)
                put("startEpoch", lease.plan.startEpoch); put("endEpoch", lease.plan.endEpoch)
                put("trafficRoom", room.roomId); put("actualRoomViewModelStageAccepted", true)
                put("recordingQueued", false); put("recordingSendQualified", false); put("clockCompressed", false)
                put("authorityBoundQuietRoom", withAuthority)
            })
            scenario.onActivity { model.leave() }
            ui.await("product model closed while original node schedule stays retained") {
                model.stage.value == dev.forgesworn.kithmoot.ui.Stage.START && !model.start.value.busy
            }
            assertEquals(lease, app.cadenceLeases.all(room.roomId, who.devicePubkey).single())
        } finally {
            scenario.close()
            secret.fill(0); room.roomKey.fill(0); who.deviceSecretKey.fill(0)
        }
    }

    private fun stageRecordingWorkerLease() {
        val now = epochSeconds()
        val ready = get("ready")
        assertEquals("emulated", ready.getValue("evidence").jsonPrimitive.content)
        assertFalse("Worker qualification must keep the protocol clock", ready.getValue("lab_slot_override_built").jsonPrimitive.boolean)
        assertTrue("Separate worker must be running", ready.getValue("worker").jsonObject.getValue("running").jsonPrimitive.boolean)
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val node = ready.getValue("link_node_id").jsonPrimitive.content
        val identity = identity(now)
        val originalScope = scope(identity, node)
        app.linkConsents.put(consent(identity, node, route.routeId, ready.getValue("claimed_by").jsonPrimitive.content))
        val status = app.cadenceClient.status(identity.participant, originalScope, "81".repeat(16), identity, now)
            .get(120, TimeUnit.SECONDS).answer
        assertTrue(status.ready)
        val options = CadenceLeaseOptions(originalScope, "82".repeat(16), "83".repeat(16), 1, 0,
            status.currentEpoch, status.earliestStartEpoch, status.earliestStartEpoch + 1,
            ByteArray(32) { 1 }, fixtureRelays(), listOf("local"), now)
        val lease = app.cadenceClient.stage(identity.participant, options, identity, now, app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        assertEquals("staged", lease.receipt?.code)
        // Only stage authority now. The recording event and its original
        // deadline must be created when this lease actually becomes active.
        put("recording-worker-stage", buildJsonObject {
            put("leaseId", options.leaseId); put("generation", options.generation)
            put("startEpoch", options.startEpoch); put("endEpoch", options.endEpoch)
            put("trafficRoom", ROOM); put("realLinkStageAccepted", true)
            put("recordingQueued", false); put("relayDeliveryQualified", false)
            put("clockCompressed", false)
        })
    }

    private fun verifyRecordingWorkerLeadIn() {
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == "83".repeat(16) }
        check(epochSeconds() / 3600 < lease.plan.startEpoch) { "Lead-in check requires the original inactive lease" }
        val participant = dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(PERSONA_SECRET.hexToBytes())
        val before = app.recordingNodeHandoffs.pending(participant)
        assertTrue(before.isEmpty())
        val refusal = assertThrows(IllegalStateException::class.java) { observeRecordingWorkerDelivery() }
        assertEquals("The original recording worker lease is not active; no recording was queued", refusal.message)
        assertEquals(before, app.recordingNodeHandoffs.pending(participant))
        assertEquals(lease, app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == lease.plan.leaseId })
        val ready = get("ready")
        assertEquals(lease.plan.nodeId, ready.getValue("link_node_id").jsonPrimitive.content)
        assertTrue(ready.getValue("worker").jsonObject.getValue("running").jsonPrimitive.boolean)
        assertFalse(ready.getValue("lab_slot_override_built").jsonPrimitive.boolean)
        put("recording-worker-lead-in", buildJsonObject {
            put("inactiveLeaseRefusedBeforeQueue", true); put("journalUnchanged", true)
            put("originalLeaseRetained", true); put("separateWorkerRunning", true)
            put("clockCompressed", false); put("relayDeliveryQualified", false)
        })
    }

    private fun verifyExpiredRecordingWorkerRefusal() {
        val account = LocalSigner(PERSONA_SECRET.hexToBytes()).pubkey
        val before = app.recordingNodeHandoffs.pending(account)
        val original = before.single { it.binding.draftId == "00000000-0000-0000-0000-000000000083" }
        check(epochSeconds() >= original.binding.publishBefore)
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == "83".repeat(16) }
        val leaseExpired = epochSeconds() / 3600 >= lease.plan.endEpoch
        val refused = assertThrows(IllegalStateException::class.java) { observeRecordingWorkerDelivery(retained = true) }
        assertEquals(if (leaseExpired) "The original recording worker lease is not active; no recording was queued"
            else "Original publication expired; no retry", refused.message)
        assertEquals(before, app.recordingNodeHandoffs.pending(account))
        put("recording-worker-expired-refusal", buildJsonObject {
            put("expiredOriginalPublicationRefused", true); put("originalJournalUnchanged", true)
            put("originalLeaseAlsoExpired", leaseExpired)
            put("publicationDeadlineExtended", false); put("queueRetried", false)
        })
    }

    private fun verifyOriginalRecordingWorkerStatus() {
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == "83".repeat(16) }
        val body = Json.parseToJsonElement(lease.plan.requestBody).jsonObject
        val identity = PrimaryIdentity(LocalSigner(PERSONA_SECRET.hexToBytes()), DEVICE_SECRET.hexToBytes(),
            dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(body.getValue("credential")))
        assertEquals(lease.plan.nodeId, get("ready").getValue("link_node_id").jsonPrimitive.content)
        val before = app.recordingNodeHandoffs.pending(identity.participant)
        val answer = app.cadenceClient.status(identity.participant, scope(identity, lease.plan.nodeId),
            "89".repeat(16), identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        assertTrue(answer.answer.ready)
        assertEquals(before, app.recordingNodeHandoffs.pending(identity.participant))
        assertEquals(lease, app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == lease.plan.leaseId })
        put("recording-worker-original-status", buildJsonObject {
            put("originalRouteStatusAccepted", true); put("originalLeaseUnchanged", true)
            put("originalJournalUnchanged", true); put("queueRetried", false); put("repaired", false)
        })
    }

    /** Diagnose the original route without granting another queue attempt.
     * Invalid authentication must be refused before custody can change. */
    private fun verifyOriginalGuardedTransportRefusal() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) { "Disposable emulator only" }
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == "83".repeat(16) }
        val body = Json.parseToJsonElement(lease.plan.requestBody).jsonObject
        val identity = PrimaryIdentity(LocalSigner(PERSONA_SECRET.hexToBytes()), DEVICE_SECRET.hexToBytes(),
            dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(body.getValue("credential")))
        val before = app.recordingNodeHandoffs.pending(identity.participant)
        val original = before.single().binding
        check(original.nodeId == lease.plan.nodeId && original.leaseBody == lease.plan.requestBody)
        val route = requireNotNull(app.linkConsents.activeRoute(identity.participant, ROOM,
            BoxCadence.server(lease.plan.nodeId)))
        val status = app.cadenceClient.status(identity.participant, scope(identity, lease.plan.nodeId),
            "8a".repeat(16), identity, epochSeconds()).get(120, TimeUnit.SECONDS)
        assertTrue(status.answer.ready)
        val signed = BoxCadence.sign(lease.plan.nodeId, "POST", BoxCadence.guardedQueuePath(lease.plan.leaseId),
            BoxCadence.guardedQueueBody(body, original.requestId, original.event, original.publishBefore),
            identity.deviceSecretKey, epochSeconds())
        val request = LinkJsonRequest(route, signed.method, signed.path, changedSignature(signed.authorization), signed.body)
        val response = app.linkEngine.request(request).get(120, TimeUnit.SECONDS)
        assertEquals(HttpURLConnection.HTTP_UNAUTHORIZED, response.status)
        assertEquals(before, app.recordingNodeHandoffs.pending(identity.participant))
        assertEquals(lease, app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == lease.plan.leaseId })
        put("recording-worker-guarded-transport-refusal", buildJsonObject {
            put("originalGuardedPathReached", true); put("invalidSignatureRefused", true)
            put("originalJournalUnchanged", true); put("originalLeaseUnchanged", true); put("queueRetried", false)
        })
    }

    private fun observeRecordingWorkerDelivery(retained: Boolean = false, freshSample: Boolean = false) {
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.leaseId == "83".repeat(16) }
        val now = epochSeconds()
        check(now / 3600 >= lease.plan.startEpoch && now / 3600 < lease.plan.endEpoch) {
            "The original recording worker lease is not active; no recording was queued"
        }
        val ready = get("ready")
        assertFalse(ready.getValue("lab_slot_override_built").jsonPrimitive.boolean)
        assertEquals(lease.plan.nodeId, ready.getValue("link_node_id").jsonPrimitive.content)
        val leaseBody = Json.parseToJsonElement(lease.plan.requestBody).jsonObject
        val identity = PrimaryIdentity(LocalSigner(PERSONA_SECRET.hexToBytes()), DEVICE_SECRET.hexToBytes(),
            dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(leaseBody.getValue("credential")))
        val originalScope = scope(identity, lease.plan.nodeId)
        val journal = app.recordingNodeHandoffs
        val originalExpired = if (freshSample) {
            journal.pending(identity.participant).single {
                it.binding.draftId == "00000000-0000-0000-0000-000000000083"
            }.also { check(now >= it.binding.publishBefore) { "The original publication must remain expired" } }
        } else null
        val draftId = if (freshSample) "00000000-0000-0000-0000-000000000093"
            else "00000000-0000-0000-0000-000000000083"
        val previous = journal.pending(identity.participant).singleOrNull { it.binding.draftId == draftId }
        val binding = if (retained) {
            // An explicitly invoked lab retry reuses the persisted signed event,
            // request, node, lease and deadline. Recovery never invokes it.
            val original = requireNotNull(previous) { "No original recording custody to retry" }
            check(original.state in setOf(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.PREPARED,
                dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.OFFERED)) {
                "The original recording cannot be offered again"
            }
            original.binding.also {
                assertEquals(lease.plan.nodeId, it.nodeId)
                assertEquals(lease.plan.requestBody, it.leaseBody)
                check(now < it.publishBefore) { "Original publication expired; no retry" }
            }
        } else {
            check(previous == null) { "Original recording custody is retained; never enqueue again automatically" }
            val event = Events.sign(identity.deviceSecretKey, 1460, now, listOf(listOf("d", ROOM)),
                if (freshSample) "separate fresh guarded recording worker fixture ciphertext"
                    else "guarded recording worker fixture ciphertext", ByteArray(32) { 9 })
            dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffBinding(
                draftId, identity.participant, lease.plan.nodeId, lease.plan.requestBody,
                (if (freshSample) "94" else "84").repeat(16), event, minOf(now + 1800, lease.plan.endEpoch * 3600))
        }
        if (retained) {
            // Diagnose only a still-valid original attempt. Expired authority
            // is refused above, before even this read-only status request.
            val status = app.cadenceClient.status(identity.participant, originalScope,
                "8b".repeat(16), identity, now).get(120, TimeUnit.SECONDS)
            assertTrue(status.answer.ready)
        }
        val event = binding.event
        val ikm = DeadDrop.roomIkm(ByteArray(32) { 1 })
        val keys = (lease.plan.counterLo until lease.plan.counterHi).map {
            DeadDrop.deriveDropKey(ikm, event.createdAt / 3600, identity.participant, it)
        }.associateBy { it.publicKey }
        val relays = leaseBody.getValue("public_relays").jsonArray.map { it.jsonPrimitive.content }
        assertEquals("Receiver must use the original lease routes", relays, fixtureRelays())
        assertEquals("Delivery requires both original relay routes", 2, relays.distinct().size)
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val pools = relays.associateWith { relay -> dev.forgesworn.kithmoot.relay.RelayPool(
            listOf(relay), dev.forgesworn.kithmoot.relay.OkHttpRelaySockets(), receiverScope) }
        val exactRelayIngress = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val received = CompletableDeferred<Unit>()
        try {
            // Production relay parsing and room-drop decryption. Never publish
            // from this receiver, inject an echo, or trust a node item list.
            pools.forEach { (relay, pool) ->
                receiverScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    pool.subscribe(listOf(dev.forgesworn.kithmoot.relay.Filter(
                        kinds = listOf(RoomDrops.GIFT_WRAP_KIND), tags = mapOf("#p" to keys.keys.toList()))))
                        .collect { wrap ->
                            if (!Events.verify(wrap)) return@collect
                            val key = keys[RoomDrops.tagOf(wrap)] ?: return@collect
                            val inner = RoomDrops.openRoomDrop(wrap, key.privateKey) ?: return@collect
                            if (inner.toJson() != event.toJson()) return@collect
                            if (!pool.receivedEventConfirmsPublication(wrap.id)) return@collect
                            exactRelayIngress.add(relay)
                            if (exactRelayIngress.containsAll(relays)) received.complete(Unit)
                        }
                }
                pool.start()
            }
            journal.prepare(binding)
            val queued = app.cadenceClient.queueRecording(identity.participant, originalScope, identity, lease,
                binding, now, app.cadenceLeases, journal) { epochSeconds() < binding.publishBefore }
                .get(120, TimeUnit.SECONDS)
            assertEquals("queued", queued.custody.lease.receipt?.code)
            assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED, queued.handoff.state)
            assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED,
                journal.pending(identity.participant).single { it.binding == binding }.state)
            // The SDK ACK above deliberately leaves custody pending. Only
            // matching signed inner bytes from both TLS relays close it.
            runBlocking { withTimeout((binding.publishBefore - epochSeconds()).coerceAtLeast(1) * 1000) { received.await() } }
            assertTrue(exactRelayIngress.containsAll(relays))
            val confirmed = journal.confirmRelay(binding) { id -> id == event.id && exactRelayIngress.containsAll(relays) }
            assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CONFIRMED, confirmed.state)
            if (originalExpired != null) assertEquals(originalExpired,
                journal.pending(identity.participant).single { it.binding.draftId == originalExpired.binding.draftId })
            put("recording-worker-delivery", buildJsonObject {
                put("realLinkQueueAccepted", true); put("queueAckRemainedPending", true)
                put("exactSignedEventReceivedFromBothRelays", true); put("journalConfirmedAfterIngress", true)
                put("clockCompressed", false); put("physicalAcceptance", false)
                put("retainedOriginalBinding", retained)
                put("separateFreshSample", freshSample)
            })
        } finally {
            pools.values.forEach { it.stop() }
            runBlocking { receiverScope.coroutineContext[Job]?.cancelAndJoin() }
            keys.values.forEach { it.privateKey.fill(0) }
            ikm.bytes.fill(0)
        }
    }

    private fun pairAndQueueRecording() {
        val now = epochSeconds()
        val pairing = parsePairing(post("pairing").getValue("uri").jsonPrimitive.content, now)
        val route = app.linkEngine.pair(pairing.card, pairing.pairingSecret, pairing.expiresAt).get(120, TimeUnit.SECONDS)
        val ready = get("ready")
        val node = ready.getValue("link_node_id").jsonPrimitive.content
        val identity = identity(now)
        val originalScope = scope(identity, node)
        app.linkConsents.put(consent(identity, node, route.routeId, ready.getValue("claimed_by").jsonPrimitive.content))
        val status = app.cadenceClient.status(identity.participant, originalScope, "71".repeat(16), identity, now)
            .get(120, TimeUnit.SECONDS).answer
        assertTrue(status.ready)
        // The G5 router fixture accepts custody on these configured routes;
        // it has no worker/carriers and cannot qualify publication to them.
        val options = CadenceLeaseOptions(originalScope, "72".repeat(16), "73".repeat(16), 1, 0,
            status.currentEpoch, status.earliestStartEpoch, status.earliestStartEpoch + 1,
            ByteArray(32) { 1 }, listOf("wss://relay-a.example", "wss://relay-b.example"), listOf("local"), now)
        val lease = app.cadenceClient.stage(identity.participant, options, identity, now, app.cadenceLeases)
            .get(120, TimeUnit.SECONDS).lease
        val event = Events.sign(identity.deviceSecretKey, 1460, now, listOf(listOf("d", ROOM)),
            "guarded recording fixture ciphertext", ByteArray(32) { 9 })
        val binding = dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffBinding(
            "00000000-0000-0000-0000-000000000073", identity.participant, node,
            lease.plan.requestBody, "74".repeat(16), event, now + 1800)
        val journal = app.recordingNodeHandoffs
        journal.prepare(binding)
        val queued = app.cadenceClient.queueRecording(identity.participant, originalScope, identity, lease,
            binding, now, app.cadenceLeases, journal) { true }.get(120, TimeUnit.SECONDS)
        assertEquals("queued", queued.custody.lease.receipt?.code)
        assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED, queued.handoff.state)
        assertEquals(binding, journal.pending(identity.participant).single { it.binding == binding }.binding)
        put("recording-node-queue", buildJsonObject {
            put("realLinkQueueAccepted", true); put("custodyPending", true); put("relayDeliveryQualified", false)
        })
    }

    private fun withdrawRecordingWithLostReply() {
        val journal = app.recordingNodeHandoffs
        val original = journal.pending(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(PERSONA_SECRET.hexToBytes())).single { it.binding.draftId == "00000000-0000-0000-0000-000000000073" }
        assertTrue(original.state in setOf(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.QUEUED,
            dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING))
        assertFalse(journal.stillAllowed(original.binding))
        val identity = recordingIdentity(original.binding)
        val originalScope = scope(identity, original.binding.nodeId)
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.requestBody == original.binding.leaseBody }
        journal.revoke(original.binding)
        // The real SDK and node complete the authenticated withdrawal. Drop
        // only its reply at this application seam to reproduce lost custody ACK.
        val nodeReply = CompletableFuture<dev.forgesworn.kithmoot.relay.LinkJsonResponse>()
        val client = dev.forgesworn.kithmoot.cadence.CadenceClient(
            dev.forgesworn.kithmoot.relay.LinkJsonTransport { request ->
                app.linkEngine.request(request).whenComplete { _, failure ->
                    if (failure != null) nodeReply.completeExceptionally(failure)
                }.thenCompose { response ->
                    nodeReply.complete(response)
                    CompletableFuture<dev.forgesworn.kithmoot.relay.LinkJsonResponse>()
                }
            }, app.linkConsents)
        val requestId = original.withdrawal?.requestId ?: "75".repeat(16)
        val uncertain = client.withdrawRecording(identity.participant, originalScope, identity, lease,
            original.binding, requestId, epochSeconds(), app.cadenceLeases, journal) { true }
        val reply = nodeReply.get(120, TimeUnit.SECONDS)
        assertEquals("withdrawn", BoxCadence.parseReceipt(reply.status, reply.body).code)
        assertFalse(uncertain.isDone)
        uncertain.cancel(true)
        val retained = journal.pending(identity.participant).single { it.binding == original.binding }
        assertEquals(dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffState.CANCEL_PENDING, retained.state)
        assertEquals(requestId, requireNotNull(retained.withdrawal).requestId)
        put("recording-node-lost-withdrawal", buildJsonObject {
            put("originalQueueSurvivedProcessRestart", true); put("realNodeWithdrew", true)
            put("lostReplyRemainsPending", true)
        })
    }

    private fun replayRecordingWithdrawal() {
        val journal = app.recordingNodeHandoffs
        val original = journal.pending(dev.forgesworn.kithmoot.crypto.Schnorr.publicKeyHex(PERSONA_SECRET.hexToBytes())).single { it.binding.draftId == "00000000-0000-0000-0000-000000000073" }
        val identity = recordingIdentity(original.binding)
        val originalScope = scope(identity, original.binding.nodeId)
        val lease = app.cadenceLeases.all(ROOM, DEVICE).single { it.plan.requestBody == original.binding.leaseBody }
        val withdrawal = requireNotNull(original.withdrawal)
        assertFalse(journal.withdrawalStillAllowed(original.binding, withdrawal))
        val replay = app.cadenceClient.withdrawRecording(identity.participant, originalScope, identity, lease,
            original.binding, withdrawal.requestId, epochSeconds(), app.cadenceLeases, journal) { true }
            .get(120, TimeUnit.SECONDS)
        assertEquals("withdrawn", replay.lease.receipt?.code)
        assertTrue(journal.pending(identity.participant).none { it.binding == original.binding })
        put("recording-node-withdrawal-replay", buildJsonObject {
            put("originalWithdrawalSurvivedProcessRestart", true); put("realNodeAcceptedExactReplay", true)
            put("pendingCustodyRetired", true); put("relayDeliveryQualified", false)
        })
    }

    private fun recordingIdentity(binding: dev.forgesworn.kithmoot.cadence.RecordingNodeHandoffBinding) =
        PrimaryIdentity(LocalSigner(PERSONA_SECRET.hexToBytes()), DEVICE_SECRET.hexToBytes(),
            dev.forgesworn.kithmoot.protocol.NostrEvent.fromJson(binding.lease.getValue("credential")))


    private fun identity(now: Long): PrimaryIdentity {
        val persona = PERSONA_SECRET.hexToBytes()
        val device = DEVICE_SECRET.hexToBytes()
        return PrimaryIdentity(
            LocalSigner(persona),
            device,
            createDeviceCredential(persona, DEVICE, ROOM, now + 43_200, now, ByteArray(32)),
        )
    }

    private fun scope(
        identity: PrimaryIdentity,
        nodeId: String,
        trafficRoom: String = ROOM,
        roomGeneration: Long = 1,
    ) = CadenceScope(
        nodeId, ROOM, trafficRoom, roomGeneration,
        identity.participant, identity.devicePubkey, identity.credential, GRANT_ID,
    )

    private fun consent(identity: PrimaryIdentity, nodeId: String, routeId: String, bothy: String) = LinkConsent(
        identity.participant, ROOM, bothy, routeId, "ws://$nodeId/events",
        listOf(get("ready").getValue("introduction_relay_url").jsonPrimitive.content), LinkConsentState.ACTIVE,
    )

    private fun leaseOptions(
        scope: CadenceScope,
        requestId: String,
        leaseId: String,
        currentEpoch: Long,
        start: Long,
        end: Long,
        now: Long,
        generation: Long,
        deviceSlot: Int = 0,
    ) = CadenceLeaseOptions(
        scope, requestId, leaseId, generation, deviceSlot, currentEpoch, start, end,
        ByteArray(32) { scope.roomGeneration.toByte() }, fixtureRelays(), listOf("local"), now,
    )

    private fun fixtureRelays(): List<String> {
        val relays = get("ready").getValue("routes").jsonArray.map { it.jsonPrimitive.content }
        require(relays.size == 2 && relays.distinct().size == 2 && relays.all { relay ->
            val url = URI(relay)
            url.scheme == "wss" && !url.host.isNullOrBlank() && url.port in 1..65535 &&
                url.rawUserInfo == null && url.rawQuery == null && url.rawFragment == null
        }) { "fixture must supply two distinct WebPKI relay URLs" }
        return relays
    }

    private fun parsePairing(uri: String, now: Long): NativePairing = try {
        BothyPairing.parse(uri, now).let { NativePairing(it.card, it.pairingSecret, it.expiresAt) }
    } catch (error: IllegalArgumentException) {
        val root = Json.parseToJsonElement(Base64.getUrlDecoder().decode(uri.removePrefix("bothy:")).decodeToString()).jsonObject
        val card = Base64.getDecoder().decode(root.getValue("card").jsonPrimitive.content)
        require(card.decodeToString().contains("ws://127.0.0.1:")) { throw error }
        NativePairing(card, root.getValue("secret").jsonPrimitive.content.hexToBytes(), root.getValue("exp").jsonPrimitive.content.toLong())
    }

    private fun get(path: String) = request(path, "GET")
    private fun post(path: String) = request(path, "POST")
    private fun request(path: String, method: String) = (URL("$control/$path").openConnection() as HttpURLConnection).let { connection ->
        try {
            connection.requestMethod = method; connection.connectTimeout = 10_000; connection.readTimeout = 120_000
            require(connection.responseCode in 200..299) { "fixture control rejected $path" }
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private fun put(key: String, value: kotlinx.serialization.json.JsonObject) {
        val connection = URL("$control/journey/$key").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10_000; connection.readTimeout = 10_000
            connection.outputStream.bufferedWriter().use { it.write(value.toString()) }
            require(connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT)
        } finally { connection.disconnect() }
    }

    private fun assertFutureRefused(future: java.util.concurrent.CompletableFuture<*>) {
        assertThrows(ExecutionException::class.java) { future.get(120, TimeUnit.SECONDS) }
    }

    private fun changedSignature(authorization: String): String {
        val raw = Base64.getDecoder().decode(authorization.removePrefix("Nostr ")).decodeToString()
        val signature = Json.parseToJsonElement(raw).jsonObject.getValue("sig").jsonPrimitive.content
        val changed = (if (signature[0] == '0') "1" else "0") + signature.drop(1)
        return "Nostr " + Base64.getEncoder().encodeToString(raw.replaceFirst(signature, changed).toByteArray())
    }

    private fun epochSeconds() = System.currentTimeMillis() / 1000
    private data class NativePairing(val card: ByteArray, val pairingSecret: ByteArray, val expiresAt: Long)

    private companion object {
        const val PERSONA_SECRET = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DEVICE_SECRET = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val DEVICE = "ed83704c95d829046f1ac27806211132102c34e9ac7ffa1b71110658e5b9d1bd"
        const val ROOM = "4242424242424242424242424242424242424242424242424242424242424242"
        const val GRANT_ID = "33333333333333333333333333333333"
        const val REQUEST_ID = "11111111111111111111111111111111"
        const val LEASE_ID = "22222222222222222222222222222222"
        const val PENDING_REQUEST_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val PENDING_LEASE_ID = "cccccccccccccccccccccccccccccccc"
        const val PENDING_QUEUE_REQUEST_ID = "dddddddddddddddddddddddddddddddd"
        const val PENDING_REKEY_REQUEST_ID = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val PENDING_STATUS_REQUEST_ID = "ffffffffffffffffffffffffffffffff"
        const val QUEUE_REQUEST_ID = "44444444444444444444444444444444"
        const val REKEY_REQUEST_ID = "55555555555555555555555555555555"
        const val OLD_QUEUE_REQUEST_ID = "77777777777777777777777777777777"
        const val STATUS_REQUEST_ID = "88888888888888888888888888888888"
        const val LOWER_REQUEST_ID = "99999999999999999999999999999999"
        const val LOWER_LEASE_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val LOWER_TRAFFIC_ROOM = "4343434343434343434343434343434343434343434343434343434343434343"
    }
}
