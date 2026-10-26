package dev.forgesworn.kithmoot.mls

import dev.forgesworn.kithmoot.BuildConfig
import dev.forgesworn.kithmoot.account.EngineSnapshotFormat
import kotlin.test.*

class InvitationAccessPolicyTest {
    private fun policy(debug: Boolean = false, profile: String = "disabled", network: String = "nostr",
        legacy: Boolean = false, localAdmin: Boolean = false) = InvitationAccessPolicy(debug, profile, network, legacy, localAdmin)
    private val magic = "VMLS/1 snapshot".toByteArray(Charsets.US_ASCII)

    @Test fun `default builds refuse both profiles and shared private ceremony`() {
        for (debug in listOf(false, true)) {
            val p = policy(debug)
            assertFailsWith<IllegalStateException> { p.requireLegacy() }
            assertFailsWith<IllegalStateException> { p.requireAdmin2() }
            assertFailsWith<IllegalStateException> { p.requirePrivateCeremony() }
        }
    }
    @Test fun `selected release allows only Admin2 and private ceremony`() {
        val p = policy(profile = "admin2", network = "private-invitations")
        p.requireAdmin2(); p.requirePrivateCeremony()
        assertFailsWith<IllegalStateException> { p.requireLegacy() }
    }
    @Test fun `debug legacy and local Admin2 retain separate authorizations`() {
        val legacy = policy(debug = true, legacy = true)
        legacy.requireLegacy(); legacy.requirePrivateCeremony()
        assertFailsWith<IllegalStateException> { legacy.requireAdmin2() }
        val admin = policy(debug = true, legacy = true, localAdmin = true)
        admin.requireLegacy(); admin.requireAdmin2(); admin.requirePrivateCeremony()
        val release = policy(legacy = true, localAdmin = true)
        assertFailsWith<IllegalStateException> { release.requireLegacy() }
        assertFailsWith<IllegalStateException> { release.requireAdmin2() }
        assertFailsWith<IllegalStateException> { release.requirePrivateCeremony() }
    }
    @Test fun `invalid and mixed profile configurations refuse`() {
        assertFailsWith<IllegalArgumentException> { policy(profile = "Admin2") }
        assertFailsWith<IllegalArgumentException> { policy(profile = "admin2") }
        assertFailsWith<IllegalArgumentException> { policy(profile = "admin2", network = "private-invitations", legacy = true) }
        assertFailsWith<IllegalArgumentException> { policy(profile = "admin2", network = "private-invitations", localAdmin = true) }
        assertFailsWith<IllegalArgumentException> { policy(localAdmin = true) }
    }
    @Test fun `actual restore dispatcher refuses unselected snapshots before native open with no fallback`() {
        val disabled = policy()
        val selected = policy(profile = "admin2", network = "private-invitations")
        val debugLegacy = policy(debug = true, legacy = true)
        val debugAdmin = policy(debug = true, legacy = true, localAdmin = true)
        for (p in listOf(disabled, selected, debugLegacy, debugAdmin)) {
            for (version in listOf(1, 2)) assertEquals(EngineSnapshotFormat.Legacy, EngineSnapshotFormat.readAuthorized(magic + byteArrayOf(version.toByte()), p))
        }
        for (version in listOf(3, 4)) {
            val bytes = magic + byteArrayOf(version.toByte())
            for (p in listOf(disabled, selected)) assertFailsWith<IllegalStateException> { EngineSnapshotFormat.readAuthorized(bytes, p) }
            for (p in listOf(debugLegacy, debugAdmin)) assertEquals(EngineSnapshotFormat.Invitation1, EngineSnapshotFormat.readAuthorized(bytes, p))
        }
        val adminBytes = magic + byteArrayOf(5)
        for (p in listOf(disabled, debugLegacy)) assertFailsWith<IllegalStateException> { EngineSnapshotFormat.readAuthorized(adminBytes, p) }
        for (p in listOf(selected, debugAdmin)) assertEquals(EngineSnapshotFormat.Admin2, EngineSnapshotFormat.readAuthorized(adminBytes, p))
        assertFailsWith<IllegalStateException> { EngineSnapshotFormat.readAuthorized(magic + byteArrayOf(6), selected) }
    }
    @Test fun `generated BuildConfig selects the real default policy`() {
        val p = InvitationAccessPolicy.current()
        // This test runs in both default and selected product Gradle matrices.
        if (BuildConfig.INVITATION_PROFILE == "admin2") {
            p.requireAdmin2(); p.requirePrivateCeremony()
            assertFailsWith<IllegalStateException> { p.requireLegacy() }
        } else {
            if (BuildConfig.DEBUG && BuildConfig.EXPERIMENTAL_INVITATION_MLS) {
                p.requireLegacy(); p.requirePrivateCeremony()
            } else {
                assertFailsWith<IllegalStateException> { p.requireLegacy() }
                assertFailsWith<IllegalStateException> { p.requirePrivateCeremony() }
            }
            if (BuildConfig.DEBUG && BuildConfig.EXPERIMENTAL_INVITATION_MLS && BuildConfig.EXPERIMENTAL_ADMIN_INVITATION_MLS) p.requireAdmin2()
            else assertFailsWith<IllegalStateException> { p.requireAdmin2() }
        }
    }

    @Test fun `bounded canonical parsing is independent of wire authorization`() {
        assertContentEquals(byteArrayOf(0), PrivateInvitationEncoding.decode("AA=="))
        assertContentEquals(byteArrayOf(0), PrivateInvitationEncoding.fixed("AA==", 1))
        for (bad in listOf("", "AA", "AB==", "!!!", "A".repeat(2_000_001))) {
            assertFailsWith<IllegalArgumentException> { PrivateInvitationEncoding.decode(bad) }
        }
        assertFailsWith<IllegalArgumentException> { PrivateInvitationEncoding.fixed("AA==", 2) }
    }
}
