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
import dev.forgesworn.kithmoot.crypto.hexToBytes
import dev.forgesworn.kithmoot.crypto.toHex
import dev.forgesworn.kithmoot.mls.*
import dev.forgesworn.kithmoot.ui.RoomViewModel
import dev.forgesworn.kithmoot.ui.Stage
import dev.forgesworn.kithmoot.protocol.decodeInvitationUrl
import dev.forgesworn.vmls.ffi.VmlsEvent
import dev.forgesworn.vmls.ffi.VmlsMember
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64

private val safeProductFailureFrames = setOf(
    "dev.forgesworn.kithmoot.mixed.ProductApprovalDriver.allowVault",
    "dev.forgesworn.kithmoot.mixed.ProductApprovalDriver.allowAdmin",
    "dev.forgesworn.kithmoot.mixed.ProductApprovalDriver\$run\$2.invokeSuspend",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.run_private_product_step",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.runObservedProductLoop",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.runProductStep",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.runStep",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.metadataControl",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.ordinaryStep",
    "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest.exchange",
    "dev.forgesworn.kithmoot.lab.MetadataObserver.arm",
    "dev.forgesworn.kithmoot.lab.MetadataSession.<init>",
    "dev.forgesworn.kithmoot.mls.VmlsRuntime.issuePrivateAdminInvitation",
)

