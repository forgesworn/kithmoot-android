package dev.forgesworn.kithmoot.mixed

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.KithMootApplication
import dev.forgesworn.kithmoot.MainActivity
import dev.forgesworn.kithmoot.account.*
import dev.forgesworn.kithmoot.crypto.Digests
import dev.forgesworn.kithmoot.crypto.Schnorr
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.*
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI

/** Test-APK-only private OOB controller for the actual application owners.
 * Never approves consent: the existing app and signer UI own every approval.
 * No fixture signer/vault/runtime, store seeding, clearing, or device commands.
 * Capabilities move only on adb-reversed loopback; receipts omit message bodies.
 */
@RunWith(AndroidJUnit4::class)
class MixedInvitationAppTest {
    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val activityScenario: ActivityScenario<MainActivity>
        get() = launchedActivity ?: ActivityScenario.launch(MainActivity::class.java).also { launchedActivity = it }
    @get:Rule val activityCleanup = object : org.junit.rules.ExternalResource() {
        override fun after() { launchedActivity?.close(); launchedActivity = null }
    }
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private val app get() = instrumentation.targetContext.applicationContext as KithMootApplication
    private val step get() = requireNotNull(args.getString("mixed_step")).also { require(it.matches(Regex("[a-z-]{1,40}"))) }
    private val control get() = URI(requireNotNull(args.getString("mixed_control"))).also {
        require(it.scheme == "http" && it.host == "127.0.0.1" && it.port in 1024..65535 && it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null)
    }.toString().removeSuffix("/")

