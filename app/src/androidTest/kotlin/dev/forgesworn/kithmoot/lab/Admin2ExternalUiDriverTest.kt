package dev.forgesworn.kithmoot.lab

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.system.Os
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.forgesworn.kithmoot.BuildConfig
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Lab-only external UI journey. The host seeds admin2-external-ui.json in this new user's
 * target-app files directory through private stdin. This class never logs its contents.
 * No fixture signer, model mutation, direct signing, or accessibility dump is permitted.
 */
@RunWith(AndroidJUnit4::class)
class Admin2ExternalUiDriverTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext
    private val ui = instrumentation.uiAutomation
    private val cambium = "dev.forgesworn.cambium"
    private val kithmoot = "dev.forgesworn.kithmoot"
    private val signerRelay = "wss://link-lab.forgesworn.dev:28544"

    @Test fun retained_signin_private_networking() {
        val inputFile = File(app.filesDir, "admin2-external-ui.json")
        assertTrue("private input must be seeded in the target app", inputFile.isFile)
        val inputStat = Os.stat(inputFile.absolutePath)
        assertTrue("private input must be owner-only", inputStat.st_uid == Os.getuid() && (inputStat.st_mode and 511) == 384)
        val input = JSONObject(inputFile.readText())
        guard(input)
        try {
            journey(input)
        } finally {
            assertTrue("remove private input", inputFile.delete())
        }
    }

    private fun journey(input: JSONObject) {
        val expectedNpub = input.getString("expected_root_npub")
        val bunker = input.getString("bunker_uri")
        val relay = input.getString("public_relay_url")
        assertTrue("root public identity format", expectedNpub.matches(Regex("npub1[023456789acdefghjklmnpqrstuvwxyz]{58}")))
        val bunkerUri = Uri.parse(bunker)
        assertTrue("bunker URI format", bunkerUri.scheme == "bunker" && bunkerUri.fragment == null && bunkerUri.path.isNullOrEmpty())
        assertTrue("exact bunker root", bunkerUri.authority == input.getString("expected_root_hex"))
        assertTrue("exact signer relay URL", relay == signerRelay &&
            bunkerUri.queryParameterNames == setOf("relay", "secret") &&
            bunkerUri.getQueryParameters("relay") == listOf(signerRelay) &&
            bunkerUri.getQueryParameters("secret").size == 1 &&
            bunkerUri.getQueryParameter("secret")?.matches(Regex("[A-Za-z0-9_-]{43}")) == true)

        launch(cambium, "dev.forgesworn.cambium.MainActivity")
        await(cambium, "Cambium pairing state") { ownedId(cambium, "statusValue")?.text?.isNotBlank() == true }
        // This bounded journey preserves the known retained signer; an empty or damaged
        // pairing display must never turn into permission to pair again.
        assertTrue("exact retained signer root", cambiumPairedWith(expectedNpub))

        launch(kithmoot, "dev.forgesworn.kithmoot.MainActivity")
        await(kithmoot, "retained signed-in account menu") {
            byDescription(kithmoot, "Account menu") == null && byDescriptionPrefix(kithmoot, "Account menu for ") != null
        }
        click(requireNotNull(byDescriptionPrefix(kithmoot, "Account menu for ")))
        // Resume only the account adopted by the completed one-shot sign-in.
        // Never sign out, request the identity again, or repair a missing session.
        clickText(kithmoot, "Settings")
        clickText(kithmoot, expectedNpub.take(13) + "…" + expectedNpub.takeLast(6))
        await(kithmoot, "signed-in root") { byDescription(kithmoot, "Public key $expectedNpub") != null && visibleText(kithmoot, "Signing with Cambium on this phone. Rooms you open are joined as this account.") }

        clickText(kithmoot, "Back")
        clickText(kithmoot, "Privacy")
        await(kithmoot, "Privacy account networking section") { visibleText(kithmoot, "Account networking") }
        val privateNote = "Private invitations: Nostr contacts, inbox discovery, room sync and public profile lookups are paused. Your keys, saved rooms and pending changes stay on this device. Private groups and paired nodes keep working. Signer connections and update checks are separate."
        revealSettingsText(privateNote)
        assertTrue("private account networking before explicit choice", settingsText(kithmoot, privateNote).isNotEmpty())
        // Reveal the switch again if reading the note moved it out of view.
        revealSettingsText("Use Nostr account networking", backwards = true)
        val networkSwitch = requireCheckableSettingsRow("Use Nostr account networking")
        assertTrue("public account networking remains off", !networkSwitch.isChecked)
    }

    private fun guard(input: JSONObject) {
        assertTrue("target package", app.packageName == kithmoot)
        assertTrue("selected product profile", BuildConfig.INVITATION_PROFILE == "admin2" &&
            BuildConfig.ACCOUNT_NETWORK_DEFAULT == "private-invitations" &&
            !BuildConfig.EXPERIMENTAL_INVITATION_MLS && !BuildConfig.EXPERIMENTAL_ADMIN_INVITATION_MLS)
        val actualUser = Regex("^/data/user/([0-9]+)/dev\\.forgesworn\\.kithmoot$")
            .matchEntire(app.dataDir.absolutePath)?.groupValues?.get(1)?.toIntOrNull() ?: -1
        assertTrue("secondary user", actualUser > 0 && actualUser == input.getInt("user_id"))
        val foreground = ui.executeShellCommand("am get-current-user").use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText().trim() }
        }
        assertTrue("exact foreground user", foreground == actualUser.toString())
        val fingerprintSha256 = MessageDigest.getInstance("SHA-256")
            .digest(Build.FINGERPRINT.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertTrue("exact emulator hardware and fingerprint",
            Build.HARDWARE in setOf("ranchu", "goldfish") &&
                fingerprintSha256 == "50983ce8d3c867e878a322a2b3e4f7985ccfc1a1a4de564bed318f30f1e16fe9")
        val manager = app.getSystemService(Context.USER_SERVICE) as UserManager
        assertTrue("non-system user", !manager.isSystemUser)
        assertTrue("exact user serial", manager.getSerialNumberForUser(Process.myUserHandle()) == input.getLong("user_serial"))
        assertTrue("Cambium certificate", certificate(cambium) == input.getString("cambium_cert_sha256"))
        assertTrue("KithMoot certificate", certificate(kithmoot) == input.getString("kithmoot_cert_sha256"))
    }

    private fun certificate(pkg: String): String {
        val info = app.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val certs = requireNotNull(info.signingInfo).apkContentsSigners
        assertTrue("single signing certificate", certs.size == 1)
        return MessageDigest.getInstance("SHA-256").digest(certs.single().toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun launch(pkg: String, activity: String) {
        app.startActivity(Intent(Intent.ACTION_MAIN).setClassName(pkg, activity).addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        await(pkg, "app foreground") { ui.rootInActiveWindow?.packageName?.toString() == pkg }
    }

    private fun await(pkg: String, label: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 45_000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (ui.rootInActiveWindow?.packageName?.toString() == pkg && predicate()) return
            SystemClock.sleep(250)
        }
        throw AssertionError("UI step timed out: $label")
    }

    private fun nodes(pkg: String): List<AccessibilityNodeInfo> {
        val root = ui.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != pkg) return emptyList()
        val found = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect().also { node.getBoundsInScreen(it) }
            if (node.isVisibleToUser && !bounds.isEmpty && node.packageName?.toString() == pkg) found += node
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return found
    }

    // Frozen Cambium renders one exact npub resource per pairing; count every owned row,
    // including a row clipped by the scroll viewport. Never re-pair a retained identity.
    private fun cambiumPairingRows(): List<AccessibilityNodeInfo> {
        val root = ui.rootInActiveWindow ?: return emptyList()
        if (root.packageName?.toString() != cambium) return emptyList()
        return root.findAccessibilityNodeInfosByViewId("$cambium:id/pairingNpubValue")
            .filter { it.packageName?.toString() == cambium }
    }

    private fun cambiumPairedWith(expectedNpub: String): Boolean {
        val rows = cambiumPairingRows()
        return ownedId(cambium, "statusValue")?.text?.toString() == "1 signer paired" &&
            rows.size == 1 && rows.single().text?.toString() == expectedNpub
    }

    // Read the exact current owned sheet fields even when scrolling clips them off-screen.
    private fun ownedId(pkg: String, name: String): AccessibilityNodeInfo? {
        val root = ui.rootInActiveWindow ?: return null
        if (root.packageName?.toString() != pkg) return null
        return root.findAccessibilityNodeInfosByViewId("$pkg:id/$name")
            .singleOrNull { it.packageName?.toString() == pkg }
    }

    private fun hasText(node: AccessibilityNodeInfo, value: String): Boolean =
        node.text?.toString() == value || (0 until node.childCount).any { index ->
            node.getChild(index)?.let { hasText(it, value) } == true
        }

    private fun assertOneShotIdentityRequest(input: JSONObject, expectedNpub: String) {
        assertTrue("exact current identity request", ownedId(cambium, "methodValue")?.text?.toString() == "get_public_key" &&
            ownedId(cambium, "appValue")?.text?.toString() == input.getString("expected_caller_label") &&
            ownedId(cambium, "singleIdentityValue")?.text?.toString() == expectedNpub)
        assertTrue("exact current one-shot lifetime", ownedId(cambium, "approvalLifetimePicker")
            ?.let { hasText(it, "This request only") } == true)
    }

    private fun scrollToVisibleControl(pkg: String, scrollId: String, targetId: String) {
        repeat(8) {
            if (byId(pkg, targetId)?.let { it.isEnabled && it.isVisibleToUser } == true) return
            val scroll = requireNode(pkg, scrollId)
            assertTrue("exact approval sheet scrollable", scroll.isScrollable)
            val moved = scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(250)
            if (!moved) {
                assertTrue("visible approval at scroll boundary", byId(pkg, targetId)
                    ?.let { it.isEnabled && it.isVisibleToUser } == true)
                return
            }
        }
        assertTrue("bounded visible approval", byId(pkg, targetId)?.let { it.isEnabled && it.isVisibleToUser } == true)
    }

    private fun byId(pkg: String, name: String) = nodes(pkg).singleOrNull { it.viewIdResourceName == "$pkg:id/$name" }
    private fun byDescription(pkg: String, description: String) = nodes(pkg).singleOrNull { it.contentDescription?.toString() == description }
    private fun byDescriptionPrefix(pkg: String, prefix: String) = nodes(pkg).singleOrNull { it.contentDescription?.toString()?.startsWith(prefix) == true }
    private fun visibleText(pkg: String, value: String) = nodes(pkg).any { it.text?.toString() == value }
    private fun settingsText(pkg: String, value: String) = nodes(pkg).filter { node ->
        node.text?.toString()?.lineSequence()?.any { it == value } == true
    }

    private fun revealSettingsText(value: String, backwards: Boolean = false) {
        repeat(8) {
            // A click can finish before Compose publishes the destination accessibility tree.
            repeat(4) {
                if (settingsText(kithmoot, value).isNotEmpty()) return
                SystemClock.sleep(250)
            }
            val viewport = nodes(kithmoot).filter { it.isScrollable }.singleOrNull()
            assertTrue("one owned settings viewport", viewport != null)
            val action = if (backwards) AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            if (!requireNotNull(viewport).performAction(action)) {
                await(kithmoot, "exact settings text at scroll boundary") { settingsText(kithmoot, value).isNotEmpty() }
                return
            }
        }
        assertTrue("bounded exact settings text", settingsText(kithmoot, value).isNotEmpty())
    }

    private fun requireCheckableSettingsRow(value: String): AccessibilityNodeInfo {
        val rows = settingsText(kithmoot, value).mapNotNull { label ->
            var row: AccessibilityNodeInfo? = label
            while (row != null && !row.isCheckable) row = row.parent
            row?.takeIf { it.packageName?.toString() == kithmoot && it.isVisibleToUser && it.isEnabled }
        }.distinct()
        assertTrue("one present checkable settings switch", rows.size == 1)
        return rows.single()
    }
    private fun requireNode(pkg: String, id: String) = requireNotNull(byId(pkg, id)) { "expected visible control $id" }
    private fun singleEditable(pkg: String) = nodes(pkg).single { it.isEditable }

    private fun clickText(pkg: String, value: String) {
        await(pkg, "visible control") { nodes(pkg).any { it.text?.toString() == value || it.contentDescription?.toString() == value } }
        val node = nodes(pkg).single { it.text?.toString() == value || it.contentDescription?.toString() == value }
        click(node)
    }

    private fun click(node: AccessibilityNodeInfo) {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        assertTrue("visible UI click", target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }

    private fun setText(node: AccessibilityNodeInfo, value: String) {
        assertTrue("visible editable control", node.isVisibleToUser && node.isEditable)
        assertTrue("UI text entry", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,
            Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }))
    }
}