internal fun safeProductFailureTrace(failure: Throwable): String {
    val causes = generateSequence(failure as Throwable?) { current -> current.cause?.takeIf { it !== current } }
    val stage = causes.mapNotNull { current ->
        Regex("Approval stage: (start|cam-lifetime|cam-details|cam-permissions|cam-credential|cam-provision|admin-navigation|admin-consent)")
            .matchEntire(current.message.orEmpty())?.groupValues?.get(1)
    }.firstOrNull()
    val http = causes.mapNotNull { current -> Regex("HTTP ([0-9]{3})").matchEntire(current.message.orEmpty())?.groupValues?.get(1) }.firstOrNull()
    val metadataControl = causes.mapNotNull { current ->
        Regex("MetadataControl opened=(true|false) eosed=(true|false) closed=(true|false)").matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val ordinarySend = causes.mapNotNull { current ->
        Regex("OrdinarySend stage=(START|ROOM) pending=([0-9]+) sending=(true|false) errorPresent=(true|false)(?: preview=([ -~]{0,80}))?").matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val ordinaryNewIdentityJoin = causes.mapNotNull { current ->
        Regex("OrdinaryNewIdentityJoin publication=(true|false) held=(true|false) update=([a-z]+|null) tiles=([0-9]+) guest=(true|false) busy=(true|false) errorPresent=(true|false) epoch=([A-Za-z]+)").matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val ordinaryOpenSavedRoom = causes.mapNotNull { current ->
        Regex("OrdinaryOpenSavedRoom stage=(START|ROOM) roomMatches=(true|false) busy=(true|false) savedRoom=(true|false) accountPresent=(true|false) errorPresent=(true|false)").matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val welcome = causes.mapNotNull { current ->
        Regex("WelcomeEvidence candidates=([0-9]+) distinct=([0-9]+) delivered=([0-9]+) processed=([0-9]+) outbox=([0-9]+) welcomes=([0-9]+) slots=([0-9]+) phase=([A-Za-z]+) epoch=([0-9]+|null)")
            .matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val welcomeStaging = causes.mapNotNull { current -> Regex("WelcomeEvidence (staging|stagingException)=([A-Za-z]+)").matchEntire(current.message.orEmpty()) }.firstOrNull()
    val witness = causes.mapNotNull { current ->
        Regex("WitnessEvidence build=(true|false) account=(true|false) phase=([A-Za-z]+) status=([A-Za-z]+) error=(true|false)").matchEntire(current.message.orEmpty())
    }.firstOrNull()
    val frames = generateSequence(failure as Throwable?) { current -> current.cause?.takeIf { it !== current } }
        .flatMap { it.stackTrace.asSequence() }
        .filter { "${it.className}.${it.methodName}" in safeProductFailureFrames }
        .take(24)
        .joinToString(" | ") { "${it.className}.${it.methodName}:${it.lineNumber}" }
    return listOfNotNull(stage?.let { "stage=$it" }, http?.let { "http=$it" },
        metadataControl?.let { "metadata-opened=${it.groupValues[1]},metadata-eosed=${it.groupValues[2]},metadata-closed=${it.groupValues[3]}" },
        ordinarySend?.let { "ordinary-stage=${it.groupValues[1]},ordinary-pending=${it.groupValues[2]},ordinary-sending=${it.groupValues[3]},ordinary-error=${it.groupValues[4]},ordinary-preview=${it.groupValues[5]}" },
        ordinaryNewIdentityJoin?.let { "new-identity-publication=${it.groupValues[1]},new-identity-held=${it.groupValues[2]},new-identity-update=${it.groupValues[3]},new-identity-tiles=${it.groupValues[4]},new-identity-guest=${it.groupValues[5]},new-identity-busy=${it.groupValues[6]},new-identity-error=${it.groupValues[7]},new-identity-epoch=${it.groupValues[8]}" },
        ordinaryOpenSavedRoom?.let { "ordinary-open-stage=${it.groupValues[1]},ordinary-room-matches=${it.groupValues[2]},ordinary-busy=${it.groupValues[3]},ordinary-saved-room=${it.groupValues[4]},ordinary-account-present=${it.groupValues[5]},ordinary-open-error=${it.groupValues[6]}" },
        welcome?.let {
            "welcome-candidates=${it.groupValues[1]},welcome-distinct=${it.groupValues[2]},welcome-delivered=${it.groupValues[3]}," +
                "welcome-processed=${it.groupValues[4]},welcome-outbox=${it.groupValues[5]},welcome-welcomes=${it.groupValues[6]}," +
                "welcome-slots=${it.groupValues[7]},welcome-phase=${it.groupValues[8]},welcome-epoch=${it.groupValues[9]}"
        },
        welcomeStaging?.let { "welcome-${it.groupValues[1]}=${it.groupValues[2]}" },
        witness?.let { "witness-build=${it.groupValues[1]},witness-account=${it.groupValues[2]},witness-phase=${it.groupValues[3]},witness-status=${it.groupValues[4]},witness-error=${it.groupValues[5]}" },
        frames.takeIf { it.isNotEmpty() }).joinToString(" | ")
}

/** Test-APK-only private OOB controller for the actual application owners.
 * Vault consent stays with the app UI. The explicit signer-challenge step alone
 * drives one exact one-shot approval through this same instrumentation UI owner.
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

    @Test fun adminConsentDeadlineIsBoundedSeparatelyFromInvitationExpiry() {
        val hex = "ab".repeat(32)
        val context = dev.forgesworn.kithmoot.vmls.AdminInvitationSigningContext(hex, hex, hex, hex, hex, hex, hex,
            hex, hex, hex, hex, null, 2_000, hex)
        val scope = AdminInvitationConsent("dev.forgesworn.kithmoot", hex, hex, hex, 1_600, context)
        check(adminInvitationExpiryValid(scope, 2_000, 1_000))
        check(!adminInvitationExpiryValid(scope, 2_001, 1_000))
        check(!adminInvitationExpiryValid(scope.copy(deadline = 999), 2_000, 1_000))
        check(!adminInvitationExpiryValid(scope.copy(deadline = 1_601), 2_000, 1_000))
        check(!adminInvitationExpiryValid(scope.copy(deadline = 2_001), 2_000, 1_000))
    }

    @Test fun consentFailureEvidenceUsesOnlyAllowListedProductFrames() {
        val sensitive = "private-capability-must-never-be-exported"
        val actual = IllegalStateException(sensitive)
        actual.stackTrace = arrayOf(StackTraceElement("dev.forgesworn.kithmoot.mixed.ProductApprovalDriver", "allowVault", sensitive, 96))
        val dto = refusalDto("admin-accept", actual)
        check(dto.getValue("code").jsonPrimitive.content == "Unclassified")
        check(dto.getValue("origin").jsonPrimitive.content == "ProductApprovalDriver.allowVault:96")
        check(!dto.toString().contains(sensitive))
        actual.stackTrace = arrayOf(StackTraceElement("dev.forgesworn.kithmoot.mixed.ProductApprovalDriver", "allowAdmin", sensitive, 140))
        val adminDto = refusalDto("admin-issue", actual)
        check(adminDto.getValue("origin").jsonPrimitive.content == "ProductApprovalDriver.allowAdmin:140")
        check(!adminDto.toString().contains(sensitive))
        actual.stackTrace = arrayOf(StackTraceElement("dev.forgesworn.kithmoot.mls.VmlsRuntime", "issuePrivateAdminInvitation", sensitive, 904))
        val issueDto = refusalDto("admin-issue", actual)
        check(issueDto.getValue("origin").jsonPrimitive.content == "VmlsRuntime.issuePrivateAdminInvitation:904")
        check(!issueDto.toString().contains(sensitive))
        val safeTrace = safeProductFailureTrace(actual)
        check(safeTrace == "dev.forgesworn.kithmoot.mls.VmlsRuntime.issuePrivateAdminInvitation:904")
        check(!safeTrace.contains(sensitive))
        actual.stackTrace = arrayOf(StackTraceElement("dev.forgesworn.kithmoot.mixed.ProductApprovalDriver", sensitive, sensitive, 96))
        check("origin" !in refusalDto("admin-accept", actual))
        val staged = IllegalStateException("Approval stage: admin-consent", actual)
        staged.stackTrace = actual.stackTrace
        check(safeProductFailureTrace(staged) == "stage=admin-consent")
        check(!safeProductFailureTrace(staged).contains(sensitive))
        val injected = IllegalStateException("Approval stage: admin-consent\n$sensitive", actual)
        injected.stackTrace = actual.stackTrace
        check(!safeProductFailureTrace(injected).startsWith("stage="))
        check(!safeProductFailureTrace(injected).contains("Approval stage:"))
        check(!safeProductFailureTrace(injected).contains(sensitive))
        check(safeProductFailureTrace(IllegalStateException("MetadataControl opened=true eosed=false closed=false")) ==
            "metadata-opened=true,metadata-eosed=false,metadata-closed=false")
        check(safeProductFailureTrace(IllegalStateException("WelcomeEvidence staging=Offline")) == "welcome-staging=Offline")
        check(safeProductFailureTrace(IllegalArgumentException("WelcomeEvidence stagingException=IllegalArgumentException")) ==
            "welcome-stagingException=IllegalArgumentException")
        check(safeProductFailureTrace(IllegalStateException("WelcomeEvidence candidates=0 distinct=0 delivered=2 processed=1 outbox=3 welcomes=0 slots=2 phase=Active epoch=4")) ==
            "welcome-candidates=0,welcome-distinct=0,welcome-delivered=2,welcome-processed=1,welcome-outbox=3," +
            "welcome-welcomes=0,welcome-slots=2,welcome-phase=Active,welcome-epoch=4")
        check(safeProductFailureTrace(IllegalStateException("WitnessEvidence build=true account=false phase=None status=NotEnrolled error=false")) ==
            "witness-build=true,witness-account=false,witness-phase=None,witness-status=NotEnrolled,witness-error=false")
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
        if (step in setOf("signer-pair", "signer-challenge")) require(it.rawPath.matches(Regex("/[0-9a-f]{64}")))
    }.toString().removeSuffix("/")

    private val productSteps = setOf("witness-state", "witness-prepare", "witness-pair", "witness-genesis", "witness-check",
        "guest-home-pair", "provision-target", "provision", "target", "admin-node-check", "admin-review", "admin-accept", "admin-response",
        "admin-create", "admin-issue", "admin-import-response", "admin-registration-request", "admin-register", "admin-receipt-add", "admin-remove", "admin-roles", "admin-role-change",
        "admin-raw-send", "admin-raw-welcome", "admin-raw-process", "admin-restart-refusal", "send", "receive", "drive", "update", "snapshot", "restart-check", "metadata-control", "offline-drive")
    @Test fun run_private_product_step() = runBlocking {
        val name = step
        require(name in productSteps && URI(control).rawPath.matches(Regex("/[0-9a-f]{64}")))
        val input = exchange("step", buildJsonObject { put("step", name) })
        try {
            val result = withTimeout(240_000) { runProductStep(name, input) }
            if (input["crashBeforeAck"]?.jsonPrimitive?.boolean == true) {
                exchange("result", buildJsonObject {
                    put("step", "crash-before-ack"); put("ok", true)
                    put("result", buildJsonObject { put("code", 83) })
                })
                Runtime.getRuntime().halt(83)
            }
            exchange("result", buildJsonObject { put("step", name); put("ok", true); put("result", result) })
        } catch (failure: Throwable) {
            val diagnosed = if (!name.startsWith("witness-")) failure else {
                val state = app.restoreWitness?.state?.value
                val account = runCatching {
                    app.accounts.load()?.let { it.pubkey == input.text("expectedPersona") && it.method == "nip55" && it.signerPackage == CAMBIUM } == true
                }.getOrDefault(false)
                val build = dev.forgesworn.kithmoot.BuildConfig.DEBUG && dev.forgesworn.kithmoot.BuildConfig.INVITATION_PROFILE == "admin2" &&
                    dev.forgesworn.kithmoot.BuildConfig.ACCOUNT_NETWORK_DEFAULT == "private-invitations"
                IllegalStateException("WitnessEvidence build=$build account=$account phase=${state?.enrolment?.javaClass?.simpleName ?: "Unavailable"} " +
                    "status=${state?.status?.javaClass?.simpleName ?: "Unavailable"} error=${state?.error != null}", failure)
            }
            runCatching { exchange("result", buildJsonObject {
                put("step", name); put("ok", false); put("failure", failure.javaClass.simpleName)
                put("refusal", refusalDto(name, failure)); put("safeTrace", safeProductFailureTrace(diagnosed))
            }) }
            throw AssertionError("Protected product step failed (${failure.javaClass.simpleName}): ${safeProductFailureTrace(diagnosed)}")
        }
        Unit
    }
    /** One bounded process owns observation and normal product steps. Controller traffic is
     * HttpURLConnection on the exact private loopback channel, excluded from journey capture. */
    @Test fun run_private_observed_product_steps() {
        try { runBlocking { runObservedProductLoop() } }
        catch (failure: Throwable) { throw AssertionError("Owned metadata loop failed (${failure.javaClass.simpleName}: ${safeProductFailureTrace(failure)})") }
    }
    private suspend fun runObservedProductLoop() {
        check(dev.forgesworn.kithmoot.BuildConfig.DEBUG && dev.forgesworn.kithmoot.BuildConfig.LAB_METADATA_OBSERVER)
        require(step == "metadata-loop" && URI(control).port == 28546 && URI(control).rawPath.matches(Regex("/[0-9a-f]{64}")))
        val initialModel = model()
        await { initialModel.start.value.account != null && !initialModel.start.value.signingIn }
        val initialAccount = requireNotNull(app.accounts.load())
        check(initialAccount.method == "nip55" && initialAccount.signerPackage == CAMBIUM)
        val ready = exchange("metadata-ready", buildJsonObject { put("step", "metadata-loop"); put("schema", "kithmoot.metadata-loop.v1") })
        require(ready.keys == setOf("expectedPersona", "ownerStage", "loopNonce"))
        val expected = ready.text("expectedPersona").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        check(initialAccount.pubkey == expected && initialModel.start.value.account?.pubkey == expected)
        val stage = ready.text("ownerStage").also { require(it.matches(Regex("[a-z][a-z0-9-]{0,63}"))) }
        val nonce = ready.text("loopNonce").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val staleSpools = java.io.File(app.cacheDir, "metadata-spools")
        check(!staleSpools.exists() || staleSpools.deleteRecursively())
        quiesceMetadataAttempts()
        val allowed = setOf("target", "witness-check", "admin-review", "admin-accept", "admin-response", "admin-registration-review", "admin-roles", "admin-restart-refusal",
            "send", "receive", "receive-probe", "drive", "snapshot", "metadata-control", "ordinary-join", "ordinary-new-identity-join", "ordinary-send",
            "ordinary-receive", "ordinary-restart-check", "ordinary-snapshot", "ordinary-removed-check")
        var window: String? = null
        var pendingTerminal: JsonObject? = null
        var sequence = 1
        var completed = 0
        var waits = 0
        var finished = false
        var signedOutJoin = false
        val deadline = System.nanoTime() + 600_000_000_000L
        suspend fun rootGuard() {
            val model = model()
            if (signedOutJoin) {
                await { model.start.value.account == null && !model.start.value.signingIn }
                check(app.accounts.load() == null)
            } else {
                await { model.start.value.account != null && !model.start.value.signingIn }
                val saved = requireNotNull(app.accounts.load())
                check(saved.pubkey == expected && saved.method == "nip55" && saved.signerPackage == CAMBIUM && model.start.value.account?.pubkey == expected)
            }
        }
        try {
            withTimeout(600_000) {
                while (!finished) {
                    check(System.nanoTime() < deadline)
                    val command = exchange("metadata-command", buildJsonObject {
                        put("loopNonce", nonce); put("nextSequence", sequence); put("completedCommands", completed); put("observationArmed", window != null)
                    })
                    require(command.keys == setOf("sequence", "command", "input") && command.getValue("sequence").jsonPrimitive.int == sequence)
                    val name = command.text("command")
                    val input = command.getValue("input").jsonObject
                    val receiveProbeCommand = name == "product" && input["operation"]?.jsonPrimitive?.contentOrNull == "receive-probe"
                    val result: JsonObject
                    try {
                        if (name == "wait") {
                            require(++waits <= 600 && input.keys == setOf("delayMs"))
                            val delayMs = input.getValue("delayMs").jsonPrimitive.long.also { require(it in 0..1000) }
                            delay(delayMs); result = buildJsonObject { put("waited", true) }
                        } else {
                            require(name == "chunk" || receiveProbeCommand || completed < 32); rootGuard()
                            result = when (name) {
                                "arm" -> {
                                    require(window == null && input.keys == setOf("windowId", "terms", "saltHex", "actor", "durationMs", "actionSequence", "signerOrigin"))
                                    val saltHex = input.text("saltHex").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                                    val salt = saltHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                                    val id = input.text("windowId")
                                    val roleTerms = input.getValue("terms").jsonObject
                                    require(roleTerms.values.all { it is JsonPrimitive && it.isString })
                                    require(roleTerms.getValue(input.text("actor") + "RootHex").jsonPrimitive.content == expected)
                                    val spoolRoot = java.io.File(app.cacheDir, "metadata-spools")
                                    check(spoolRoot.isDirectory || spoolRoot.mkdir())
                                    val spoolDirectory = java.io.File(spoolRoot, id)
                                    check(spoolDirectory.canonicalFile.parentFile == spoolRoot.canonicalFile)
                                    try { dev.forgesworn.kithmoot.lab.MetadataObserver.arm(dev.forgesworn.kithmoot.lab.MetadataObserver.Spec(id,stage,
                                        input.getValue("terms").jsonObject.mapValues { it.value.jsonPrimitive.content },salt,input.text("actor"),
                                        input.getValue("durationMs").jsonPrimitive.long,input.getValue("actionSequence").jsonArray.map { it.jsonPrimitive.content },input.text("signerOrigin"),spoolDirectory.absolutePath))
                                        .also { window = id }
                                    } finally { salt.fill(0) }
                                }
                                "snapshot", "disarm" -> {
                                    require(input.keys == setOf("windowId") && input.text("windowId") == window)
                                    if (name == "snapshot") dev.forgesworn.kithmoot.lab.MetadataObserver.snapshot(stage,requireNotNull(window))
                                    else dev.forgesworn.kithmoot.lab.MetadataObserver.disarm(stage,requireNotNull(window)).also { pendingTerminal = it; window = null }
                                }
                                "chunk" -> {
                                    require(window == null && input.keys == setOf("windowId", "index"))
                                    dev.forgesworn.kithmoot.lab.MetadataObserver.chunk(stage, input.text("windowId"), input.getValue("index").jsonPrimitive.int)
                                }
                                "product" -> {
                                    require(input.keys == setOf("operation", "value"))
                                    val operation = input.text("operation").also { require(it in allowed) }
                                    val value = input.getValue("value").jsonObject
                                    require(value.text("expectedPersona") == expected)
                                    when {
                                        operation == "metadata-control" && signedOutJoin -> metadataControl(value).also { rootGuard() }
                                        operation.startsWith("ordinary-") -> ordinaryStep(operation, value, model(), expected).also {
                                            if (operation == "ordinary-new-identity-join") signedOutJoin = true
                                            rootGuard()
                                        }
                                        else -> runProductStep(operation,value).also { rootGuard() }
                                    }
                                }
                                "finish" -> {
                                    require(input.isEmpty() && window == null)
                                    val receipt = dev.forgesworn.kithmoot.lab.MetadataObserver.finish()
                                    finished = true; receipt
                                }
                                else -> error("Unsupported bounded metadata command")
                            }
                        }
                        check(System.nanoTime() < deadline)
                        val ack = exchange("metadata-result", buildJsonObject { put("loopNonce",nonce);put("sequence",sequence);put("command",name);put("ok",true);put("result",result) })
                        require(ack.keys == setOf("accepted","sequence") && ack.getValue("accepted").jsonPrimitive.boolean && ack.getValue("sequence").jsonPrimitive.int == sequence)
                        if (name == "disarm") pendingTerminal = null
                        if (name !in setOf("wait", "chunk") && !receiveProbeCommand) completed++
                        sequence++
                    } catch (failure: Throwable) {
                        val witnessState = app.restoreWitness?.state?.value
                        val diagnosed = if (witnessState == null) failure else {
                            val build = dev.forgesworn.kithmoot.BuildConfig.DEBUG && dev.forgesworn.kithmoot.BuildConfig.INVITATION_PROFILE == "admin2" &&
                                dev.forgesworn.kithmoot.BuildConfig.ACCOUNT_NETWORK_DEFAULT == "private-invitations"
                            val account = runCatching { app.accounts.load()?.pubkey == expected }.getOrDefault(false)
                            IllegalStateException("WitnessEvidence build=$build account=$account phase=${witnessState.enrolment?.javaClass?.simpleName ?: "Unavailable"} " +
                                "status=${witnessState.status?.javaClass?.simpleName ?: "Unavailable"} error=${witnessState.error != null}", failure)
                        }
                        runCatching { exchange("metadata-result", buildJsonObject { put("loopNonce",nonce);put("sequence",sequence);put("command",name);put("ok",false);put("failure",failure.javaClass.simpleName);put("safeTrace",safeProductFailureTrace(diagnosed)) }) }
                        throw failure
                    }
                }
            }
        } catch (failure: Throwable) {
            // Never let a product/controller exception render capabilities or payloads in JUnit output.
            throw AssertionError("Owned metadata loop failed (${failure.javaClass.simpleName})")
        } finally {
            var disarmFailure: String? = null
            val terminal = pendingTerminal ?: window?.let { id ->
                runCatching { dev.forgesworn.kithmoot.lab.MetadataObserver.disarm(stage,id) }
                    .onFailure { disarmFailure = it.javaClass.simpleName }.getOrNull()
            }
            var cleanup: JsonObject? = null
            var cleanupFailure: String? = null
            if (!finished) runCatching { dev.forgesworn.kithmoot.lab.MetadataObserver.cleanup() }
                .onSuccess { cleanup = it }.onFailure { cleanupFailure = it.javaClass.simpleName }
            if (terminal != null || disarmFailure != null || cleanup != null || cleanupFailure != null) {
                val summary = buildJsonObject {
                    put("snapshotAvailable", terminal != null)
                    disarmFailure?.let { put("failure", it) }
                    cleanup?.let {
                        put("cleanupComplete", it.getValue("cleanupComplete")); put("noPersistedManifest", it.getValue("noPersistedManifest"))
                    }
                    cleanupFailure?.let { put("cleanupComplete", false); put("cleanupFailure", it) }
                    terminal?.let { snapshot ->
                        for (key in listOf("errors", "overflow", "clockRegression", "capturedValueBytes", "endpointUrlReferenceBytes",
                            "rawFrameCount", "spool", "coverageGaps", "missingRecords", "activeCallbacks", "pendingAttempts", "captureBoundaryComplete", "positiveControls")) {
                            snapshot[key]?.let { put(key, it) }
                        }
                    }
                }
                runCatching { exchange("metadata-finally",buildJsonObject { put("loopNonce",nonce);put("sequence",sequence);put("forcedDisarm",true);put("result",summary) }) }
            }
            window = null
            pendingTerminal = null
        }
        check(finished)
        Unit
    }

    private suspend fun runProductStep(name: String, input: JsonObject): JsonObject {
        check(dev.forgesworn.kithmoot.BuildConfig.DEBUG && dev.forgesworn.kithmoot.BuildConfig.INVITATION_PROFILE == "admin2" &&
            dev.forgesworn.kithmoot.BuildConfig.ACCOUNT_NETWORK_DEFAULT == "private-invitations")
        val expected = input.text("expectedPersona").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val model = model()
        await { model.start.value.account != null && !model.start.value.signingIn }
        val saved = requireNotNull(app.accounts.load())
        check(saved.pubkey == expected && saved.method == "nip55" && saved.signerPackage == CAMBIUM)
        if (name == "metadata-control") return metadataControl(input)
        val signer = requireNotNull(model.vmlsSigner())
        fun live() { check(model.start.value.account?.pubkey == expected && model.vmlsSigner() === signer && app.accounts.load()?.pubkey == expected) }
        live()
        // Inspect the actual vault through its product factory without changing the preview preference.
        var witness = app.restoreWitness ?: requireNotNull(dev.forgesworn.kithmoot.account.restoreWitness(app))
        witness.open(expected)
        await { val state = witness.state.value; check(state.error == null); state.persona == expected && !state.busy && state.enrolment != null }
        val initial = witness.state.value.enrolment
        fun witnessResult(): JsonObject {
            val state = witness.state.value
            check(state.persona == expected && !state.busy && state.error == null)
            return buildJsonObject {
                put("persona", expected); put("provenance", "product-runtime"); put("visibleUiReadback", false)
                put("phase", when (state.enrolment) { WitnessEnrolment.None -> "None"; is WitnessEnrolment.Prepared -> "Prepared"; is WitnessEnrolment.Paired -> "Paired"; is WitnessEnrolment.Enrolled -> "Enrolled"; is WitnessEnrolment.Fenced -> "Fenced"; else -> error("Unknown witness phase") })
                put("status", when (state.status) { CoordinationStatus.Active -> "Active"; CoordinationStatus.NotEnrolled -> "NotEnrolled"; is CoordinationStatus.Pending -> "Pending"; is CoordinationStatus.Fenced -> "Fenced"; else -> "Unknown" })
                (state.enrolment as? WitnessEnrolment.Enrolled)?.let { enrolled ->
                    put("enrolLine", enrolled.line); put("box", enrolled.box)
                }
            }
        }
        if (name == "witness-state") return witnessResult().also { live() }
        when (name) {
            "witness-prepare" -> check(initial == WitnessEnrolment.None)
            "witness-pair" -> check(initial is WitnessEnrolment.Prepared)
            "witness-genesis" -> check(initial is WitnessEnrolment.Paired)
            "witness-check" -> check(initial is WitnessEnrolment.Enrolled)
            else -> check(initial is WitnessEnrolment.Enrolled && (name == "offline-drive" || name == "admin-raw-welcome" && input["stage"]?.jsonPrimitive?.boolean == true || witness.state.value.status == CoordinationStatus.Active))
        }
        live()
        // Same product preference setter only after the transition is checked; never a build switch.
        app.setVmlsPreview(true)
        witness = requireNotNull(app.restoreWitness)
        witness.open(expected)
        awaitWitness { it.persona == expected && it.enrolment != null }
        val core = EngineCore.of(app)
        val home = input["expectedHomeBox"]?.jsonPrimitive?.content?.also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        if (name == "guest-home-pair") {
            check(input.getValue("expectNewDevice").jsonPrimitive.boolean && home != null)
            check(dev.forgesworn.kithmoot.protocol.BothyPairing.parse(input.text("code"), System.currentTimeMillis() / 1000).linkNodeId == home)
            val before = core.vault.device(core.vault.context("dev.forgesworn.kithmoot", expected))
            check(before is VaultResult.Refused && before.refusal == VaultRefusal.Unauthorised)
        }
        val transport = saved.clientSecretKey?.let(Schnorr::publicKeyHex)
        if (name == "provision-target") {
            val target = requireNotNull(transport)
            val current = app.rendezvous.active(expected, target)
            try {
                live()
                return buildJsonObject {
                    put("persona", expected); put("provenance", "product-runtime"); put("targetDevice", target)
                    put("alreadyProtected", current != null)
                    current?.receipt?.let { receipt -> put("activeIndex", receipt.index); put("rendezvous", receipt.rendezvousPubkey) }
                }
            } finally { current?.wipe() }
        }
        val target = input["expectedTargetDevice"]?.jsonPrimitive?.content
        if (name == "provision") {
            check(input.long("index") == 0L && target != null && saved.clientSecretKey != null &&
                Schnorr.publicKeyHex(requireNotNull(saved.clientSecretKey)) == target)
            val current = app.rendezvous.active(expected, requireNotNull(target))
            try { check(current == null) { "A protected child already exists; inspect instead of reprovisioning." } }
            finally { current?.wipe() }
        }
        if (name == "guest-home-pair" || name in setOf("admin-node-check", "admin-accept", "admin-response", "admin-create", "admin-issue",
            "admin-import-response", "admin-registration-request", "admin-register", "admin-receipt-add", "admin-remove", "admin-role-change",
            "admin-raw-send", "admin-raw-welcome", "admin-raw-process", "admin-restart-refusal", "send", "receive", "receive-probe", "drive", "update", "snapshot", "restart-check", "offline-drive")) {
            require(home != null)
            validConsentToken(input.text("consentToken"))
        }
        val existingDevice = input["expectedDevice"]?.jsonPrimitive?.content
        if (existingDevice != null) check((core.vault.device(core.vault.context("dev.forgesworn.kithmoot", expected)) as? VaultResult.Ok)?.value?.device == existingDevice)
        val adminRoom = if (name == "admin-issue") adminRoomScope(requireNotNull(app.vmlsBoxes), expected, input.text("session")) else null
        val driver = ProductApprovalDriver(expected, saved.npub, home, target, existingDevice, ::live,
            { app.vmlsBoxes?.consent?.value }, { System.currentTimeMillis() / 1000 },
            { app.vmlsBoxes?.adminInvitationConsent?.value }, { input }, adminRoom)
        val kind = when (name) { "guest-home-pair" -> "device"; "provision" -> "provision"; "admin-issue" -> "admin-invitation"; else -> "none" }
        val result = if (name == "offline-drive") {
            runStep(name, input).also { check(app.vmlsBoxes?.consent?.value == null) { "Offline route operation requested an approval." } }
        } else driver.run(kind) { live(); runStep(name, input) }
        live()
        if (name.startsWith("witness-")) return witnessResult()
        if (name == "guest-home-pair") check(result.getValue("device").jsonPrimitive.content == driver.credential?.device &&
            (core.vault.device(core.vault.context("dev.forgesworn.kithmoot", expected)) as? VaultResult.Ok)?.value?.device == driver.credential?.device)
        return buildJsonObject {
            result.forEach { (key, value) -> put(key, value) }
            put("persona", expected); put("provenance", "product-runtime"); put("visibleUiApprovals", buildJsonArray { driver.approved.forEach { add(it) } })
            driver.credential?.let { credential -> put("reviewedDevice", credential.device); put("credentialCreatedAt", credential.createdAt); put("credentialExpiresAt", credential.expiresAt) }
            driver.provision?.let { provision -> put("reviewedTarget", provision.target); put("reviewedNonce", provision.nonce); put("reviewedExpiresAt", provision.expiresAt) }
            put("vaultConsentReviews", buildJsonArray { driver.vaultScopes.forEach { add(consentReview(input.text("consentToken"), it)) } })
            put("adminInvitationConsentReviews", buildJsonArray { driver.adminScopes.forEach { add(adminConsentReview(input.text("consentToken"), it)) } })
        }
    }

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
        var offlineRound: String? = null
        var roundEventNames: List<String>? = null
        var receiveProbe: JsonObject? = null
        var updateObservation: JsonObject? = null
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
        if (name == "ordinary-restart-check" && input["signedOut"]?.jsonPrimitive?.boolean == true) {
            await { model.start.value.account == null && !model.start.value.signingIn }
            check(app.accounts.load() == null)
            launchedActivity?.close()
            launchedActivity = null
            val fresh = model()
            await { fresh.start.value.account == null && !fresh.start.value.signingIn }
            check(fresh.stage.value == Stage.START)
            return ordinaryStep(name, input, fresh, persona = input.text("expectedPersona"))
        }
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
        if (name == "account-network-private") {
            activityScenario.onActivity { model.setAccountNetworkMode(dev.forgesworn.kithmoot.account.AccountNetworkMode.PRIVATE_INVITATIONS) }
            await { model.start.value.accountNetworkMode == dev.forgesworn.kithmoot.account.AccountNetworkMode.PRIVATE_INVITATIONS }
            return buildJsonObject { put("mode", "private-invitations"); put("persona", persona) }
        }
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
            val event = coroutineScope {
                val pending = async(Dispatchers.IO) { signer.sign(1, createdAt, tags, content) }
                approveSignerChallenge(saved.npub, persona, createdAt, challenge, stage, content)
                checkedSignedEvent(pending.await(), persona, 1, createdAt, tags, content)
            }
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
            val relay = input.text("relay").also { require(it == "wss://link-lab.forgesworn.dev:28443") }
            var savedResult: String? = "Not invoked"
            activityScenario.onActivity {
                // The same Privacy switch and persistent relay editor save used by Settings.
                model.setProfilesEnabled(false)
                savedResult = model.saveAccountRelays(listOf(dev.forgesworn.kithmoot.relay.RelayChoice(relay)))
            }
            check(savedResult == null)
            val choices = model.accountRelayChoices()
            check(choices.size == 1 && choices.single().let { it.url.removeSuffix("/") == relay && it.read && it.write })
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
            "admin-raw-send" -> return observedConsent(boxes, input) { adminRawSend(boxes, persona, input) }
            "admin-raw-welcome" -> return observedConsent(boxes, input) { adminRawWelcome(boxes, persona, input) }
            "admin-raw-process" -> return observedConsent(boxes, input) { adminRawProcess(boxes, persona, input) }
            "admin-restart-refusal" -> return observedConsent(boxes, input) { adminRestartRefusal(boxes, persona, input) }
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
            "update" -> observedConsent(boxes, input) {
                val runtime = boxes as VmlsRuntime
                val observation = input["observeUpdate"]?.jsonPrimitive?.content
                if (observation == null) runtime.updating(persona, input.text("session"))
                else updateObservation = observeUpdate(runtime, persona, signer, input, observation)
            }
            "offline-drive" -> offlineRound = adminOfflineRound(boxes, persona, input)
            "drive", "snapshot", "restart-check", "receive", "receive-probe" -> observedConsent(boxes, input) {
                val rounds = (input["rounds"]?.jsonPrimitive?.int ?: 1).also {
                    require(it in (if (name == "snapshot") 0 else 1)..30)
                }
                if (name == "drive" && input["roundObservation"]?.jsonPrimitive?.boolean == true) {
                    roundEventNames = adminRoundAudit(boxes, persona, signer, rounds, input.text("session"), input.text("expectedHomeBox"))
                } else if (name != "receive-probe") repeat(rounds) { boxes.foregroundRounds(persona, signer); delay(100) }
                if (name == "receive") await {
                    boxes.foregroundRounds(persona, signer)
                    boxes.rooms.value.any { it.session == input.text("session") && it.messages.any { m -> !m.mine && m.body == input.text("body") } }
                }
                if (name == "receive-probe") {
                    val probeMs = input.long("probeMs").also { require(it in 1..15_000) }
                    val expectedDigest = Digests.sha256(input.text("body").toByteArray(Charsets.UTF_8)).toHex()
                    val target = input.text("session")
                    val started = System.nanoTime()
                    var roundsStarted = 0
                    var roundsCompleted = 0
                    var matched = false
                    var outcome = "timeout"
                    fun matchedNow() = boxes.rooms.value.any { room -> room.session == target && room.messages.any { message ->
                        !message.mine && Digests.sha256(message.body.toByteArray(Charsets.UTF_8)).toHex() == expectedDigest } }
                    try { withTimeout(probeMs) {
                        while (!matched) {
                            roundsStarted++
                            try { boxes.foregroundRounds(persona, signer); roundsCompleted++ }
                            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                            catch (failure: Throwable) { outcome = "round-" + failure.javaClass.simpleName; break }
                            matched = matchedNow()
                            if (matched) outcome = "matched" else { outcome = "not-matched"; delay(50) }
                        }
                    } } catch (_: TimeoutCancellationException) { if (!matched) outcome = "timeout" }
                    val room = boxes.rooms.value.firstOrNull { it.session == target }
                    receiveProbe = buildJsonObject {
                        put("matched", matched); put("expectedIncomingDigest", expectedDigest)
                        put("roundsStarted", roundsStarted); put("roundsCompleted", roundsCompleted)
                        put("elapsedMs", (System.nanoTime() - started) / 1_000_000)
                        put("targetSessionPresent", room != null); put("roomState", room?.state?.name ?: "Unavailable")
                        put("canSend", room?.canSend == true); put("consentPending", boxes.consent.value != null)
                        put("boxErrorPresent", boxes.state.value.error != null); put("lastRoundOutcome", outcome)
                    }
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
            if (offlineRound != null) put("offlineRound", offlineRound)
            receiveProbe?.let { put("receiveProbe", it) }
            updateObservation?.let { put("updateObservation", it) }
            roundEventNames?.let { names -> put("roundEventNames", buildJsonArray { names.forEach { add(it) } }) }
            if (name == "snapshot" && input["engineObservation"]?.jsonPrimitive?.boolean == true) {
                put("engineObservation", adminEngineObservation(boxes, persona, input.text("session")))
            }
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
                model.start.value.account?.pubkey?.let { put("accountSha256", digest(it)) }
                put("selfParticipantSha256", digest(room.selfParticipant))
                put("accountPrimary", model.start.value.account?.pubkey == room.selfParticipant && !room.secondary)
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
            fun diagnostics(failure: Throwable): IllegalStateException {
                val state = model.start.value
                return IllegalStateException("OrdinaryOpenSavedRoom stage=${model.stage.value} roomMatches=${model.room.value.roomId == id} " +
                    "busy=${state.busy} savedRoom=${state.savedRooms.any { it.id == id }} accountPresent=${state.account != null} errorPresent=${state.error != null}", failure)
            }
            if (model.stage.value == Stage.ROOM && model.room.value.roomId != id) {
                activityScenario.onActivity { model.leave() }
                try { await { model.stage.value == Stage.START && !model.start.value.busy } }
                catch (failure: Throwable) { throw diagnostics(failure) }
            }
            if (model.stage.value != Stage.ROOM || model.room.value.roomId != id || name == "ordinary-restart-check") {
                activityScenario.onActivity { model.reopenRoom(id) }
                try { await { model.stage.value == Stage.ROOM && model.room.value.roomId == id && !model.start.value.busy } }
                catch (failure: Throwable) { throw diagnostics(failure) }
            }
            return id
        }
        if (name == "ordinary-new-identity-join") {
            val id = input.text("room").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
            if (model.stage.value == Stage.ROOM) {
                activityScenario.onActivity { model.leave() }
                await { model.stage.value == Stage.START && !model.start.value.busy }
            }
            activityScenario.onActivity { model.forgetRoom(id) }
            await { model.start.value.savedRooms.none { it.id == id } && !model.start.value.busy }
            activityScenario.onActivity { model.signOutFromAccountMenu() }
            await { model.start.value.account == null && !model.start.value.signingIn }
            launchedActivity?.close()
            launchedActivity = null
            val fresh = model()
            await { fresh.start.value.account == null && !fresh.start.value.signingIn }
            check(fresh.stage.value == Stage.START)
            val joined = ordinaryStep("ordinary-join", input, fresh, persona)
            val live = RoomViewModel::class.java.getDeclaredField("session").apply { isAccessible = true }.get(fresh) as? dev.forgesworn.kithmoot.session.RoomSession
                ?: return joined
            val publicationAllowed = dev.forgesworn.kithmoot.session.RoomSession::class.java.getDeclaredField("publicationAllowed").apply { isAccessible = true }
            val keeperStartupHeld = dev.forgesworn.kithmoot.session.RoomSession::class.java.getDeclaredField("keeperStartupHeld").apply { isAccessible = true }
            try {
                await { publicationAllowed.getBoolean(live) && !keeperStartupHeld.getBoolean(live) && fresh.room.value.roomUpdate != "updating" }
            } catch (failure: Throwable) {
                throw IllegalStateException("OrdinaryNewIdentityJoin publication=${publicationAllowed.getBoolean(live)} held=${keeperStartupHeld.getBoolean(live)} update=${fresh.room.value.roomUpdate} tiles=${fresh.room.value.tiles.size} guest=${fresh.guestAdmission.value != null} busy=${fresh.start.value.busy} errorPresent=${fresh.start.value.error != null} epoch=${live.epochState.value.javaClass.simpleName}", failure)
            }
            return joined
        }
        when (name) {
            "ordinary-join" -> {
                activityScenario.onActivity { model.setAccountNetworkMode(dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR) }
                await { model.start.value.accountNetworkMode == dev.forgesworn.kithmoot.account.AccountNetworkMode.NOSTR }
                check(model.stage.value == Stage.START)
                val url = input.text("url") // Private loopback only; never return or log the bearer.
                val invite = requireNotNull(decodeInvitationUrl(url))
                try { check(invite.invitation.persistent) } finally { invite.invitation.bearer.fill(0) }
                activityScenario.onActivity { model.joinFromUrl(url) }
                await { model.stage.value == Stage.ROOM || model.guestAdmission.value != null || (!model.start.value.busy && model.start.value.error != null) }
                if (model.stage.value != Stage.ROOM) return buildJsonObject {
                    val entryError = model.start.value.error
                    put("status", if (model.guestAdmission.value != null) "awaiting-guest-action" else "entry-error")
                    put("refusal", when {
                        entryError?.startsWith("This invitation was retired.") == true -> "RevokedInvitation"
                        entryError == dev.forgesworn.kithmoot.account.NOSTR_MODE_REQUIRED -> "NostrModeRequired"
                        entryError == dev.forgesworn.kithmoot.session.INVITATION_NOT_FOUND -> "InvitationNotFound"
                        entryError == "The room could not be opened. Try again." -> "RoomOpenFailed"
                        entryError == "The room is not answering this invitation. Ask for a fresh link." -> "RoomNotAnswering"
                        entryError == "This group invitation names conflicting rooms. Ask for a current link." -> "ConflictingRooms"
                        entryError == "Access could not be restored. Keep another member online and try again." -> "AccessRestoreFailed"
                        entryError == "That is not a join link." -> "InvalidJoinLink"
                        entryError == "This invitation admitted a different room." -> "WrongRoom"
                        entryError == "Your request was declined. Ask someone in the room before trying again." -> "DeclinedInvitation"
                        entryError?.contains("auth", ignoreCase = true) == true -> "RelayAuthFailure"
                        entryError?.contains("certificate", ignoreCase = true) == true || entryError?.contains("tls", ignoreCase = true) == true -> "TlsFailure"
                        entryError?.contains("timeout", ignoreCase = true) == true || entryError?.contains("timed out", ignoreCase = true) == true -> "Timeout"
                        entryError?.contains("epoch", ignoreCase = true) == true -> "EpochFailure"
                        entryError?.contains("signer", ignoreCase = true) == true -> "SignerFailure"
                        entryError?.contains("relay", ignoreCase = true) == true -> "RelayFailure"
                        else -> "Unclassified"
                    })
                    put("errorSha256", digest(entryError ?: ""))
                    put("errorPreview", (entryError ?: "").split(Regex("\\s+")).joinToString(" ") { if (it.length > 12) "<redacted>" else it }.take(80))
                    put("shareUrlSha256", digest(url))
                }
                return buildJsonObject { put("status", "room-open"); put("shareUrlSha256", digest(url)); put("snapshot", snapshot()) }
            }
            "ordinary-removed-check" -> {
                val id = input.text("room").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
                launchedActivity?.close()
                launchedActivity = null
                val fresh = model()
                await { fresh.start.value.account?.pubkey == persona && !fresh.start.value.signingIn }
                activityScenario.onActivity { fresh.reopenRoom(id) }
                await { !fresh.start.value.busy && fresh.start.value.error != null }
                check(fresh.stage.value == Stage.START && fresh.start.value.error == "You were removed from this room")
                return buildJsonObject { put("status", "removed"); put("roomSha256", digest(id)); put("refusal", "RemovedParticipant") }
            }
            "ordinary-send" -> {
                try {
                    openSavedRoom()
                    val body = input.text("body").also { require(it.isNotBlank() && it.length <= 1200) }
                    val expected = digest(body)
                    val before = model.room.value.chat.map { it.id }.toSet()
                    activityScenario.onActivity { model.sendChat(body) }
                    await {
                        val room = model.room.value
                        room.chat.any { it.id !in before && it.participant == room.selfParticipant && digest(it.body) == expected } ||
                            room.pendingChats.any { it.messageId !in before && digest(it.text) == expected && it.state != dev.forgesworn.kithmoot.session.PendingChatState.MOVED }
                    }
                    val pending = model.room.value.pendingChats.any { it.messageId !in before && digest(it.text) == expected && it.state != dev.forgesworn.kithmoot.session.PendingChatState.MOVED }
                    return buildJsonObject { put("locallyRetainedBodySha256", expected); put("pendingObserved", pending); put("snapshot", snapshot()) }
                } catch (failure: Throwable) {
                    val room = model.room.value
                    val preview = room.chatSendError.orEmpty().split(Regex("\\s+")).joinToString(" ") { word ->
                        word.map { if (it.code in 32..126) it else '?' }.joinToString("").let { if (it.length > 12) "<redacted>" else it }
                    }.take(80)
                    throw IllegalStateException("OrdinarySend stage=${model.stage.value} pending=${room.pendingChats.size} sending=${room.chatSending} errorPresent=${room.chatSendError != null} preview=$preview", failure)
                }
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
    /** One UiAutomation owner initiates the request and reviews its actual frozen Cambium sheet. */
    private suspend fun approveSignerChallenge(npub: String, rootHex: String, createdAt: Long,
                                               challenge: String, stage: String, content: String) {
        val ui = instrumentation.uiAutomation
        ui.serviceInfo = ui.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        fun ownedNodes(): List<android.view.accessibility.AccessibilityNodeInfo> {
            val root = ui.rootInActiveWindow ?: return emptyList()
            if (root.packageName?.toString() != CAMBIUM) return emptyList()
            val found = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            fun visit(node: android.view.accessibility.AccessibilityNodeInfo) {
                if (node.packageName?.toString() == CAMBIUM) found += node
                for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
            }
            visit(root)
            return found
        }
        fun id(name: String) = ownedNodes().singleOrNull { it.viewIdResourceName == "$CAMBIUM:id/$name" }
        fun hasText(node: android.view.accessibility.AccessibilityNodeInfo?, text: String): Boolean =
            node != null && (node.text?.toString() == text || (0 until node.childCount).any { hasText(node.getChild(it), text) })
        fun click(node: android.view.accessibility.AccessibilityNodeInfo) {
            check(node.isVisibleToUser && node.isEnabled)
            var target: android.view.accessibility.AccessibilityNodeInfo? = node
            while (target != null && !target.isClickable) target = target.parent
            check(requireNotNull(target).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        fun scope(once: Boolean) = id("methodValue")?.text?.toString() == "sign_event" &&
            id("appValue")?.text?.toString() == "KithMoot" && id("kindValue")?.text?.toString() == "1" &&
            id("singleIdentityValue")?.text?.toString() == npub &&
            id("identityPicker")?.isVisibleToUser != true &&
            (!once || hasText(id("approvalLifetimePicker"), "This request only"))
        suspend fun reveal(name: String, backwards: Boolean = false) {
            repeat(8) {
                if (id(name)?.let { it.isVisibleToUser && it.isEnabled } == true) return
                val scroll = requireNotNull(id("approvalScroll"))
                check(scroll.isScrollable)
                val action = if (backwards) android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                check(scroll.performAction(action))
                delay(250)
            }
            error("Bounded signer approval control unavailable.")
        }
        val preview = "These details come from the requesting app. They are shown here only and are never written to the activity log.\n\n" +
            "Kind: 1\nAuthor: $rootHex\nCreated at (Unix seconds): $createdAt\nContent:\n$content\nTags: 3\n" +
            "challenge | $challenge\nstage | $stage\nt | vennel-signer-continuity-lab\n" +
            "This is a shortened preview. Heartwood validates the signing request."
        suspend fun checkDetails() {
            reveal("requestDetailsButton", backwards = true)
            click(requireNotNull(id("requestDetailsButton")))
            await { ownedNodes().any { it.text?.toString() == preview } }
            val ok = ownedNodes().singleOrNull { it.viewIdResourceName == "android:id/button1" && it.text?.toString() == "OK" }
            click(requireNotNull(ok))
            await { scope(once = false) } // Wait for dialog dismissal before querying the sheet.
        }
        withTimeout(90_000) {
            await { scope(once = false) }
            checkDetails()
            reveal("approvalLifetimePicker")
            click(requireNotNull(id("approvalLifetimePicker")))
            await { ownedNodes().any { it.text?.toString() == "This request only" && it.isVisibleToUser } }
            click(ownedNodes().single { it.text?.toString() == "This request only" && it.isVisibleToUser })
            await { scope(once = true) }
            // Recheck the complete exact template after changing the lifetime.
            checkDetails()
            reveal("approveButton")
            check(scope(once = true))
            click(requireNotNull(id("approveButton")))
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
        // Native frozen ScrollView retains clipped child rows: inspect all owned rows,
        // while every mutating control must independently be revealed and visible.
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
        return buildJsonObject {
            put("source", "unclassified"); put("code", "Unclassified")
            productFailureOrigin(failure)?.let { put("origin", it) }
        }
    }
    private fun productFailureOrigin(failure: Throwable): String? {
        val owners = mapOf(
            "dev.forgesworn.kithmoot.mixed.ProductApprovalDriver" to "ProductApprovalDriver",
            "dev.forgesworn.kithmoot.mixed.MixedInvitationAppTest" to "MixedInvitationAppTest",
            "dev.forgesworn.kithmoot.mls.VmlsRuntime" to "VmlsRuntime",
            "dev.forgesworn.kithmoot.account.EngineJoin" to "EngineJoin",
            "dev.forgesworn.kithmoot.account.SessionHost" to "SessionHost",
            "dev.forgesworn.kithmoot.account.RestoreWitness" to "RestoreWitness",
            "dev.forgesworn.kithmoot.account.MlsVault" to "MlsVault",
            "dev.forgesworn.kithmoot.account.PersonaCoordination" to "PersonaCoordination",
        )
        val methods = setOf("allowVault", "allowAdmin", "click", "run", "runProductStep", "runStep", "witnessResult", "adminOfflineRound",
            "issuePrivateAdminInvitation", "acceptPrivateAdminInvitation", "acceptAdminInvitation", "released", "createInvitation", "createWithContext", "commit",
            "prepare", "prepareCoordination", "refresh", "act", "open")
        val frame = failure.stackTrace.firstOrNull { frame ->
            frame.className in owners && methods.any { method -> frame.methodName == method || frame.methodName.startsWith("${method}$") }
        } ?: return null
        return "${owners.getValue(frame.className)}.${methods.first { method -> frame.methodName == method || frame.methodName.startsWith("${method}$") }}:${frame.lineNumber}"
    }
    private fun adminRoles(roles: ExperimentalAdminRoles) = buildJsonObject {
        put("profile", "Vennel/invitation-admins/2"); put("policy", "designated-leaf-admins/1")
        put("epoch", roles.epoch); put("commitment", roles.commitment)
        put("ownLeaf", roles.ownLeaf); put("ownIsAdmin", roles.ownIsAdmin)
        put("devices", buildJsonArray { roles.devices.forEach { member -> add(buildJsonObject {
            put("leaf", member.leaf); put("persona", member.persona); put("device", member.device); put("admin", member.admin)
        }) } })
    }
    private suspend fun quiesceMetadataAttempts() {
        val runtime = app.vmlsBoxes as? VmlsRuntime
        val quiet = runtime?.let { VmlsRuntime::class.java.getDeclaredField("quiet").apply { isAccessible = true }.get(it) as java.util.concurrent.atomic.AtomicBoolean }
        val previous = quiet?.get()
        val pending = dev.forgesworn.kithmoot.lab.MetadataObserver::class.java.getDeclaredField("pendingAttempts").apply { isAccessible = true }
        try {
            quiet?.set(true)
            await { pending.getInt(dev.forgesworn.kithmoot.lab.MetadataObserver) == 0 }
        } finally { if (previous != null) quiet?.set(previous) }
    }
    private class EventAuditList(private val backing: MutableList<Any>) : MutableList<Any> by backing {
        val names = mutableListOf<String>()
        override fun addAll(elements: Collection<Any>): Boolean {
            synchronized(names) { elements.forEach { names += it.javaClass.simpleName } }
            return backing.addAll(elements)
        }
    }
    private data class DriverAudit(
        val driven: Any,
        val driverField: java.lang.reflect.Field,
        val eventsField: java.lang.reflect.Field,
        val originalDriver: SessionDriver<EngineSession>,
        val originalEvents: MutableList<Any>,
        val events: EventAuditList,
        val host: SessionHost<EngineSession>,
        val client: VmlsBoxClient,
        val homeBox: ByteArray,
    )
    private data class WatchAudit(val phase: String, val epoch: String?, val watched: List<Watched>)
    private suspend fun adminRoundAudit(
        boxes: VmlsBoxes, persona: String, signer: ParticipantSigner?, rounds: Int, session: String, homeBox: String,
    ): List<String> {
        val runtime = boxes as VmlsRuntime
        val drivers = VmlsRuntime::class.java.getDeclaredField("drivers").apply { isAccessible = true }.get(runtime) as Map<*, *>
        val audits = drivers.values.map { nullableDriven ->
            val driven = requireNotNull(nullableDriven)
            val driverField = driven.javaClass.getDeclaredField("driver").apply { isAccessible = true }
            val eventsField = driven.javaClass.getDeclaredField("events").apply { isAccessible = true }
            val originalDriver = driverField.get(driven) as SessionDriver<EngineSession>
            val originalEvents = eventsField.get(driven) as MutableList<Any>
            val audit = EventAuditList(originalEvents)
            fun <T> driverField(name: String): T = SessionDriver::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(originalDriver) as T
            val host = driverField<SessionHost<EngineSession>>("host")
            val client = driverField<VmlsBoxClient>("client")
            val driverHomeBox = driverField<ByteArray>("homeBox")
            val replacement = SessionDriver<EngineSession>(
                host, client, driverHomeBox, driverField<CapabilitiesParser>("capabilities"),
                events = { audit.addAll(it) }, maxPages = driverField<Int>("maxPages"),
            )
            SessionDriver::class.java.getDeclaredField("pendingQueries").apply { isAccessible = true }
                .set(replacement, driverField<Any>("pendingQueries"))
            driverField.set(driven, replacement); eventsField.set(driven, audit)
            DriverAudit(driven, driverField, eventsField, originalDriver, originalEvents, audit, host, client, driverHomeBox)
        }
        val observations = mutableListOf<String>()
        try {
            repeat(rounds) { index ->
                boxes.foregroundRounds(persona, signer); delay(100)
                audits.filter { it.homeBox.contentEquals(homeBox.hexToBytes()) }.forEach { audit ->
                    val watched = (audit.host.step(persona, session.hexToBytes()) { opened ->
                        EngineStep(null, WatchAudit(opened.phase().javaClass.simpleName, opened.epoch()?.toString(), opened.watchList()))
                    } as? Hosted.Released)?.value ?: error("The protected vault held or refused the watch observation.")
                    val slots = watched.watched.mapNotNull { watch ->
                        (watch.kind as? Watched.Kind.Slot)?.let { slot -> watch.mailbox to slot.attempt }
                    }
                    observations += "round=${index + 1}:phase=${watched.phase}:epoch=${watched.epoch}:slots=${slots.size}"
                    slots.forEach { (mailbox, attempt) ->
                        val state = when (val answer = audit.client.slotStatus(mailbox, attempt)) {
                            is BoxAnswer.Ok -> "${answer.value.state.name}@${answer.value.attempt}"
                            is BoxAnswer.Refused -> "Refused(${answer.status}:${answer.code})"
                            else -> answer.javaClass.simpleName
                        }
                        observations += "round=${index + 1}:slot@${attempt}:sha256=${Digests.sha256(mailbox).toHex()}=$state"
                    }
                }
            }
        } finally {
            audits.forEach {
                it.driverField.set(it.driven, it.originalDriver)
                it.eventsField.set(it.driven, it.originalEvents)
            }
        }
        return audits.flatMap { synchronized(it.events.names) { it.events.names.toList() } } + observations
    }
    private fun adminEngineHost(boxes: VmlsBoxes, persona: String): SessionHost<EngineSession> {
        val runtime = boxes as VmlsRuntime
        val engines = VmlsRuntime::class.java.getDeclaredField("engines").apply { isAccessible = true }.get(runtime) as Map<*, *>
        val engine = requireNotNull(engines[persona])
        return engine.javaClass.getDeclaredField("host").apply { isAccessible = true }.get(engine) as SessionHost<EngineSession>
    }
    private suspend fun observeUpdate(
        runtime: VmlsRuntime, persona: String, signer: ParticipantSigner?, input: JsonObject, observation: String,
    ): JsonObject {
        check(observation == "race" || observation == "retry")
        val session = validConsentToken(input.text("session"))
        val home = validConsentToken(input.text("expectedHomeBox"))
        val epoch = input.text("expectedEpoch").toLong().also { require(it >= 0) }
        val sessionBytes = session.hexToBytes()
        val homeBytes = home.hexToBytes()
        val rounding = VmlsRuntime::class.java.getDeclaredField("rounding").apply { isAccessible = true }.get(runtime) as Mutex
        runtime.foregroundRounds(persona, signer)
        fun routeDriver(): SessionDriver<*> {
            val drivers = VmlsRuntime::class.java.getDeclaredField("drivers").apply { isAccessible = true }.get(runtime) as Map<*, *>
            val driven = requireNotNull(drivers["$persona:$home"])
            return driven.javaClass.getDeclaredField("driver").apply { isAccessible = true }.get(driven) as SessionDriver<*>
        }
        val (host, driver) = rounding.withLock {
            // The callback is per route. Require exactly this room for the event attribution.
            check(runtime.store.rooms().filter { it.persona == persona && it.box == home }.single().session == session)
            adminEngineHost(runtime, persona) to routeDriver()
        }
        val client = SessionDriver::class.java.getDeclaredField("client").apply { isAccessible = true }.get(driver) as VmlsBoxClient
        val signerField = VmlsBoxClient::class.java.getDeclaredField("signer").apply { isAccessible = true }
        val originalSigner = signerField.get(client) as BoxRequestSigner
        val eventsField = SessionDriver::class.java.getDeclaredField("events").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val originalEvents = eventsField.get(driver) as (List<Any>) -> Unit
        val outcomes = mutableListOf<RoomSignal>()
        val capture: (List<Any>) -> Unit = { events ->
            originalEvents(events)
            outcomes += events.map(::roomSignal).filter { it is RoomSignal.CommitAccepted || it is RoomSignal.CommitLost }
        }
        suspend fun isSettled(at: Long): Boolean {
            val result = host.step(persona, sessionBytes) { opened ->
                EngineStep(null, opened.phase() == Phase.Active && opened.epoch() == at &&
                    opened.outbox().none { it.destination is Destination.Slot })
            }
            return (result as? Hosted.Released)?.value == true
        }
        try {
            rounding.withLock {
                check(routeDriver() === driver && isSettled(epoch)) { "Update source epoch is not settled." }
                // Every driver round starts with a signed capabilities request. Refuse it locally
                // while staging so Compose foreground rounds cannot deliver or fetch this slot.
                // The separate leaf-binding consent and witnessed Update still use the product vault.
                signerField.set(client, BoxRequestSigner { VaultResult.Refused(VaultRefusal.Busy) })
                eventsField.set(driver, capture)
            }
            runtime.updating(persona, session)
            val pending = rounding.withLock {
                check(routeDriver() === driver && outcomes.isEmpty())
                val staged = host.step(persona, sessionBytes) { opened ->
                    check(opened.phase() == Phase.Active && opened.epoch() == epoch)
                    val record = opened.outbox().single { it.destination is Destination.Slot }
                    val slot = record.destination as Destination.Slot
                    check(slot.epoch == epoch && slot.box.contentEquals(homeBytes) && slot.attempt >= 0)
                    EngineStep(null, buildJsonObject {
                        put("sourceEpoch", epoch.toString())
                        put("slotSha256", Digests.sha256(record.mailbox).toHex())
                        put("attempt", slot.attempt.toString())
                        put("commitSha256", Digests.sha256(record.envelope).toHex())
                    })
                }
                (staged as? Hosted.Released)?.value ?: error("The protected Update was not staged.")
            }
            if (observation == "race") {
                val release = withContext(Dispatchers.IO) {
                    exchange("result", buildJsonObject {
                        put("step", "commit-staged"); put("ok", true); put("result", pending)
                    }, 135_000)
                }
                check(release.getValue("received").jsonPrimitive.boolean)
            }
            rounding.withLock {
                check(routeDriver() === driver && outcomes.isEmpty())
                signerField.set(client, originalSigner)
            }
            repeat(6) { runtime.foregroundRounds(persona, signer); delay(100) }
            val event = rounding.withLock {
                check(routeDriver() === driver && isSettled(Math.addExact(epoch, 1)))
                outcomes.singleOrNull() ?: error("The product driver did not observe exactly one commit outcome.")
            }
            return buildJsonObject {
                put("pending", pending)
                put("outcome", buildJsonObject {
                    when (event) {
                        is RoomSignal.CommitAccepted -> {
                            check(event.kind == CommitKind.UPDATE && event.epoch == epoch + 1)
                            put("code", "CommitAccepted"); put("kind", event.kind.name); put("epoch", event.epoch.toString())
                        }
                        is RoomSignal.CommitLost -> {
                            check(observation == "race" && event.kind == CommitKind.UPDATE)
                            put("code", "CommitLost"); put("kind", event.kind.name)
                        }
                        else -> error("Unknown commit outcome.")
                    }
                })
            }
        } finally {
            withContext(NonCancellable) {
                rounding.withLock {
                    signerField.set(client, originalSigner)
                    eventsField.set(driver, originalEvents)
                }
            }
        }
    }
    private suspend fun adminEngineObservation(boxes: VmlsBoxes, persona: String, session: String): JsonObject {
        val result = adminEngineHost(boxes, persona).step(persona, session.hexToBytes()) { opened ->
            val members = opened.inner.members()
            val ownLeaf = opened.inner.ownLeafId()
            EngineStep(null, buildJsonObject {
                put("phase", opened.phase().javaClass.simpleName)
                put("epoch", opened.epoch()?.toString())
                put("memberCount", members.size)
                put("ownMemberCount", members.count { it.own })
                put("ownLeafPresent", members.any { it.member.leafId.contentEquals(ownLeaf) })
            })
        }
        return (result as? Hosted.Released)?.value ?: error("The protected vault held or refused the engine observation.")
    }
    private fun adminEngineSessions(boxes: VmlsBoxes, persona: String): EngineSessions {
        val runtime = boxes as VmlsRuntime
        val engines = VmlsRuntime::class.java.getDeclaredField("engines").apply { isAccessible = true }.get(runtime) as Map<*, *>
        val engine = requireNotNull(engines[persona])
        return engine.javaClass.getDeclaredField("sessions").apply { isAccessible = true }.get(engine) as EngineSessions
    }
    private suspend fun adminOfflineRound(boxes: VmlsBoxes, persona: String, input: JsonObject): String {
        boxes.open(persona)
        boxes.foregroundRounds(persona)
        val runtime = boxes as VmlsRuntime
        val drivers = VmlsRuntime::class.java.getDeclaredField("drivers").apply { isAccessible = true }.get(runtime) as Map<*, *>
        val driven = requireNotNull(drivers["$persona:${input.text("expectedHomeBox")}"])
        val driver = driven.javaClass.getDeclaredField("driver").apply { isAccessible = true }.get(driven) as SessionDriver<EngineSession>
        exchange("result", buildJsonObject { put("step", "offline-ready"); put("ok", true) }, 150_000)
        input["body"]?.jsonPrimitive?.content?.let { body ->
            boxes.say(persona, input.text("session"), body)
            await { boxes.rooms.value.any { it.session == input.text("session") && it.messages.any { message -> message.mine && message.body == body } } }
        }
        var outcome = "NotRun"
        repeat((input["rounds"]?.jsonPrimitive?.int ?: 1).also { require(it in 1..30) }) {
            try {
                outcome = driver.round(persona, input.text("session").hexToBytes(), System.currentTimeMillis() / 1000).javaClass.simpleName
            } catch (_: IllegalStateException) {
                outcome = "Refused"
            } catch (_: IllegalArgumentException) {
                outcome = "Refused"
            }
        }
        return outcome
    }
    private suspend fun adminRestartRefusal(boxes: VmlsBoxes, persona: String, input: JsonObject): JsonObject {
        boxes.open(persona)
        val host = adminEngineHost(boxes, persona)
        val sessions = adminEngineSessions(boxes, persona)
        val result = host.step(persona, input.text("session").hexToBytes()) { opened ->
            val sent = sessionCall { opened.inner.send("B4 trusted snapshot for restored-state refusal".toByteArray()) }
            val snapshot = requireNotNull(sent.snapshot)
            val refusals = buildJsonArray {
                for ((label, disposition) in listOf(
                    "DetectedImport" to dev.forgesworn.vmls.ffi.VmlsRestartDisposition.DETECTED_IMPORT,
                    "DetectedClone" to dev.forgesworn.vmls.ffi.VmlsRestartDisposition.DETECTED_CLONE,
                    "DetectedStateLoss" to dev.forgesworn.vmls.ffi.VmlsRestartDisposition.DETECTED_STATE_LOSS,
                )) {
                    val plaintext = snapshot.plaintext.copyOf()
                    val refusal = try {
                        dev.forgesworn.vmls.ffi.openAdminInvitationSession(sessions.platform, snapshot.session,
                            plaintext, snapshot.generation, disposition).use { null }
                    } catch (error: dev.forgesworn.vmls.ffi.VmlsException.Engine) { error }
                    finally { plaintext.fill(0) }
                    check(refusal?.code == "NeedsRecovery" && refusal.recovery == "Rollback")
                    add(buildJsonObject {
                        put("disposition", label); put("code", refusal.code); put("recovery", refusal.recovery)
                        put("sessionOpened", false); put("sendExecuted", false)
                    })
                }
            }
            EngineStep(hostedStep(sent).snapshot, buildJsonObject {
                put("snapshotGeneration", snapshot.generation.toString()); put("refusals", refusals)
            })
        }
        return (result as? Hosted.Released)?.value ?: error("The protected vault held or refused the restored-state evidence.")
    }
    private suspend fun metadataControl(input: JsonObject): JsonObject {
        val id = input.text("controlId").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        require(input.text("phase") in setOf("start", "end"))
        val relay = input.text("relay").also { require(it == "wss://link-lab.forgesworn.dev:28443") }
        val req = buildJsonArray {
            add("REQ"); add(id); add(buildJsonObject {
                put("kinds", buildJsonArray { add(1) }); put("limit", 1)
            })
        }.toString()
        val eose = buildJsonArray { add("EOSE"); add(id) }.toString()
        val close = buildJsonArray { add("CLOSE"); add(id) }.toString()
        var opened = false
        var eosed = false
        var closed = false
        lateinit var socket: dev.forgesworn.kithmoot.relay.RelaySocket
        try {
            socket = dev.forgesworn.kithmoot.relay.OkHttpRelaySockets().open(relay,
                object : dev.forgesworn.kithmoot.relay.RelaySocketListener {
                    override fun onOpen() { opened = true; socket.send(req) }
                    override fun onMessage(text: String) {
                        if (text == eose) { eosed = true; socket.send(close); socket.close() }
                    }
                    override fun onClosed(reason: String) { closed = true }
                })
            withTimeout(8_000) { await { opened && eosed && closed } }
        } catch (failure: Throwable) {
            throw IllegalStateException("MetadataControl opened=$opened eosed=$eosed closed=$closed", failure)
        } finally { runCatching { socket.close() } }
        return buildJsonObject { put("req", req); put("eose", eose); put("close", close) }
    }
    private fun rawBytes(value: ByteArray) = Base64.getEncoder().encodeToString(value)
    private suspend fun adminRawSend(boxes: VmlsBoxes, persona: String, input: JsonObject): JsonObject {
        boxes.open(persona)
        if (input["stageForRecipient"]?.jsonPrimitive?.boolean != true) {
            return sendRawRecord(boxes, persona, input)
        }
        // The instrumented activity may have just started. Initialise the
        // witnessed engine through the product before waiting on its driver.
        boxes.foregroundRounds(persona)
        // Keep this exact committed record in the sender's outbox until the
        // other built client reports its result via the private controller.
        // The activity's foreground driver takes this same lock before sending.
        val rounding = VmlsRuntime::class.java.getDeclaredField("rounding").apply { isAccessible = true }.get(boxes) as Mutex
        return rounding.withLock {
            val record = sendRawRecord(boxes, persona, input)
            val reply = withContext(Dispatchers.IO) {
                exchange("result", buildJsonObject {
                    put("step", "raw-record-staged")
                    put("ok", true)
                    put("result", record)
                }, 145_000)
            }
            check(reply.getValue("received").jsonPrimitive.boolean)
            record
        }
    }
    private suspend fun sendRawRecord(boxes: VmlsBoxes, persona: String, input: JsonObject): JsonObject {
        val host = adminEngineHost(boxes, persona)
        val target = input.text("leaf").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        val body = input.text("body").also { require(it.length in 1..512) }
        val result = host.step(persona, input.text("session").hexToBytes()) { opened ->
            val step = sessionCall { opened.inner.send(body.toByteArray()) }
            val hosted = hostedStep(step)
            val evidence = buildJsonObject {
                put("epoch", opened.inner.epoch().toString())
                put("messages", buildJsonArray {
                    step.outbound.forEach { out ->
                        val destination = out.destination
                        if (destination is dev.forgesworn.vmls.ffi.VmlsDestination.Leaf && destination.leafId.toHex() == target) {
                            add(buildJsonObject {
                                put("leaf", target); put("recordId", out.recordId.toHex())
                                put("mailbox", rawBytes(out.mailbox)); put("envelope", rawBytes(out.envelope))
                            })
                        }
                    }
                })
            }
            EngineStep(hosted.snapshot, evidence)
        }
        return (result as? Hosted.Released)?.value?.also {
            check(it.getValue("messages").jsonArray.size == 1) { "Exactly one ciphertext for the target leaf required" }
        } ?: error("The protected vault held or refused the raw message evidence.")
    }
    private suspend fun adminRawWelcome(boxes: VmlsBoxes, persona: String, input: JsonObject): JsonObject {
        boxes.open(persona)
        val host = adminEngineHost(boxes, persona)
        val stage = input["stage"]?.jsonPrimitive?.boolean == true
        var staged: Round.Done? = null
        var stagingDriver: SessionDriver<EngineSession>? = null
        suspend fun stageRound() {
            try {
                if (stagingDriver == null) {
                    val runtime = boxes as VmlsRuntime
                    val drivers = VmlsRuntime::class.java.getDeclaredField("drivers").apply { isAccessible = true }.get(runtime) as Map<*, *>
                    val driven = requireNotNull(drivers["$persona:${input.text("expectedHomeBox")}"])
                    stagingDriver = driven.javaClass.getDeclaredField("driver").apply { isAccessible = true }.get(driven) as SessionDriver<EngineSession>
                }
                val round = requireNotNull(stagingDriver).round(persona, input.text("session").hexToBytes(), System.currentTimeMillis() / 1000)
                check(round is Round.Done) { "WelcomeEvidence staging=${round.javaClass.simpleName}" }
                staged = round
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: RuntimeException) {
                if (failure.message?.startsWith("WelcomeEvidence staging=") == true) throw failure
                throw IllegalArgumentException("WelcomeEvidence stagingException=${failure.javaClass.simpleName}", failure)
            }
        }
        val packageId = input.text("packageId").also { require(it.matches(Regex("[0-9a-f]{64}"))) }
        suspend fun capture(): Triple<JsonObject, Int, String> {
            val result = host.step(persona, input.text("session").hexToBytes()) { opened ->
                val outbox = opened.outbox()
                val candidates = outbox.filter {
                    val destination = it.destination
                    destination is Destination.Welcome && destination.packageId.toHex() == packageId
                }
                val distinct = candidates.distinctBy { it.mailbox.contentToString() + it.envelope.contentToString() }
                val diagnostic = "WelcomeEvidence candidates=${candidates.size} distinct=${distinct.size} delivered=${staged?.delivered} processed=${staged?.processed} outbox=${outbox.size} welcomes=${outbox.count { it.destination is Destination.Welcome }} slots=${outbox.count { it.destination is Destination.Slot }} phase=${opened.phase().javaClass.simpleName} epoch=${opened.epoch()}"
                val welcome = distinct.singleOrNull()
                EngineStep(null, buildJsonObject {
                    put("candidateCount", candidates.size); put("distinctWelcomeCount", distinct.size)
                    put("packageId", packageId)
                    if (welcome != null) {
                        put("recordId", welcome.recordId.toHex())
                        put("mailbox", rawBytes(welcome.mailbox)); put("envelope", rawBytes(welcome.envelope))
                    }
                } to diagnostic)
            }
            val (value, diagnostic) = (result as? Hosted.Released)?.value ?: error("The protected vault held or refused the Welcome evidence.")
            return Triple(value, value.getValue("distinctWelcomeCount").jsonPrimitive.int, diagnostic)
        }
        if (stage) stageRound()
        val first = capture()
        if (stage && first.second == 0) {
            stageRound()
            val second = capture()
            require(second.second == 1) { second.third }
            return second.first
        }
        require(first.second == 1) { first.third }
        return first.first
    }
    private fun rawMessageEvents(events: List<VmlsEvent>) = buildJsonArray {
        events.filterIsInstance<VmlsEvent.Message>().forEach { message ->
            add(buildJsonObject {
                put("senderLeaf", message.sender.leafId.toHex())
                put("senderPersona", message.sender.identity.toHex())
                put("senderDevice", message.sender.device.toHex())
                put("epoch", message.epoch.toString())
                put("bodySha256", Digests.sha256(message.body).toHex())
            })
        }
    }

    @Test fun rawMessageEvidenceBindsNativeSenderAndHashesBody() {
        val sender = VmlsMember(ByteArray(32) { 1 }, ByteArray(32) { 2 }, ByteArray(32) { 3 })
        val event = VmlsEvent.Message(sender, 4uL, "abc".encodeToByteArray())
        val expected = buildJsonObject {
            put("senderLeaf", "01".repeat(32))
            put("senderPersona", "02".repeat(32))
            put("senderDevice", "03".repeat(32))
            put("epoch", "4")
            put("bodySha256", "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        }
        check(rawMessageEvents(listOf(VmlsEvent.Joined(4uL), event)) == buildJsonArray { add(expected) })
        check(rawMessageEvents(emptyList()).isEmpty())
    }

    private suspend fun adminRawProcess(boxes: VmlsBoxes, persona: String, input: JsonObject): JsonObject {
        boxes.open(persona)
        val observeMessage = input["observeMessage"]?.jsonPrimitive?.boolean == true
        if (observeMessage) boxes.foregroundRounds(persona)
        val host = adminEngineHost(boxes, persona)
        val decode = { key: String -> Base64.getDecoder().decode(input.text(key)) }
        val mailbox = decode("mailbox").also { require(it.size == 32) }
        val envelope = decode("envelope").also { require(it.size in 1..1_048_576) }
        val node = input["node"]?.jsonPrimitive?.content?.hexToBytes()
        val installation = input["installation"]?.jsonPrimitive?.content?.hexToBytes()
        require((node == null) == (installation == null) && (node == null || node.size == 32 && installation!!.size == 32))
        val boxInstallation = if (node == null) null else node to requireNotNull(installation)
        val result = host.step(persona, input.text("session").hexToBytes()) { opened ->
            fun evidence(outcome: String, refused: String?, events: List<VmlsEvent>) = buildJsonObject {
                put("outcome", outcome); put("refused", refused); put("eventCount", events.size)
                put("phase", opened.phase().javaClass.simpleName); put("epoch", opened.epoch()?.toString())
                if (observeMessage) {
                    put("recipientLeaf", opened.inner.ownLeafId().toHex())
                    put("mailboxSha256", Digests.sha256(mailbox).toHex())
                    put("envelopeSha256", Digests.sha256(envelope).toHex())
                    put("messageEvents", rawMessageEvents(events))
                }
            }
            val processed = try {
                opened.inner.process((System.currentTimeMillis() / 1000).toULong(), mailbox, envelope, null,
                    boxInstallation?.let { dev.forgesworn.vmls.ffi.VmlsBoxInstallation(it.first, it.second) })
            } catch (refused: dev.forgesworn.vmls.ffi.VmlsException.Engine) {
                return@step EngineStep(null, evidence("Refused", refused.code, emptyList()))
            }
            val step = hostedStep(processed.step)
            EngineStep(step.snapshot, evidence(processed.outcome.javaClass.simpleName,
                (processed.outcome as? dev.forgesworn.vmls.ffi.VmlsOutcome.Dropped)?.code, processed.step.events))
        }
        return (result as? Hosted.Released)?.value ?: error("The protected vault held or refused the raw process evidence.")
    }
    private suspend fun adminRoomScope(boxes: VmlsBoxes, persona: String, session: String): JsonObject {
        boxes.open(persona)
        boxes.currentAdminRoles(persona, session)
        val host = adminEngineHost(boxes, persona)
        val result = host.step(persona, session.hexToBytes()) { opened ->
            val roles = opened.inner.getCurrentAdminRolesV1()
            check(roles.ownIsAdmin)
            EngineStep(null, buildJsonObject {
                put("group", requireNotNull(opened.inner.groupId()).toHex())
                put("installation", requireNotNull(opened.inner.installation()).toHex())
                put("adminSetCommitment", roles.adminSetCommitment.toHex())
            })
        }
        return (result as? Hosted.Released)?.value ?: error("The protected vault held or refused the room review.")
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
    private fun exchange(path: String, body: JsonObject, readTimeoutMs: Int = 20_000): JsonObject {
        val connection = URI("$control/android/$path").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.connectTimeout = 5_000; connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
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