    @Test fun run_private_app_step() = runBlocking {
        val name = step
        val input = exchange("step", buildJsonObject { put("step", name) })
        try {
            withTimeout(240_000) {
                exchange("result", buildJsonObject { put("step", name); put("ok", true); put("result", runStep(name, input)) })
            }
        } catch (failure: Throwable) {
            runCatching { exchange("result", buildJsonObject { put("step", name); put("ok", false); put("failure", failure.javaClass.simpleName) }) }
            // Exception messages and causes can carry capabilities. Do not print them.
            throw AssertionError("Mixed app step $name failed (${failure.javaClass.simpleName})")
        }
        Unit // JUnit4 requires a void test method.
    }
    private suspend fun runStep(name: String, input: JsonObject): JsonObject {
        if (name == "signer-pair") return pairCambium(input)
        if (name == "tls-check") {
            // The dedicated adb reverse transports bytes only. Android's own
            // trust store and HTTPS hostname verification authenticate the WSS host.
            val plain = java.net.Socket()
            plain.connect(java.net.InetSocketAddress("127.0.0.1", 28444), 10_000)
            val factory = javax.net.ssl.SSLSocketFactory.getDefault() as javax.net.ssl.SSLSocketFactory
            (factory.createSocket(plain, "link-lab.forgesworn.dev", 28444, true) as javax.net.ssl.SSLSocket).use { socket ->
                socket.soTimeout = 10_000
                socket.sslParameters = socket.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                socket.startHandshake()
                val peer = socket.session.peerCertificates.first() as java.security.cert.X509Certificate
                return buildJsonObject { put("trusted", true); put("hostnameVerified", true); put("subject", peer.subjectX500Principal.name); put("issuer", peer.issuerX500Principal.name); put("notAfterMillis", peer.notAfter.time) }
            }
        }
        val model = model()
        if (name == "sign-in") {
            activityScenario.onActivity { model.refreshSigners() }
            await { model.start.value.signers.any { it.packageName == CAMBIUM } }
            activityScenario.onActivity { model.signInWithSignerApp(CAMBIUM) }
        }
        await { model.start.value.account != null && !model.start.value.signingIn }
        val saved = requireNotNull(app.accounts.load())
        check(saved.method == "nip55" && saved.signerPackage == CAMBIUM)
        val persona = saved.pubkey
        val signer = requireNotNull(model.vmlsSigner()).also { check(it.pubkey == persona && it.method == "nip55") }
        if (name == "sign-in") return buildJsonObject { put("persona", persona); put("method", "nip55"); put("signerPackage", CAMBIUM) }
        if (name.startsWith("ordinary-")) {
            if (name == "ordinary-restart-check") {
                // A new activity owns a new ViewModel; reopen through its saved-room action.
                launchedActivity?.close()
                launchedActivity = null
                val fresh = model()
                await { fresh.start.value.account?.pubkey == persona && !fresh.start.value.signingIn }
                check(fresh.stage.value == Stage.START)
                return ordinaryStep(name, input, fresh, persona)
            }
            return ordinaryStep(name, input, model, persona)
        }
        if (name == "configure-relays") {
            var savedResult: String? = "Not invoked"
            activityScenario.onActivity {
                // The same Privacy switch and persistent relay editor save used by Settings.
                model.setProfilesEnabled(false)
                savedResult = model.saveAccountRelays(listOf(dev.forgesworn.kithmoot.relay.RelayChoice("wss://link-lab.forgesworn.dev:28444")))
            }
            check(savedResult == null)
            val choices = model.accountRelayChoices()
            check(choices.size == 1 && choices.single().let { it.url.removeSuffix("/") == "wss://link-lab.forgesworn.dev:28444" && it.read && it.write })
            check(!model.start.value.publicProfiles)
            return buildJsonObject { put("fixtureRelayOnly", true); put("relayCount", choices.size); put("publicProfiles", false); put("separateNip05ToggleAvailable", false) }
        }
        if (name == "provision-target") {
            // Read only the public key of the app-owned protected provision
            // transport already used by RoomViewModel.provisionRendezvous.
            // This exposes neither a scalar nor a generic key accessor.
            check(model.vmlsSigner() === signer && model.start.value.account?.pubkey == persona)
            val target = Schnorr.publicKeyHex(requireNotNull(saved.clientSecretKey))
            check(model.vmlsSigner() === signer && app.accounts.load()?.pubkey == persona)
            return result("targetDevice", target)
        }
        if (name == "provision") {
            activityScenario.onActivity { model.provisionRendezvous(input.long("index")) }
            await { model.start.value.rendezvous?.activeIndex == input.long("index") }
            return buildJsonObject { put("index", input.long("index")); put("protected", true) }
        }
        app.setVmlsPreview(true)
        val boxes = requireNotNull(app.vmlsBoxes)
        val core = EngineCore.of(app)
        val witness = requireNotNull(app.restoreWitness)
        boxes.open(persona)
        when (name) {
            "witness-prepare" -> { witness.prepare(persona); awaitWitness { it.enrolment is WitnessEnrolment.Prepared } }
            "witness-pair" -> { witness.pair(persona, input.text("code")); awaitWitness { it.enrolment is WitnessEnrolment.Paired } }
            "witness-genesis" -> {
                witness.begin(persona); awaitWitness { it.enrolment is WitnessEnrolment.Enrolled }
                val enrolled = witness.state.value.enrolment as WitnessEnrolment.Enrolled
                return buildJsonObject { put("enrolLine", enrolled.line); put("box", enrolled.box) }
            }
            "witness-check" -> { witness.checkNow(persona); awaitWitness { it.status == CoordinationStatus.Active } }
            "guest-home-pair" -> return result("device", boxes.pairPrivateInvitationHome(signer, input.text("code")) { model.vmlsSigner() === signer && app.accounts.load()?.pubkey == persona })
            "node-check" -> {
                boxes.check(persona, input.text("box"))
                await { boxes.state.value.boxes.any { it.box == input.text("box") && it.vmls == true } }
            }
            "node-pair" -> {
                boxes.pair(signer, input.text("code"))
                await { boxes.state.value.boxes.any { it.box == input.text("box") && it.vmls == true } }
            }
            "create" -> return result("session", boxes.createInvitationRoom(persona, input.text("box"), input.text("name")))
            "issue" -> return result("authorization", boxes.issuePrivateInvitation(persona, input.text("session"), ExperimentalInvitationTarget(input.text("persona"), input.text("device"), input.text("rendezvous"), input.text("leafHome"), input.long("expiresAt"))))
            "review" -> { boxes.reviewPrivateInvitation(persona, input.text("inviter"), input.text("authorization")); return result("review", "validated") }
            "accept" -> return result("session", boxes.acceptPrivateInvitation(persona, input.text("inviter"), input.text("authorization"), input.text("name")))
            "response" -> return result("response", boxes.exportPrivateInvitationResponse(persona, input.text("session")))
            "import-response" -> { boxes.importPrivateInvitationResponse(persona, input.text("session"), input.text("authorization"), input.text("response")); return result("status", "recorded") }
            "registration-request" -> return result("request", boxes.exportInvitationRegistrationRequest(persona, input.text("session"), input.text("authorization"), input.text("response")))
            "registration-review" -> { boxes.reviewInvitationRegistrationRequest(input.text("request")); return result("review", "validated") }
            "register" -> return result("receipt", boxes.registerInvitationRequest(persona, input.text("request")))
            "receipt-add" -> { boxes.addPrivateInvitationMember(persona, input.text("session"), input.text("authorization"), input.text("response"), input.text("receipt")); return result("status", "recorded") }
            "remove" -> { boxes.removeInvitationMember(persona, input.text("session"), input.text("leaf")); return result("status", "recorded") }
            "send" -> {
                boxes.say(persona, input.text("session"), input.text("body"))
                await { boxes.rooms.value.any { it.session == input.text("session") && it.messages.any { m -> m.mine && m.body == input.text("body") } } }
            }
            "drive", "snapshot", "restart-check", "receive" -> {
                repeat((input["rounds"]?.jsonPrimitive?.int ?: 1).also { require(it in 1..30) }) { boxes.foregroundRounds(persona, signer); delay(100) }
                if (name == "receive") await {
                    boxes.foregroundRounds(persona, signer)
                    boxes.rooms.value.any { it.session == input.text("session") && it.messages.any { m -> !m.mine && m.body == input.text("body") } }
                }
            }
            "target" -> Unit
            else -> error("Unknown bounded app step")
        }
        val device = (core.vault.device(core.vault.context("dev.forgesworn.kithmoot", persona)) as? VaultResult.Ok)?.value
        val rz = saved.clientSecretKey?.let { app.rendezvous.active(persona, Schnorr.publicKeyHex(it)) }?.let { child ->
            try { child.receipt.rendezvousPubkey } finally { child.wipe() }
        }
        return buildJsonObject {
            put("persona", persona); put("device", device?.device); put("rendezvous", rz)
            put("witnessActive", core.vault.coordinationStatus(persona, check = true) == CoordinationStatus.Active)
            put("boxErrorPresent", boxes.state.value.error != null)
            put("boxes", buildJsonArray { boxes.state.value.boxes.forEach { add(buildJsonObject { put("box", it.box); put("vmls", it.vmls) }) } })
            put("rooms", buildJsonArray { boxes.rooms.value.forEach { room -> add(buildJsonObject {
                put("session", room.session); put("state", room.state.name); put("canSend", room.canSend)
                put("members", buildJsonArray { room.members.forEach { add(buildJsonObject { put("leaf", it.leaf); put("device", it.device); put("invited", it.invited) }) } })
                put("messages", buildJsonArray { room.messages.forEach { add(buildJsonObject { put("mine", it.mine); put("sha256", Digests.sha256(it.body.toByteArray()).toHex()) }) } })
            }) } })
        }
    }

