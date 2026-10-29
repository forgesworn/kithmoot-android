package dev.forgesworn.kithmoot.mixed

import android.view.accessibility.AccessibilityNodeInfo as Node
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.account.ConsentScope
import dev.forgesworn.kithmoot.account.MlsVault
import kotlinx.coroutines.*

/** Uses the existing instrumentation owner; every approval is a visible product button. */
internal class ProductApprovalDriver(
    private val root: String, private val npub: String, private val home: String?,
    private val target: String?, private val existingDevice: String?, private val checkRoot: () -> Unit,
    private val consent: () -> ConsentScope?, private val clock: () -> Long,
) {
    private val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
    private val cam = "dev.forgesworn.cambium"
    private val app = "dev.forgesworn.kithmoot"
    var credential: ProductApprovalReview.Credential? = null; private set
    var provision: ProductApprovalReview.Provision? = null; private set
    val approved = mutableListOf<String>()
    val vaultScopes = mutableListOf<ConsentScope>()
    private fun nodes(pkg: String): List<Node> {
        val window = ui.rootInActiveWindow ?: return emptyList()
        if (window.packageName?.toString() != pkg) return emptyList()
        val found = mutableListOf<Node>()
        fun visit(node: Node) { if (node.packageName?.toString() == pkg) found += node; for (i in 0 until node.childCount) node.getChild(i)?.let(::visit) }
        visit(window); return found
    }
    private fun id(name: String) = nodes(cam).singleOrNull { it.viewIdResourceName == "$cam:id/$name" }
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
        reveal("approvalLifetimePicker"); click(requireNotNull(id("approvalLifetimePicker")))
        until { nodes(cam).any { it.text?.toString() == "This request only" && it.isVisibleToUser } }
        click(nodes(cam).single { it.text?.toString() == "This request only" && it.isVisibleToUser })
        until { sheet(method) && hasText(id("approvalLifetimePicker"), "This request only") }
    }
    private suspend fun details(): String {
        reveal("requestDetailsButton", backwards = true); click(requireNotNull(id("requestDetailsButton")))
        until { nodes(cam).any { it.text?.toString()?.startsWith(ProductApprovalReview.NOTE) == true } }
        val text = nodes(cam).single { it.text?.toString()?.startsWith(ProductApprovalReview.NOTE) == true }.text.toString()
        click(requireNotNull(nodes(cam).singleOrNull { it.viewIdResourceName == "android:id/button1" && it.text?.toString() == "OK" }))
        until { id("approvalScroll") != null && id("methodValue") != null }
        return text
    }
    private suspend fun approveCam(type: String) {
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
        val owned = nodes(app)
        fun short(value: String) = value.take(8) + "…" + value.takeLast(8)
        check(owned.any { it.text?.toString() == title && it.isVisibleToUser })
        check(owned.any { it.text?.toString() == "Box ${short(scope.homeBox)}" } && owned.any { it.text?.toString() == "Device ${short(scope.device)}" })
        val allow = owned.single { it.text?.toString() == "Allow" && it.isVisibleToUser && it.isEnabled }
        check(consent() == scope); checkRoot(); click(allow); vaultScopes += scope
        until { consent() != scope }
    }
    suspend fun <T> run(kind: String, action: suspend () -> T): T = coroutineScope {
        ui.serviceInfo = ui.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val pending = async { action() }
        withTimeout(180_000) {
            while (!pending.isCompleted) {
                val method = id("methodValue")?.text?.toString()
                when {
                    method == "get_public_key" && kind == "device" && "permissions" !in approved -> approveCam("permissions")
                    method == "sign_event" && kind == "device" && "credential" !in approved -> approveCam("credential")
                    method == "Provision one rendezvous child" && kind == "provision" && "provision" !in approved -> approveCam("provision")
                    consent() != null -> allowVault(requireNotNull(consent()))
                    else -> delay(50)
                }
            }
            val result = pending.await()
            checkRoot()
            if (kind == "device") check(approved == listOf("permissions", "credential"))
            if (kind == "provision") check(approved == listOf("provision"))
            result
        }
    }
}
