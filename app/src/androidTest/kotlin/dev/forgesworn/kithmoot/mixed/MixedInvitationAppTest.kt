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
import kotlinx.coroutines.flow.collect
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
    @Test fun consentReviewContainsOnlyCorrelatedPublicScope() {
        val token = "a".repeat(64)
        val scope = ConsentScope("dev.forgesworn.kithmoot", "b".repeat(64), "c".repeat(64), "d".repeat(64), MlsVault.SIGN_METHOD)
        val wire = consentReview(validConsentToken(token), scope)
        check(wire.keys == setOf("consentToken", "principal", "persona", "device", "homeBox", "method"))
        check(wire.getValue("consentToken").jsonPrimitive.content == token)
        check(wire.getValue("homeBox").jsonPrimitive.content == scope.homeBox)
        check(wire.getValue("method").jsonPrimitive.content == MlsVault.SIGN_METHOD)
        check(runCatching { validConsentToken("stale") }.isFailure)
    }

    @Test fun adminRoleReviewRetainsExactAppliedDeviceAuthority() {
        val roles = ExperimentalAdminRoles("7", "a".repeat(64), "b".repeat(64), false, listOf(
            ExperimentalAdminDevice("b".repeat(64), "c".repeat(64), "d".repeat(64), false),
            ExperimentalAdminDevice("e".repeat(64), "c".repeat(64), "f".repeat(64), true)))
        val wire = adminRoles(roles)
        check(wire.getValue("epoch").jsonPrimitive.content == "7")
        check(wire.getValue("commitment").jsonPrimitive.content == roles.commitment)
        check(!wire.getValue("ownIsAdmin").jsonPrimitive.boolean)
        val devices = wire.getValue("devices").jsonArray.map { it.jsonObject }
        check(devices.size == 2 && devices.map { it.getValue("persona").jsonPrimitive.content }.distinct().size == 1)
        check(devices.map { it.getValue("device").jsonPrimitive.content }.distinct().size == 2)
        check(devices.filter { it.getValue("admin").jsonPrimitive.boolean }.single().getValue("leaf").jsonPrimitive.content == "e".repeat(64))
        check(wire.keys == setOf("profile", "policy", "epoch", "commitment", "ownLeaf", "ownIsAdmin", "devices"))
    }

    @Test fun refusalEvidenceRequiresExactStaticNativeOrLocalInvariant() {
        val sensitive = "private-capability-must-never-be-exported"
        val native = dev.forgesworn.vmls.ffi.VmlsException.Engine("AdminRequired", 195U, sensitive)
        check(refusalDto("admin-remove", native) == buildJsonObject {
            put("source", "native-engine"); put("code", "AdminRequired"); put("number", 195)
        })
        val session = EngineSessionException("InvalidAdminSet", IllegalStateException(sensitive))
        check(refusalDto("admin-role-change", session).getValue("code").jsonPrimitive.content == "InvalidAdminSet")
        val roles = ExperimentalAdminRoles("1", "a".repeat(64), "b".repeat(64), false,
            listOf(ExperimentalAdminDevice("b".repeat(64), "c".repeat(64), "d".repeat(64), true)))
        val realPrecheck = runCatching { AdminRoleIntent.targetLeafIds(roles, "b".repeat(64), false, roles.commitment) }.exceptionOrNull()!!
        check(refusalDto("admin-role-change", realPrecheck).getValue("code").jsonPrimitive.content == "AdminRequired")
        val lastAdmin = runCatching { AdminRoleIntent.targetLeafIds(roles.copy(ownIsAdmin = true), "b".repeat(64), false, roles.commitment) }.exceptionOrNull()!!
        check(refusalDto("admin-role-change", lastAdmin).getValue("code").jsonPrimitive.content == "LastAdminDemotion")
        for (failure in listOf(
            IllegalStateException("This device is not a current group admin."),
            IllegalStateException(sensitive), RuntimeException("Transport"),
            dev.forgesworn.link.ffi.LinkException.Transport(sensitive),
            EngineSessionException(sensitive, native),
            dev.forgesworn.vmls.ffi.VmlsException.Engine("AdminRequired", 196U, sensitive),
            dev.forgesworn.vmls.ffi.VmlsException.Engine(sensitive, 195U, sensitive),
        )) {
            val dto = refusalDto("admin-role-change", failure)
            check(dto.getValue("code").jsonPrimitive.content == "Unclassified")
            check(!dto.toString().contains(sensitive))
        }
        check(refusalDto("send", realPrecheck).getValue("code").jsonPrimitive.content == "Unclassified")
    }

    @Test fun kotlinIndyPrecheckEvidenceUsesOnlyApprovedOperationFrames() {
        AdminPrecheckRefusalRegression.verify()
        val runtime = "dev.forgesworn.kithmoot.mls.VmlsRuntime"
        val actual = IllegalStateException("This device is not a current group admin.", RuntimeException("private-do-not-export"))
        actual.stackTrace = arrayOf(StackTraceElement(runtime, "removeAdminInvitationMember\$lambda\$134\$lambda\$132", "private-do-not-export", 10))
        val dto = refusalDto("admin-remove", actual)
        check(dto.keys == setOf("source", "code", "origin"))
        check(dto.getValue("code").jsonPrimitive.content == "AdminRequired")
        check(dto.getValue("origin").jsonPrimitive.content == "VmlsRuntime.removeAdminInvitationMember\$lambda\$134\$lambda\$132")
        check(!dto.toString().contains("private-do-not-export"))
        check(refusalDto("admin-issue", actual).getValue("code").jsonPrimitive.content == "Unclassified")
    }

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
            runCatching { exchange("result", buildJsonObject { put("step", name); put("ok", false); put("failure", failure.javaClass.simpleName); put("refusal", refusalDto(name, failure)) }) }
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
        if (name.startsWith("admin-")) {
            check(dev.forgesworn.kithmoot.BuildConfig.DEBUG &&
                ((dev.forgesworn.kithmoot.BuildConfig.EXPERIMENTAL_INVITATION_MLS && dev.forgesworn.kithmoot.BuildConfig.EXPERIMENTAL_ADMIN_INVITATION_MLS) ||
                    dev.forgesworn.kithmoot.BuildConfig.INVITATION_PROFILE == "admin2"))
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
        if (name == "signer-challenge") {
            check(dev.forgesworn.kithmoot.BuildConfig.DEBUG &&
                dev.forgesworn.kithmoot.BuildConfig.INVITATION_PROFILE == "admin2" &&
                dev.forgesworn.kithmoot.BuildConfig.ACCOUNT_NETWORK_DEFAULT == "private-invitations")
            check(persona == input.text("expectedPersona"))
            val challenge = input.text("challenge").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            val stage = input.text("stage").also { require(it in setOf("before-restart", "after-restart")) }
            val createdAt = input.long("createdAt")
            val now = System.currentTimeMillis() / 1000
            require(createdAt in (now - 120)..(now + 120))
            val tags = listOf(listOf("challenge", challenge), listOf("stage", stage), listOf("t", "vennel-signer-continuity-lab"))
            val content = "Vennel synthetic signer continuity test; not published"
            // Actual production NIP-55 signer path; no publish, account mutation or sign-in.
            val event = checkedSignedEvent(signer.sign(1, createdAt, tags, content), persona, 1, createdAt, tags, content)
            check(model.vmlsSigner() === signer && app.accounts.load()?.pubkey == persona)
            return buildJsonObject {
                put("stage", stage); put("signatureVerified", true); put("unpublished", true)
                put("eventId", event.id); put("challengeSha256", Digests.sha256(challenge.toByteArray(Charsets.UTF_8)).toHex())
                // Existing loopback result is private. Public receipts must project only hashes/flags.
                put("signedEvent", event.toJson())
            }
        }
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
            // Explicit Admin2 routes: no profile guessing, upgrade, or Invitation1 fallback.
            "admin-node-check" -> return observedConsent(boxes, input) {
                boxes.check(persona, input.text("box"))
                await { boxes.state.value.boxes.any { it.box == input.text("box") && it.vmls == true } }
                buildJsonObject { put("box", input.text("box")); put("vmls", true) }
            }
            "admin-create" -> return result("session", boxes.createAdminInvitationRoom(persona, input.text("box"), input.text("name")))
            "admin-issue" -> return observedAdminInvitationConsent(boxes, input) {
                result("authorization", boxes.issuePrivateAdminInvitation(persona, input.text("session"), ExperimentalInvitationTarget(input.text("persona"), input.text("device"), input.text("rendezvous"), input.text("leafHome"), input.long("expiresAt"))))
            }
            "admin-review" -> return result("review", boxes.reviewPrivateAdminInvitation(persona, input.text("inviter"), input.text("authorization")))
            "admin-accept" -> return observedConsent(boxes, input) {
                result("session", boxes.acceptPrivateAdminInvitation(persona, input.text("inviter"), input.text("authorization"), input.text("name")))
            }
            "admin-response" -> return result("response", boxes.exportPrivateAdminInvitationResponse(persona, input.text("session")))
            "admin-import-response" -> { boxes.importPrivateAdminInvitationResponse(persona, input.text("session"), input.text("authorization"), input.text("response")); return result("status", "recorded") }
            "admin-registration-request" -> return result("request", boxes.exportAdminInvitationRegistrationRequest(persona, input.text("session"), input.text("authorization"), input.text("response")))
            // Registration is intentionally the same independent storage-only ceremony.
            "admin-registration-review" -> { boxes.reviewInvitationRegistrationRequest(input.text("request")); return result("review", "validated") }
            "admin-register" -> return result("receipt", boxes.registerInvitationRequest(persona, input.text("request")))
            "admin-receipt-add" -> { boxes.addPrivateAdminInvitationMember(persona, input.text("session"), input.text("authorization"), input.text("response"), input.text("receipt")); return result("status", "recorded") }
            "admin-remove" -> { boxes.removeAdminInvitationMember(persona, input.text("session"), input.text("leaf")); return result("status", "recorded") }
            "admin-roles" -> return adminRoles(boxes.currentAdminRoles(persona, input.text("session")))
            "admin-role-change" -> {
                // The controller first reviews admin-roles and supplies that complete
                // commitment. Native authority atomically refuses stale/invalid intent.
                val reviewed = input.text("expectedCommitment").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                boxes.changeAdminRole(persona, input.text("session"), input.text("leaf"), input.getValue("admin").jsonPrimitive.boolean, reviewed)
                return result("status", "recorded")
            }
            "create" -> return result("session", boxes.createInvitationRoom(persona, input.text("box"), input.text("name")))
            "issue" -> return result("authorization", boxes.issuePrivateInvitation(persona, input.text("session"), ExperimentalInvitationTarget(input.text("persona"), input.text("device"), input.text("rendezvous"), input.text("leafHome"), input.long("expiresAt"))))
            "review" -> return result("review", boxes.reviewPrivateInvitation(persona, input.text("inviter"), input.text("authorization")))
            "accept" -> return observedConsent(boxes, input) {
                result("session", boxes.acceptPrivateInvitation(persona, input.text("inviter"), input.text("authorization"), input.text("name")))
            }
            "response" -> return result("response", boxes.exportPrivateInvitationResponse(persona, input.text("session")))
            "import-response" -> { boxes.importPrivateInvitationResponse(persona, input.text("session"), input.text("authorization"), input.text("response")); return result("status", "recorded") }
            "registration-request" -> return result("request", boxes.exportInvitationRegistrationRequest(persona, input.text("session"), input.text("authorization"), input.text("response")))
            "registration-review" -> { boxes.reviewInvitationRegistrationRequest(input.text("request")); return result("review", "validated") }
            "register" -> return result("receipt", boxes.registerInvitationRequest(persona, input.text("request")))
            "receipt-add" -> { boxes.addPrivateInvitationMember(persona, input.text("session"), input.text("authorization"), input.text("response"), input.text("receipt")); return result("status", "recorded") }
            "remove" -> { boxes.removeInvitationMember(persona, input.text("session"), input.text("leaf")); return result("status", "recorded") }
            "send" -> observedConsent(boxes, input) {
                boxes.say(persona, input.text("session"), input.text("body"))
                await { boxes.rooms.value.any { it.session == input.text("session") && it.messages.any { m -> m.mine && m.body == input.text("body") } } }
            }
            "drive", "snapshot", "restart-check", "receive" -> observedConsent(boxes, input) {
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
            try { if (root.packageName?.toString() == CAMBIUM) root.findAccessibilityNodeInfosByViewId("$CAMBIUM:id/$id") else emptyList() }
            finally { @Suppress("DEPRECATION") root.recycle() }
        }.orEmpty()
        fun retainedRoot() = nodes("pairingNpubValue").let { rows ->
            try { rows.size == 1 && rows.single().packageName?.toString() == CAMBIUM && rows.single().text?.toString() == npub }
            finally { rows.forEach { @Suppress("DEPRECATION") it.recycle() } }
        }
        suspend fun reveal(id: String) {
            repeat(8) {
                if (nodes(id).let { rows ->
                    try { rows.size == 1 && rows.single().isVisibleToUser && rows.single().isEnabled }
                    finally { rows.forEach { @Suppress("DEPRECATION") it.recycle() } }
                }) return
                val root = requireNotNull(automation.rootInActiveWindow)
                try {
                    check(root.packageName?.toString() == CAMBIUM)
                    val scrolling = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
                    fun collect(node: android.view.accessibility.AccessibilityNodeInfo) {
                        if (node.packageName?.toString() == CAMBIUM && node.isScrollable && node.isVisibleToUser) scrolling += node
                        for (i in 0 until node.childCount) node.getChild(i)?.let(::collect)
                    }
                    collect(root)
                    check(scrolling.size == 1) { "One owned Cambium viewport required." }
                    check(scrolling.single().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
                } finally { @Suppress("DEPRECATION") root.recycle() }
                delay(250)
            }
            error("Bounded visible Cambium control not found.")
        }
        fun setUri(value: String): Boolean = nodes("bunkerInput").singleOrNull()?.let { node ->
            try { node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply { putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }) }
            finally { @Suppress("DEPRECATION") node.recycle() }
        } ?: false
        app.startActivity(android.content.Intent.makeMainActivity(android.content.ComponentName(CAMBIUM, "$CAMBIUM.MainActivity")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        await { retainedRoot() }
        // Missing/damaged/another pairing is a refusal, never an instruction to reset or repair.
        check(retainedRoot())
        reveal("bunkerInput")
        await { nodes("bunkerInput").any { node -> try { node.isVisibleToUser && node.isEnabled && node.isEditable } finally { @Suppress("DEPRECATION") node.recycle() } } }
        try {
            check(setUri(uri))
            await { nodes("bunkerInput").any { node -> try { node.text?.toString() == uri } finally { @Suppress("DEPRECATION") node.recycle() } } }
            reveal("pairButton")
            check(retainedRoot())
            val button = nodes("pairButton").single()
            try {
                check(button.isVisibleToUser && button.isEnabled && button.isClickable)
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
                val identityMatched = retainedRoot()
                cleared && ready && !progressing && identityMatched
            }
            return buildJsonObject { put("paired", true); put("persona", persona); put("publicIdentityMatched", true); put("currentSetupCompleted", true); put("retainedSoleRoot", true) }
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
    private fun validConsentToken(value: String) = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
    private suspend fun <T> observedConsent(boxes: VmlsBoxes, input: JsonObject, action: suspend () -> T): T {
        val token = validConsentToken(input.text("consentToken"))
        check(boxes.consent.value == null) { "A prior vault consent is still pending." }
        val observer = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            boxes.consent.collect { scope ->
                if (scope != null) exchange("result", buildJsonObject {
                    put("step", "consent-review"); put("ok", true)
                    put("result", consentReview(token, scope))
                })
            }
        }
        try { return action() } finally { observer.cancelAndJoin() }
    }
    /** Only reviewed static identifiers cross the private evidence boundary.
     * Never walk causes or export exception messages, recovery strings or input.
     */
    private fun refusalDto(name: String, failure: Throwable): JsonObject {
        val nativeCodes = mapOf(
            "AwaitingCommitAck" to 161U, "NotActive" to 162U,
            "NeedsRecovery" to 163U, "CommitInFlight" to 164U,
            "UpdateRequired" to 165U, "UnknownMember" to 166U,
            "BadInput" to 174U, "OperationExpired" to 178U,
            "StaleOperation" to 179U, "ReceiptUnverified" to 182U,
            "UnsupportedSecurityContract" to 183U,
            "AdminRequired" to 195U, "InvalidAdminSet" to 196U,
        )
        val native = failure as? dev.forgesworn.vmls.ffi.VmlsException.Engine
        if (native != null && nativeCodes[native.code] == native.number) return buildJsonObject {
            put("source", "native-engine"); put("code", native.code); put("number", native.number.toInt())
        }
        val session = failure as? EngineSessionException
        if (session != null && session.code in nativeCodes) return buildJsonObject {
            put("source", "engine-session"); put("code", session.code)
        }
        adminPrecheckRefusal(name, failure)?.let { evidence -> return buildJsonObject {
            put("source", "app-admin-precheck"); put("code", evidence.code); put("origin", evidence.origin)
        } }
        return buildJsonObject { put("source", "unclassified"); put("code", "Unclassified") }
    }
    private fun adminRoles(roles: ExperimentalAdminRoles) = buildJsonObject {
        put("profile", "Vennel/invitation-admins/2"); put("policy", "designated-leaf-admins/1")
        put("epoch", roles.epoch); put("commitment", roles.commitment)
        put("ownLeaf", roles.ownLeaf); put("ownIsAdmin", roles.ownIsAdmin)
        put("devices", buildJsonArray { roles.devices.forEach { member -> add(buildJsonObject {
            put("leaf", member.leaf); put("persona", member.persona); put("device", member.device); put("admin", member.admin)
        }) } })
    }
    private suspend fun <T> observedAdminInvitationConsent(boxes: VmlsBoxes, input: JsonObject, action: suspend () -> T): T {
        val token = validConsentToken(input.text("consentToken"))
        check(boxes.adminInvitationConsent.value == null) { "A prior Admin2 consent is still pending." }
        val observer = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
            boxes.adminInvitationConsent.collect { scope ->
                if (scope != null) exchange("result", buildJsonObject {
                    put("step", "admin-consent-review"); put("ok", true)
                    put("result", adminConsentReview(token, scope))
                })
            }
        }
        try { return action() } finally { observer.cancelAndJoin() }
    }
    // Private controller only; exact correlated public scope for the human UI
    // review. No capability, signing body, scalar, or automatic approval.
    private fun adminConsentReview(token: String, scope: AdminInvitationConsent) = buildJsonObject {
        val c = scope.context
        put("consentToken", token); put("principal", scope.principal); put("persona", scope.persona)
        put("device", scope.device); put("credentialId", scope.credentialId); put("deadline", scope.deadline)
        put("profile", c.profile); put("policy", c.policy); put("adminSetCommitment", c.adminSetCommitment)
        put("invitationId", c.invitationId); put("group", c.group); put("groupHome", c.groupHome); put("installation", c.installation)
        put("inviterPersona", c.inviterPersona); put("inviterDevice", c.inviterDevice); put("inviterRendezvous", c.inviterRendezvous)
        put("joinerPersona", c.joinerPersona); put("joinerDevice", c.joinerDevice); put("joinerRendezvous", c.joinerRendezvous)
        put("leafHome", c.leafHome); put("returnNode", c.returnNode); put("expiresAt", c.expiresAt)
    }
    private fun consentReview(token: String, scope: ConsentScope) = buildJsonObject {
        put("consentToken", token)
        put("principal", scope.principal)
        put("persona", scope.persona)
        put("device", scope.device)
        put("homeBox", scope.homeBox)
        put("method", scope.method)
    }
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