    /** Ordinary room actions use the same ViewModel entry, composer and saved-room reopen as the UI. */
    private suspend fun ordinaryStep(name: String, input: JsonObject, model: RoomViewModel, persona: String): JsonObject {
        fun digest(value: String) = Digests.sha256(value.toByteArray(Charsets.UTF_8)).toHex()
        fun snapshot(): JsonObject {
            val room = model.room.value
            check(model.stage.value == Stage.ROOM && room.roomId.isNotBlank())
            val link = requireNotNull(decodeInvitationUrl(room.joinUrl))
            val authority = try { link.invitation.roomAuthority } finally { link.invitation.bearer.fill(0) }
            // UI observations only: a tile is one participant; this role comparison does not
            // prove cryptographic admission or that the account signed every room action.
            val participants = room.tiles.map { it.participant }.distinct()
            return buildJsonObject {
                put("room", room.roomId)
                put("roomSha256", digest(room.roomId))
                put("rootAuthoritySha256", digest(authority))
                put("accountSha256", digest(persona))
                put("accountPrimary", room.selfParticipant == persona && !room.secondary)
                put("secondary", room.secondary)
                put("tileCount", room.tiles.size)
                put("participantCount", participants.size)
                put("participantSha256", buildJsonArray { participants.map(::digest).sorted().forEach { add(it) } })
                put("messageHashes", buildJsonArray { room.chat.takeLast(32).forEach { message -> add(buildJsonObject {
                    put("authorSha256", digest(message.participant))
                    put("idSha256", digest(message.id))
                    put("bodySha256", digest(message.body))
                    put("remote", message.participant != room.selfParticipant)
                }) } })
            }
        }
        suspend fun openSavedRoom(): String {
            val id = input.text("room").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            if (model.stage.value != Stage.ROOM || model.room.value.roomId != id || name == "ordinary-restart-check") {
                activityScenario.onActivity { model.reopenRoom(id) }
                await { model.stage.value == Stage.ROOM && model.room.value.roomId == id && !model.start.value.busy }
            }
            return id
        }
        when (name) {
            "ordinary-join" -> {
                check(model.stage.value == Stage.START)
                val url = input.text("url") // Private loopback only; never return or log the bearer.
                val invite = requireNotNull(decodeInvitationUrl(url))
                try { check(invite.invitation.persistent) } finally { invite.invitation.bearer.fill(0) }
                activityScenario.onActivity { model.joinFromUrl(url) }
                await { model.stage.value == Stage.ROOM || model.guestAdmission.value != null || (!model.start.value.busy && model.start.value.error != null) }
                if (model.stage.value != Stage.ROOM) return buildJsonObject {
                    put("status", if (model.guestAdmission.value != null) "awaiting-guest-action" else "entry-error")
                    put("shareUrlSha256", digest(url))
                }
                return buildJsonObject { put("status", "room-open"); put("shareUrlSha256", digest(url)); put("snapshot", snapshot()) }
            }
            "ordinary-send" -> {
                openSavedRoom()
                val body = input.text("body").also { require(it.isNotBlank() && it.length <= 1200) }
                val expected = digest(body)
                val before = model.room.value.chat.map { it.id }.toSet()
                activityScenario.onActivity { model.sendChat(body) }
                await { val room = model.room.value; room.chat.any { it.id !in before && it.participant == room.selfParticipant && digest(it.body) == expected } }
                return buildJsonObject { put("locallyRetainedBodySha256", expected); put("snapshot", snapshot()) }
            }
            "ordinary-receive", "ordinary-restart-check" -> {
                openSavedRoom()
                val expected = input.text("expectedBodySha256").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                await { val room = model.room.value; room.chat.any { it.participant != room.selfParticipant && digest(it.body) == expected } }
                return buildJsonObject { put("remoteBodySha256", expected); put("savedRoomReopened", name == "ordinary-restart-check"); put("snapshot", snapshot()) }
            }
            "ordinary-snapshot" -> { openSavedRoom(); return snapshot() }
            else -> error("Unknown ordinary room step")
        }
    }
    private suspend fun pairCambium(input: JsonObject): JsonObject {
        val uri = input.text("bunkerURI")
        val persona = input.text("expectedPersona")
        val npub = input.text("expectedNpub")
        require(persona.matches(Regex("[0-9a-f]{64}")))
        require(npub.matches(Regex("npub1[023456789acdefghjklmnpqrstuvwxyz]{58}")))
        require(URI(uri).let { it.scheme == "bunker" && it.host == persona && uri.length <= 8192 })
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun nodes(id: String) = automation.rootInActiveWindow?.let { root ->
            try { root.findAccessibilityNodeInfosByViewId("$CAMBIUM:id/$id") }
            finally { @Suppress("DEPRECATION") root.recycle() }
        }.orEmpty()
        fun setUri(value: String): Boolean = nodes("bunkerInput").firstOrNull()?.let { node ->
            try { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }) }
            finally { @Suppress("DEPRECATION") node.recycle() }
        } ?: false
        app.startActivity(android.content.Intent.makeMainActivity(android.content.ComponentName(CAMBIUM, "$CAMBIUM.MainActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        await { nodes("bunkerInput").any { node -> try { node.isEnabled && node.isEditable } finally { @Suppress("DEPRECATION") node.recycle() } } }
        try {
            check(setUri(uri))
            await { nodes("bunkerInput").any { node -> try { node.text?.toString() == uri } finally { @Suppress("DEPRECATION") node.recycle() } } }
            val button = nodes("pairButton").single()
            try {
                check(button.isEnabled && button.isClickable)
                check(button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            } finally { @Suppress("DEPRECATION") button.recycle() }
            // Cambium clears the newly populated input only after the new
            // connection succeeds and addPairing commits. An old matching row
            // alone cannot establish this setup's completion.
            await {
                val hasError = nodes("pairingError").any { node -> try { node.isVisibleToUser } finally { @Suppress("DEPRECATION") node.recycle() } }
                check(!hasError) { "Cambium setup failed." }
                val cleared = nodes("bunkerInput").any { node -> try { node.isShowingHintText || node.text?.toString().orEmpty().isEmpty() } finally { @Suppress("DEPRECATION") node.recycle() } }
                val ready = nodes("pairButton").any { node -> try { node.isEnabled && node.isClickable } finally { @Suppress("DEPRECATION") node.recycle() } }
                val progressing = nodes("pairingProgress").any { node -> try { node.isVisibleToUser } finally { @Suppress("DEPRECATION") node.recycle() } }
                val identityMatched = nodes("pairingNpubValue").any { node -> try { node.text?.toString() == npub } finally { @Suppress("DEPRECATION") node.recycle() } }
                cleared && ready && !progressing && identityMatched
            }
            return buildJsonObject { put("paired", true); put("persona", persona); put("publicIdentityMatched", true); put("currentSetupCompleted", true) }
        } finally {
            // Never leave a pairing capability visible if ordinary setup fails.
            setUri("")
        }
    }

    private fun model(): RoomViewModel {
        lateinit var found: RoomViewModel
        activityScenario.onActivity { found = ViewModelProvider(it)[RoomViewModel::class.java] }
        return found
    }
    private suspend fun await(test: suspend () -> Boolean) { withTimeout(180_000) { while (!test()) delay(50) } }
    private suspend fun awaitWitness(test: (RestoreWitnessState) -> Boolean) {
        delay(100)
        await { val s = requireNotNull(app.restoreWitness).state.value; check(s.error == null); !s.busy && test(s) }
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    private fun result(key: String, value: String) = buildJsonObject { put(key, value) }
    private fun exchange(path: String, body: JsonObject): JsonObject {
        val connection = URI("$control/android/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 5_000; connection.readTimeout = 20_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode == 200)
            val payload = connection.inputStream.use { stream ->
                val out = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) { val count = stream.read(chunk); if (count < 0) break; check(out.size() + count <= 1_048_576); out.write(chunk, 0, count) }
                out.toByteArray()
            }
            return Json.parseToJsonElement(payload.toString(Charsets.UTF_8)).jsonObject
        } finally { connection.disconnect() }
    }
    companion object { private const val CAMBIUM = "dev.forgesworn.cambium" }
}
