package dev.forgesworn.kithmoot.mixed

import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.AdminInvitationConsent
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.MlsVault
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Uses the existing instrumentation owner; every approval is a visible product button. */
internal class ProductApprovalDriver(
    private val root: String, private val npub: String, private val home: String?,
    private val target: String?, private val existingDevice: String?, private val checkRoot: () -> Unit,
    private val consent: () -> ConsentScope?, private val clock: () -> Long,
    private val adminConsent: () -> AdminInvitationConsent? = { null }, private val adminInput: () -> JsonObject = { buildJsonObject {} },
    private val adminRoom: JsonObject? = null,
) {
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val cam = "dev.forgesworn.cambium"
    private val app = "dev.forgesworn.kithmoot"
    var credential: ProductApprovalReview.Credential? = null; private set
    var provision: ProductApprovalReview.Provision? = null; private set
    val approved = mutableListOf<String>()
    val vaultScopes = mutableListOf<ConsentScope>()
    val adminScopes = mutableListOf<AdminInvitationConsent>()
    private var stage = "start"
    private fun mark(value: String) { stage = value }
    private fun nodes(pkg: String): List<Node> {
        val window = ui.rootInActiveWindow ?: return emptyList()
        if (window.packageName?.toString() != pkg) return emptyList()
        val found = mutableListOf<Node>()
        fun visit(node: Node) { if (node.packageName?.toString() == pkg) found += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(window); return found
    }
    private fun id(name: String) = nodes(cam).singleOrNull { it.viewIdResourceName == "$cam:id/$name" }
    private fun visibleText(text: String, description: Boolean = false) = nodes(app).any {
        it.isVisibleToUser && it.isEnabled && (if (description) it.contentDescription?.toString()?.startsWith(text) == true else it.text?.toString() == text)
    }
    private suspend fun clickText(text: String, description: Boolean = false) {
        repeat(8) {
            val matches = nodes(app).filter {
                it.isVisibleToUser && it.isEnabled && (if (description) it.contentDescription?.toString()?.startsWith(text) == true else it.text?.toString() == text)
            }
            if (matches.size == 1) {
                delay(100)
                val settled = nodes(app).filter {
                    it.isVisibleToUser && it.isEnabled && (if (description) it.contentDescription?.toString()?.startsWith(text) == true else it.text?.toString() == text)
                }
                if (settled.size == 1) {
                    val bounds = android.graphics.Rect()
                    settled.single().getBoundsInScreen(bounds)
                    val x = bounds.centerX().toFloat()
                    val y = bounds.centerY().toFloat()
                    val down = android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_DOWN, x, y, 0)
                    val up = android.view.MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(),
                        android.view.MotionEvent.ACTION_UP, x, y, 0)
                    try {
                        check(ui.injectInputEvent(down, true))
                        check(ui.injectInputEvent(up, true))
                    } finally { down.recycle(); up.recycle() }
                    return
                }
            }
            val scroll = nodes(app).firstOrNull { it.isScrollable }
            if (scroll != null) scroll.performAction(Node.ACTION_SCROLL_FORWARD)
            delay(250)
        }
        error("Private invitation navigation unavailable.")
    }
    private fun signerRequestVisible() = id("methodValue") != null
    private fun adminPromptVisible() = adminConsent() != null && visibleText("Sign this private invitation?")
    private fun navigationComplete() = signerRequestVisible() || adminPromptVisible()
    private suspend fun openAdminInvitationScreen() {
        mark("admin-navigation")
        if (visibleText("Private invitations") || navigationComplete()) return
        clickText("Account menu", description = true)
        if (navigationComplete()) return
        until { visibleText("Settings") }
        clickText("Settings")
        if (navigationComplete()) return
        until { visibleText("Private groups") }
        clickText("Private groups")
        if (navigationComplete()) return
        until { visibleText("Private invitations and paired home") }
        clickText("Private invitations and paired home")
        if (navigationComplete()) return
        until { visibleText("Private invitations") || navigationComplete() }
    }
    private fun hasText(node: Node?, text: String): Boolean = node != null &&
        (node.text?.toString() == text || (0 until node.childCount).any { hasText(node.getChild(it), text) })
    private fun click(node: Node) {
        checkRoot(); check(node.isVisibleToUser && node.isEnabled)
        var target: Node? = node
        while (target != null && !target.isClickable) target = target.parent
        check(requireNotNull(target).performAction(Node.ACTION_CLICK))
    }
    private suspend fun until(test: () -> Boolean) { withTimeout(45_000) { while (!test()) delay(50) } }
    private fun sheet(method: String) = id("methodValue")?.text?.toString() == method &&
        id("appValue")?.text?.toString() == "KithMoot" && id("singleIdentityValue")?.text?.toString() == npub &&
        id("identityPicker")?.isVisibleToUser != true
    private suspend fun reveal(name: String, backwards: Boolean = false) {
        until { id("approvalScroll") != null }
        repeat(8) {
            if (id(name)?.let { it.isVisibleToUser && it.isEnabled } == true) return
            val scroll = requireNotNull(id("approvalScroll")); check(scroll.isScrollable)
            check(scroll.performAction(if (backwards) Node.ACTION_SCROLL_BACKWARD else Node.ACTION_SCROLL_FORWARD)); delay(250)
        }
        error("Bounded product control unavailable.")
    }
    private suspend fun once(method: String) {
        mark("cam-lifetime")
        reveal("approvalLifetimePicker"); click(requireNotNull(id("approvalLifetimePicker")))
        until { nodes(cam).any { it.text?.toString() == "This request only" && it.isVisibleToUser } }
        click(nodes(cam).single { it.text?.toString() == "This request only" && it.isVisibleToUser })
        until { sheet(method) && hasText(id("approvalLifetimePicker"), "This request only") }
    }
    private suspend fun details(): String {
        mark("cam-details")
        reveal("requestDetailsButton", backwards = true); click(requireNotNull(id("requestDetailsButton")))
        until { nodes(cam).any { it.text?.toString()?.startsWith(ProductApprovalReview.NOTE) == true } }
        val text = nodes(cam).single { it.text?.toString()?.startsWith(ProductApprovalReview.NOTE) == true }.text.toString()
        click(requireNotNull(nodes(cam).singleOrNull { it.viewIdResourceName == "android:id/button1" && it.text?.toString() == "OK" }))
        until { id("approvalScroll") != null && id("methodValue") != null }
        return text
    }
    private suspend fun approveCam(type: String) {
        mark("cam-$type")
        val method = when (type) { "permissions" -> "get_public_key"; "credential" -> "sign_event"; else -> "Provision one rendezvous child" }
        check(sheet(method)); check(type !in approved)
        when (type) {
            "permissions" -> {
                check(id("permissionsValue")?.text?.toString() == ProductApprovalReview.PERMISSIONS)
                once(method)
            }
            "credential" -> {
                check(id("kindValue")?.text?.toString() == "20460")
                val first = ProductApprovalReview.credential(details(), root, clock())
                once(method)
                check(ProductApprovalReview.credential(details(), root, clock()) == first)
                credential = first
            }
            "provision" -> {
                check(target != null && id("approvalLifetimePicker")?.isVisibleToUser != true)
                val first = ProductApprovalReview.provision(id("requestSummary")?.text?.toString().orEmpty(), root, target, 0, clock())
                provision = first
            }
        }
        reveal("approveButton")
        check(sheet(method)); checkRoot()
        if (type != "provision") check(hasText(id("approvalLifetimePicker"), "This request only"))
        if (type == "permissions") check(id("permissionsValue")?.text?.toString() == ProductApprovalReview.PERMISSIONS)
        if (type == "provision") check(ProductApprovalReview.provision(id("requestSummary")?.text?.toString().orEmpty(), root, requireNotNull(target), 0, clock()) == provision)
        click(requireNotNull(id("approveButton"))); approved += type
        until { id("approveButton")?.isVisibleToUser != true || id("approveButton")?.isEnabled == false || id("methodValue")?.text?.toString() != method }
    }
    private suspend fun allowVault(scope: ConsentScope) {
        val device = credential?.device ?: existingDevice
        checkRoot(); check(ProductApprovalReview.vaultScope(scope.principal, scope.persona, scope.device, scope.homeBox, scope.method, root, device, home))
        check(vaultScopes.none { it == scope })
        val title = if (scope.method == MlsVault.SIGN_METHOD) "Sign for a VMLS room?" else "Talk to your box?"
        until { consent() == scope && nodes(app).any { it.text?.toString() == title && it.isVisibleToUser } }
        val owned = nodes(app)
        fun short(value: String) = value.take(8) + "…" + value.takeLast(8)
        check(owned.any { it.text?.toString() == title && it.isVisibleToUser })
        check(owned.any { it.text?.toString() == "Box ${short(scope.homeBox)}" } && owned.any { it.text?.toString() == "Device ${short(scope.device)}" })
        val allow = owned.single { it.text?.toString() == "Allow" && it.isVisibleToUser && it.isEnabled }
        check(consent() == scope); checkRoot(); click(allow); vaultScopes += scope
        until { consent() != scope }
    }
    private suspend fun allowAdmin(scope: AdminInvitationConsent) {
        mark("admin-consent")
        val input = adminInput()
        val room = requireNotNull(adminRoom)
        val c = scope.context
        checkRoot()
        check(scope.principal == app && scope.persona == root && scope.device == existingDevice && scope.credentialId.matches(Regex("[0-9a-f]{64}")))
        check(c.profile == "Vennel/invitation-admins/2" && c.policy == "designated-leaf-admins/1")
        check(c.inviterPersona == root && c.inviterDevice == existingDevice && c.inviterRendezvous == input.text("inviterRendezvous"))
        check(c.joinerPersona == input.text("persona") && c.joinerDevice == input.text("device") && c.joinerRendezvous == input.text("rendezvous"))
        check(c.groupHome == home && c.leafHome == input.text("leafHome") && c.returnNode == null)
        check(c.group == room.text("group") && c.installation == room.text("installation") && c.adminSetCommitment == room.text("adminSetCommitment"))
        check(adminInvitationExpiryValid(scope, input.long("expiresAt"), clock()))
        check(listOf(c.adminSetCommitment, c.invitationId, c.group, c.installation).all { it.matches(Regex("[0-9a-f]{64}")) })
        check(adminScopes.none { it == scope })
        openAdminInvitationScreen()
        try { until { signerRequestVisible() || (adminConsent() == scope && nodes(app).any { it.text?.toString() == "Sign this private invitation?" && it.isVisibleToUser }) } }
        catch (timeout: TimeoutCancellationException) { throw IllegalStateException("Admin invitation prompt wait timed out", timeout) }
        if (signerRequestVisible()) return
        val expected = "Profile: ${c.profile}\nPolicy: ${c.policy}\nAdmin set: ${c.adminSetCommitment}\nInvitation: ${c.invitationId}\nPersona: ${scope.persona}\nDevice: ${scope.device}\nOriginal credential: ${scope.credentialId}\nGroup: ${c.group}\nGroup home: ${c.groupHome}\nInstallation: ${c.installation}\nInviter rendezvous: ${c.inviterRendezvous}\nTarget persona: ${c.joinerPersona}\nTarget device: ${c.joinerDevice}\nTarget rendezvous: ${c.joinerRendezvous}\nTarget leaf home: ${c.leafHome}\nReturn: authenticated private OOB\nInvitation expires: ${c.expiresAt}\nSigning deadline: ${scope.deadline}"
        val scopeNode = nodes(app).single { it.text?.toString() == expected }
        var parent: Node? = scopeNode.parent
        while (parent != null && !parent.isScrollable) parent = parent.parent
        val viewport = requireNotNull(parent)
        fun scrollForward(): Boolean {
            val bounds = android.graphics.Rect()
            viewport.getBoundsInScreen(bounds)
            val x = bounds.centerX().toFloat()
            val startY = bounds.top + bounds.height() * 0.7f
            val endY = bounds.top + bounds.height() * 0.4f
            val now = android.os.SystemClock.uptimeMillis()
            val down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, startY, 0)
            val move = android.view.MotionEvent.obtain(now, now + 100, android.view.MotionEvent.ACTION_MOVE, x, endY, 0)
            val up = android.view.MotionEvent.obtain(now, now + 200, android.view.MotionEvent.ACTION_UP, x, endY, 0)
            try {
                return ui.injectInputEvent(down, true) && ui.injectInputEvent(move, true) && ui.injectInputEvent(up, true)
            } finally { down.recycle(); move.recycle(); up.recycle() }
        }
        for (attempt in 1..12) {
            if (nodes(app).any { it.text?.toString() == "This approval applies only to this invitation." && it.isVisibleToUser }) break
            check(scrollForward()); delay(250)
        }
        check(nodes(app).any { it.text?.toString() == "This approval applies only to this invitation." && it.isVisibleToUser })
        if (signerRequestVisible()) return
        val sign = nodes(app).single { it.text?.toString() == "Sign invitation" && it.isVisibleToUser && it.isEnabled }
        check(adminConsent() == scope); checkRoot(); click(sign); adminScopes += scope
        try { until { adminConsent() != scope } }
        catch (timeout: TimeoutCancellationException) { throw IllegalStateException("Admin invitation dismissal wait timed out", timeout) }
    }
    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long
    suspend fun <T> run(kind: String, action: suspend () -> T): T = coroutineScope {
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val pending = async { action() }
        try { withTimeout(180_000) {
            while (!pending.isCompleted) {
                val method = id("methodValue")?.text?.toString()
                when {
                    method == "get_public_key" && kind in setOf("device", "admin-invitation") && "permissions" !in approved -> approveCam("permissions")
                    method == "sign_event" && kind == "device" && "credential" !in approved -> approveCam("credential")
                    method == "Provision one rendezvous child" && kind == "provision" && "provision" !in approved -> approveCam("provision")
                    consent() != null -> allowVault(requireNotNull(consent()))
                    adminConsent() != null -> allowAdmin(requireNotNull(adminConsent()))
                    else -> delay(50)
                }
            }
            val result = pending.await()
            checkRoot()
            if (kind == "device") check(approved == listOf("permissions", "credential"))
            if (kind == "provision") check(approved == listOf("provision"))
            if (kind == "admin-invitation") check(adminScopes.size == 1)
            result
        } } catch (timeout: TimeoutCancellationException) { throw IllegalStateException("Approval stage: $stage", timeout) }
    }
}

internal fun adminInvitationExpiryValid(scope: AdminInvitationConsent, invitationExpiresAt: Long, now: Long): Boolean =
    scope.context.expiresAt == invitationExpiresAt && scope.deadline >= now &&
        scope.deadline <= now + MlsVault.MAX_OPERATION_SECONDS && scope.deadline <= scope.context.expiresAt
